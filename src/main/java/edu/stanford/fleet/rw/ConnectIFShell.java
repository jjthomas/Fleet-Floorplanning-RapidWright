package edu.stanford.fleet.rw;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Module;
import com.xilinx.rapidwright.design.ModuleInst;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.router.Router;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ConnectIFShell {
    public static void main(String[] args) throws IOException {
        boolean USE_ROUTED_SHELL = false;
        // String shellPath = "/home/jamestho/blockCache/2018.2/af61c7608cc5a79d/passthrough_2_StreamingMemoryContr_0_opt.dcp";
        /*
        String shellPath = "/home/jamestho/blockCache/2018.2/9910d697c0e25fa9/passthrough_15_StreamingMemoryContr_0_opt.dcp";
        if (USE_ROUTED_SHELL) {
            shellPath = shellPath.replace("opt.dcp", "0_routed.dcp");
        }
        */
        String dir = "."; // "/home/jamestho/floorplanning";
        String shellPath = dir + "/shell_opt.dcp";
        Design shellD = Design.readCheckpoint(shellPath);
        Design d = new Design("top", Device.AWS_F1);
        Module shellM = new Module(shellD);
        EDIFNetlist netlist = d.getNetlist();
        netlist.migrateCellAndSubCells(shellD.getTopEDIFCell());
        if (USE_ROUTED_SHELL) {
            ModuleInst shellInst = d.createModuleInst("shell", shellM);
            shellInst.place(shellM.getAnchor().getSite());
        } else {
            netlist.getTopCell().createChildCellInst("shell", shellD.getTopEDIFCell());
        }

        String floorplanPath = dir + "/floorplan.txt";
        List<ColumnPlan> floorplan = FloorplanUtils.readFloorplan(floorplanPath);

        BufferedWriter bw = new BufferedWriter(new FileWriter(dir + "/if_shell_exclude.pblock"));
        int numIfs = 0;
        for (int columnId = 0; columnId < floorplan.size(); columnId++) {
            String ifPath = dir + "/shell_kernel_if_routed" + columnId + ".dcp";
            Design ifD = Design.readCheckpoint(ifPath);
            netlist.migrateCellAndSubCells(ifD.getTopEDIFCell());
            Module ifM = new Module(ifD);
            ColumnPlan cp = floorplan.get(columnId);
            for (int i = 0; i < cp.numKernels; i++) { // i = -1; i < 1;
                ModuleInst ifInst = d.createModuleInst("if" + numIfs, ifM); // (i + 1)
                ifInst.place(ifM.getAnchor().getSite().getNeighborSite(0, (cp.templateIdx - i) * cp.kernelHeight));
                bw.write(FloorplanUtils.getPblockForSliceRange(cp.ifEndSlice, cp.kernelEndSlice, cp.kernelHeight, i, true) + "\n");
                numIfs++;
                /*
                for (EDIFPort iPort : d.getTopEDIFCell().getCellInst("if" + (i + 1)).getCellType().getPorts()) {
                    if (!iPort.getName().contains("shell_")) {
                        EDIFPort topPort = d.getTopEDIFCell().createPort("if" + (i + 1) + "_" + iPort.getName(), iPort.getDirection(), iPort.getWidth());
                        for (int j = 0; j < iPort.getWidth(); j++) {
                            EDIFNet portNet = d.getTopEDIFCell().createNet(topPort.getName() + "_" + j + "_pn");
                            portNet.createPortInst(iPort, j, d.getTopEDIFCell().getCellInst("if" + (i + 1)));
                            portNet.createPortInst(topPort, j);
                        }
                    }
                }
                */
            }
            bw.newLine();
        }
        bw.close();

        int i = 0;
        for (EDIFPort sPort : d.getTopEDIFCell().getCellInst("shell").getCellType().getPorts()) {
            if (!sPort.getBusName().contains("streamingCores_")) {
                if (!sPort.getBusName().contains("streamingCoreReset")) {
                    EDIFPort topPort = d.getTopEDIFCell().createPort(sPort.getName().replace("axi_", ""),
                            sPort.getDirection(), sPort.getWidth());
                    EDIFNet portNet = null;
                    for (int j = 0; j < sPort.getWidth(); j++) {
                        portNet = d.getTopEDIFCell().createNet(topPort.getName() + j + "_pn");
                        portNet.createPortInst(sPort, j, d.getTopEDIFCell().getCellInst("shell"));
                        portNet.createPortInst(topPort, j);
                    }
                    // Enable for FF IF
                    if (sPort.getBusName().equals("clock")) {
                        for (int j = 0; j < numIfs; j++) {
                            EDIFCellInst ifInst = d.getTopEDIFCell().getCellInst("if" + j);
                            EDIFPort iPort = ifInst.getCellType().getPort("clock");
                            portNet.createPortInst(iPort, ifInst);
                        }
                    }
                } else {
                    EDIFNet resetNet = d.getTopEDIFCell().createNet("sifn" + i);
                    i++;
                    resetNet.createPortInst(sPort, d.getTopEDIFCell().getCellInst("shell"));
                    for (int j = 0; j < numIfs; j++) {
                        EDIFCellInst ifInst = d.getTopEDIFCell().getCellInst("if" + j);
                        EDIFPort iPort = ifInst.getCellType().getPort("shell_reset");
                        resetNet.createPortInst(iPort, ifInst);
                    }
                }
                continue;
            }
            int ifIdx = Integer.parseInt(sPort.getBusName().split("_")[2]);
            String iPortName = "shell_" + sPort.getBusName().replace("streamingCores_" + ifIdx + "_", "");
            EDIFPort iPort = d.getTopEDIFCell().getCellInst("if" + ifIdx).getCellType()
                    .getPort(iPortName);
            for (int j = 0; j < sPort.getWidth(); j++) {
                EDIFNet sifNet = d.getTopEDIFCell().createNet("sifn" + i);
                sifNet.createPortInst(sPort, j, d.getTopEDIFCell().getCellInst("shell"));
                sifNet.createPortInst(iPort, j, d.getTopEDIFCell().getCellInst("if" + ifIdx));
                i++;
            }
        }
        /*
        List<Net> toRemove = new ArrayList<>();
        for (Net n : d.getNets()) {
            if (n.getName().endsWith("_kif")) {
                toRemove.add(n);
            }
        }
        for (Net n : toRemove) {
            d.removeNet(n);
        }
        */
        for (int ifIdx = 0; ifIdx < numIfs; ifIdx++) {
            for (EDIFPort iPort : d.getTopEDIFCell().getCellInst("if" + ifIdx).getCellType().getPorts()) {
                if (!iPort.getBusName().startsWith("shell") && !iPort.getBusName().equals("clock")) {
                    EDIFPort topPort = d.getTopEDIFCell().createPort("if" + ifIdx + "_" + iPort.getName(),
                            iPort.getDirection(), iPort.getWidth());
                    for (int j = 0; j < iPort.getWidth(); j++) {
                        EDIFNet portNet = d.getTopEDIFCell().createNet(topPort.getName() + "_" + j + "_pn");
                        portNet.createPortInst(iPort, j, d.getTopEDIFCell().getCellInst("if" + ifIdx));
                        portNet.createPortInst(topPort, j);
                    }
                }
            }
        }
        // d.routeSites();
        // new Router(d).routeDesign();
        d.setAutoIOBuffers(false);
        d.setDesignOutOfContext(true);
        d.writeCheckpoint(dir + "/if_shell.dcp");
    }
}
