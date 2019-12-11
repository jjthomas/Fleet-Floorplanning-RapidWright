package edu.stanford.fleet.rw;

import com.xilinx.rapidwright.design.*;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFPortInst;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;

public class MergeShellKernel {
    private static int getXCoord(String pip) {
        int idx = pip.indexOf("_X");
        return Integer.parseInt(pip.substring(idx + 2, pip.indexOf('Y', idx)));
    }

    public static void main(String[] args) throws IOException {
        int[] upperLeafSitesOrdered = new int[] {3, 2, 8, 9, 10, 11, 19, 18, 17, 16, 24, 25, 26, 27, 31, 30};
        int[] lowerLeafSitesOrdered = new int[] {0, 1, 7, 6, 5, 4, 12, 13, 14, 15, 23, 22, 21, 20, 28, 29};
        List<String> clkBufferVccPips = Arrays.asList("INT_X72Y450/INT.VCC_WIRE->>IMUX_W44",
                "INT_INTF_L_CMT_X72Y450/INT_INTF_L_CMT.IMUX_R44->>IMUX_CMT_XIPHY44");

        String kernelName = args[0];
        String dir = "."; // "/home/jamestho/floorplanning";
        String shellPath = dir + "/cl_with_holes.dcp";
        Design shell = Design.readCheckpoint(shellPath);
        shell.getNetlist().consolidateAllToWorkLibrary();
        // shell.removeSiteInst(shell.getSiteInstFromSiteName("SLICE_X13Y529"));
        /*
        for (int x : new int[]{11, 13}) {
            for (int y : new int[]{528, 588}) {
                for (int i = 0; i < 12; i++) {
                    SiteInst si = shell.getSiteInstFromSiteName("SLICE_X" + x + "Y" + (y + i));
                    if (si != null && si.getSiteCTags().isEmpty()) {
                        System.out.println("Removing empty site: " + si.getName());
                        shell.removeSiteInst(si);
                    }
                }
            }
        }
        */
        String floorplanPath = dir + "/floorplan.txt";
        List<ColumnPlan> floorplan = FloorplanUtils.readFloorplan(floorplanPath);

        int kernelNum = 0;
        for (int columnId = 0; columnId < floorplan.size(); columnId++) {
            String kernelPath = dir + "/if_" + kernelName + "_routed" + columnId + ".dcp";
            Design kernel = Design.readCheckpoint(kernelPath);
            // Load kernel's static PIPs
            Map<NetType, List<PIP>> kernelStaticPips = new HashMap<>();
            kernelStaticPips.put(NetType.GND, new ArrayList<>(kernel.getStaticNet(NetType.GND).getPIPs()));
            List<PIP> kernelVccPips = new ArrayList<>(kernel.getStaticNet(NetType.VCC).getPIPs());
            kernelStaticPips.put(NetType.VCC, kernelVccPips);
            // Enable for FF IP
            int removed = 0;
            for (int i = kernelVccPips.size() - 1; i >= 0; i--) {
                if (clkBufferVccPips.contains(kernelVccPips.get(i).toString())) { // leftover PIP for removed clock buffer
                    kernelVccPips.remove(i);
                    removed++;
                }
            }
            if (removed != 2) {
                throw new RuntimeException("Expected to remove " + clkBufferVccPips + " from " + kernelPath +
                        ". Found " + removed + " of these");
            }
            kernel.getNetlist().consolidateAllToWorkLibrary();
            // kernel.getNetlist().renameNetlistAndTopCell("ktop");
            Module kernelM = new Module(kernel);

            shell.getNetlist().migrateCellAndSubCells(kernelM.getNetlist().getTopCell());

            /*
            BufferedReader br = new BufferedReader(new FileReader("/home/jamestho/no_route_pips.txt"));
            String pip;
            Set<String> f1ShellPips = new HashSet<>();
            while ((pip = br.readLine()) != null) {
                f1ShellPips.add(pip);
            }
            br.close();
            */
            // Load kernel's clock PIPs
            List<PIP> kernelClockPips = new ArrayList<>();
            BufferedReader br = new BufferedReader(new FileReader(dir + "/" + kernelName + "_clock_pips" + columnId + ".txt"));
            String pip;
            int minXCoord = 10000;
            while ((pip = br.readLine()) != null) {
                // if (!f1ShellPips.contains(pip)) {
                kernelClockPips.add(new PIP(pip, Device.getDevice(Device.AWS_F1)));
                if (pip.startsWith("RCLK_INT_") || pip.startsWith("INT_")) {
                    int xCoord = getXCoord(pip);
                    if (xCoord < minXCoord) {
                        minXCoord = xCoord;
                    }
                }
                // }
            }
            br.close();
            for (int i = kernelClockPips.size() - 1; i >= 0; i--) {
                String curPip = kernelClockPips.get(i).toString();
                if (curPip.startsWith("RCLK_INT_") || curPip.startsWith("INT_")) {
                    if (getXCoord(curPip) == minXCoord) {
                        kernelClockPips.remove(i); // this PIP is for an interface FF; we want the shell's clock PIPs for these FFs
                    }
                }
            }

            ColumnPlan cp = floorplan.get(columnId);
            for (int verticalIdx = 0; verticalIdx < cp.numKernels; verticalIdx++) {
                // Remove IF physical nets (other than nets connecting IF to shell) from shell
                List<Net> netsToRemove = new ArrayList<>();
                for (Net n : shell.getNets()) {
                    if (n.getName().startsWith("streaming_wrapper/if" + kernelNum + "/") && !n.getName().endsWith("_sif")) { // was previously just adding ifn nets
                        netsToRemove.add(n);
                    }
                }
                for (Net n : netsToRemove) {
                    shell.removeNet(n);
                }

                // Remove IF cells & site insts from shell
                List<Cell> cellsToRemove = new ArrayList<>();
                Set<SiteInst> siteInstsToRemove = new HashSet<>();
                for (Cell c : shell.getCells()) {
                    if (c.getName().startsWith("streaming_wrapper/if" + kernelNum + "/klut")) {
                        cellsToRemove.add(c);
                        SitePinInst inputPin = c.getSitePinFromLogicalPin("I0", null);
                        if (inputPin.getNet() != null && inputPin.getNet().isStaticNet()) {
                            inputPin.getNet().removePin(inputPin, true);
                        }
                    } else if (c.getName().startsWith("streaming_wrapper/if" + kernelNum + "/sff")) {
                        cellsToRemove.add(c);
                    }
                }
                for (Cell c : cellsToRemove) {
                    siteInstsToRemove.add(c.getSiteInst());
                    shell.removeCell(c);
                }
                // TODO do we actually need to transfer these pins?
                Map<String, List<SitePinInst>> pinsToTransfer = new HashMap<>();
                for (SiteInst si : siteInstsToRemove) {
                    for (SitePinInst spi : si.getSitePinInstMap().values()) {
                        if (spi.getNet() != null && spi.getNet().getName().startsWith("streaming_wrapper")) {
                            if (!pinsToTransfer.containsKey(si.getName())) {
                                pinsToTransfer.put(si.getName(), new ArrayList<>());
                            }
                            pinsToTransfer.get(si.getName()).add(spi);
                        }
                    }
                    shell.removeSiteInst(si, true);
                }

                // Create kernel instance (includes its own IF) and place it at the appropriate location
                ModuleInst mi = shell.createModuleInst("streaming_wrapper/kernel" + kernelNum, kernelM);
                SiteTypeEnum anchorType = kernelM.getAnchor().getSiteTypeEnum();
                mi.place(kernelM.getAnchor().getSite().getNeighborSite(0, (cp.templateIdx - verticalIdx) *
                        FloorplanUtils.convertSliceHeight(anchorType, cp.kernelHeight)));

                // The EDIF cell for the IF we just stripped out from the shell
                EDIFCellInst oldIf = shell.getTopEDIFCell().getCellInst("streaming_wrapper").getCellType()
                        .removeCellInst("if" + kernelNum);
                EDIFCellInst newKernel = shell.getTopEDIFCell().getCellInst("streaming_wrapper").getCellType()
                        .getCellInst("kernel" + kernelNum);
                // Disconnect logical nets connecting shell and IF from the old IF and attach them to the new kernel's IF
                for (EDIFPortInst pi : oldIf.getPortInsts()) {
                    // if (pi.getPort().getName().contains("shell_")) {
                    EDIFNet portNet = pi.getNet();
                    portNet.removePortInst(pi);
                    portNet.createPortInst(newKernel.getPort(pi.getPort().getBusName()), pi.getIndex(), newKernel);
                    // }
                }
                EDIFNet portNet = shell.getTopEDIFCell().getCellInst("streaming_wrapper").getCellType()
                        .getNet("clock0_pn");
                portNet.createPortInst(newKernel.getCellType().getPort("clock"), newKernel);

                for (Map.Entry<String, List<SitePinInst>> e : pinsToTransfer.entrySet()) {
                    SiteInst newSi = shell.getSiteInst(e.getKey());
                    for (SitePinInst spi : e.getValue()) {
                        spi.setSiteInst(newSi, true);
                        spi.getNet().addPin(spi);
                    }
                }

                // Rename physical nets from IF to shell to match the new logical netlist, which has the IF inside the kernel module
                List<Net> netsToRename = new ArrayList<>();
                for (Net n : shell.getNets()) {
                    if (n.getName().startsWith("streaming_wrapper/if" + kernelNum + "/")) {
                        netsToRename.add(n);
                    }
                }
                for (Net n : netsToRename) {
                    n.rename(n.getName().replace("streaming_wrapper/if" + kernelNum, "streaming_wrapper/kernel" + kernelNum + "/if"));
                }

                // Translate static PIPs
                Tile origAnchor = kernelM.getAnchor().getTile();
                Tile newAnchor = mi.getAnchor().getTile();
                for (NetType nt : new NetType[]{NetType.VCC, NetType.GND}) {
                    Net staticNet = shell.getStaticNet(nt);
                    Set<String> staticPips = new HashSet<>();
                    for (PIP p : staticNet.getPIPs()) {
                        staticPips.add(p.toString());
                    }
                    for (PIP p : kernelStaticPips.get(nt)) {
                        Tile translatedTile = Module.getCorrespondingTile(p.getTile(), newAnchor, origAnchor);
                        PIP translatedPip = new PIP(translatedTile, p.getStartWireIndex(), p.getEndWireIndex());
                        if (!staticPips.contains(translatedPip.toString())) {
                            // System.out.println(translatedPip);
                            staticNet.addPIP(translatedPip);
                        }
                    }
                }
                // Translate clock PIPs
                Net shellClock = shell.getNet("clk_main_a0");
                for (PIP p : kernelClockPips) {
                    Tile adjustedNewAnchor = newAnchor;
                    // TODO specific to height of 30
                    if (verticalIdx % 2 == 1 && p.toString().startsWith("RCLK")) {
                        adjustedNewAnchor = mi.getAnchor().getSite().getNeighborSite(0,
                                FloorplanUtils.convertSliceHeight(anchorType, 30)).getTile();
                    }
                    Tile translatedTile = Module.getCorrespondingTile(p.getTile(), adjustedNewAnchor, origAnchor);
                    PIP translatedPip = new PIP(translatedTile, p.getStartWireIndex(), p.getEndWireIndex());
                    if (verticalIdx % 2 == 1 && p.toString().startsWith("RCLK")) {
                        boolean matched = false;
                        for (int i = 0; i < 16; i++) {
                            if (translatedPip.toString().contains("CLK_LEAF_SITES_" + upperLeafSitesOrdered[i])) {
                                translatedPip = new PIP(translatedPip.toString().replace(
                                        "CLK_LEAF_SITES_" + upperLeafSitesOrdered[i], "CLK_LEAF_SITES_" + lowerLeafSitesOrdered[i]),
                                        Device.getDevice(Device.AWS_F1));
                                matched = true;
                                break;
                            }
                        }
                        if (!matched) {
                            throw new RuntimeException("Didn't recognize the CLK_LEAF_SITE in clock PIP " + p);
                        }
                    }
                    shellClock.addPIP(translatedPip);
                }
                kernelNum++;
            }
        }

        /*
        for (NetType nt : new NetType[]{NetType.VCC, NetType.GND}) {
            List<PIP> staticPips = shell.getStaticNet(nt).getPIPs();
            System.out.println(nt + " " + staticPips.size() + " " + new HashSet<>(staticPips).size());
        }
        */

        // System.out.println("Is placed: " + shell.getCell("streaming_wrapper/kernel1/if/klut161").isPlaced());
        // printSiteInst(shell.getCell("streaming_wrapper/kernel0/if/klut161").getSiteInst());
        // System.out.println();
        // printSiteInst(shell.getCell("streaming_wrapper/kernel1/if/klut161").getSiteInst());

        /*
        PBlock kernelRegion = new PBlock(Device.getDevice(Device.AWS_F1), "CLOCKREGION_X0Y8:CLOCKREGION_X0Y9");
        Net mainClock = shell.getNet("clk_main_a0");
        BufferedReader br = new BufferedReader(new FileReader("/home/jamestho/no_route_pips.txt"));
        String pip;
        List<PIP> pipsToRemove = new ArrayList<>();
        while ((pip = br.readLine()) != null) {
            PIP p = new PIP(pip, Device.getDevice(Device.AWS_F1));
            if (kernelRegion.containsTile(p.getTile())) {
                pipsToRemove.add(p);
                mainClock.addPIP(p);
            }
        }
        ArrayList<SitePinInst> clockPinsToRoute = new ArrayList<>();
        for (Net n : shell.getNets()) {
            if (n.getName().contains("clock0_pn")) {
                for (SiteInst si : n.getSiteInsts()) {
                    for (Map.Entry<String, Net> e : si.getCTagMap().entrySet()) {
                        if (e.getValue() == n && !e.getKey().contains("INV")) {
                            SitePinInst clockPin = new SitePinInst(false, e.getKey(), si);
                            clockPin.setNet(mainClock);
                            clockPinsToRoute.add(clockPin);
                        }
                    }
                }
            }
        }
        new Router(shell).routePinsReEntrant(clockPinsToRoute, false);
        for (PIP p : pipsToRemove) {
            mainClock.removePIP(p);
        }
        */

        shell.setAutoIOBuffers(false);
        shell.setDesignOutOfContext(true);
        shell.writeCheckpoint(dir + "/merged_shell_" + kernelName + ".dcp");
    }
}
