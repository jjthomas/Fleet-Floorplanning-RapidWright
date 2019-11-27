package edu.stanford.fleet.rw;

import com.xilinx.rapidwright.design.*;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.edif.*;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;

public class CreateFFShellKernelIF {
    public static void main(String[] args) throws IOException {
        // String path = "/home/jamestho/blockCache/2018.2/d74dbc374ad21a9e/passthrough_4_StreamingCore_0_0_opt.dcp";
        String path = "/home/jamestho/kernel_opt.dcp";
        Design kernel = Design.readCheckpoint(path);
        // EDIFCellInst curTopCellInst = kernel.getNetlist().getCellInstFromHierName("inst");
        EDIFCellInst curTopCellInst = kernel.getNetlist().getTopCellInst();

        String dir = "."; // "/home/jamestho/floorplanning";
        String floorplanPath = dir + "/floorplan.txt";
        List<ColumnPlan> floorplan = FloorplanUtils.readFloorplan(floorplanPath);
        for (int columnId = 0; columnId < floorplan.size(); columnId++) {
            ColumnPlan cp = floorplan.get(columnId);
            Design d = new Design("IF" + columnId, Device.AWS_F1);
            EDIFCell top = d.getNetlist().getTopCell();
            int startSliceY = 899 - cp.templateIdx * cp.kernelHeight;
            int i = 0;
            String bottomLeftSite = null;
            String topRightSite = null;

            EDIFPort clockPort = top.createPort("clock", EDIFDirection.INPUT, 1);
            EDIFNet clockNet = top.createNet("clock_pn");
            clockNet.createPortInst(clockPort);
            EDIFNet gnd = EDIFTools.getStaticNet(NetType.GND, top, d.getNetlist());
            EDIFNet vcc = EDIFTools.getStaticNet(NetType.VCC, top, d.getNetlist());
            for (EDIFPort port : curTopCellInst.getCellType().getPorts()) {
                if (port.getName().equals("clock")) {
                    continue;
                }
                EDIFPort kernelPort = top.createPort(port.getName(),
                        port.isInput() ? EDIFDirection.OUTPUT : EDIFDirection.INPUT, port.getWidth());
                EDIFPort shellPort = top.createPort("shell_" + port.getName(),
                        port.isInput() ? EDIFDirection.INPUT : EDIFDirection.OUTPUT, port.getWidth());
                for (int j = 0; j < port.getWidth(); j++) {
                    int yOffset = i / 16;
                    String cellLetter = Character.toString((char) ('H' - i / 2 % 8));
                    String lutKind = i % 2 == 0 ? "5" : "6";
                    String lutBelPin = i % 2 == 0 ? "4" : "5";
                    String ffSuffix = i % 2 == 0 ? "2" : "";
                    Cell sff = d.createAndPlaceCell("sff" + i, Unisim.FDRE,
                            "SLICE_X" + cp.ifStartSlice + "Y" + (startSliceY - yOffset) + "/" + cellLetter + "FF" + ffSuffix);
                    Cell klut = d.createAndPlaceCell("klut" + i, Unisim.LUT1,
                            "SLICE_X" + cp.ifEndSlice + "Y" + (startSliceY - yOffset) + "/" + cellLetter + lutKind + "LUT");
                    klut.addProperty("INIT", "2'h2", EDIFValueType.STRING);
                    klut.removePinMapping("A" + lutKind);
                    klut.addPinMapping("A" + lutBelPin, "I0");
                    if (i % 2 == 0) d.getVccNet().createPin(false, cellLetter + "6", klut.getSiteInst());
                    bottomLeftSite = sff.getSite().toString();
                    if (topRightSite == null) {
                        topRightSite = klut.getSite().toString();
                    }
                    System.out.println(sff.getSite() + " " + klut.getSite());

                    Net newPhysNet = d.createNet("ifn" + i);
                    EDIFNet newNet = newPhysNet.getLogicalNet();

                    newNet.createPortInst(port.isOutput() ? "O" : "I0", klut);
                    EDIFNet kPortNet = top.createNet(port.getName() + j + "_kif");
                    kPortNet.createPortInst(port.isOutput() ? "I0" : "O", klut);
                    kPortNet.createPortInst(kernelPort, j);
                    Net kPortPhysNet = d.createNet(kPortNet);
                    BELPin src = klut.getBEL().getPin("O" + lutKind);
                    BELPin snk = klut.getSite().getBELPin(cellLetter + (i % 2 == 0 ? "MUX/" + cellLetter + "MUX" : "_O"));
                    klut.getSiteInst().routeIntraSiteNet(port.isOutput() ? newPhysNet : kPortPhysNet, src, snk);
                    BELPin bp = klut.getBEL().getPin(klut.getPhysicalPinMapping("I0"));
                    klut.getSiteInst().routeIntraSiteNet(port.isOutput() ? kPortPhysNet : newPhysNet, bp, bp);

                    newNet.createPortInst(port.isOutput() ? "D" : "Q", sff);
                    EDIFNet sPortNet = top.createNet(port.getName() + j + "_sif");
                    sPortNet.createPortInst(port.isOutput() ? "Q" : "D", sff);
                    sPortNet.createPortInst(shellPort, j);
                    clockNet.createPortInst("C", sff);
                    gnd.createPortInst("R", sff);
                    vcc.createPortInst("CE", sff);
                    Net sPortPhysNet = d.createNet(sPortNet);
                    src = sff.getSite().getBELPin(cellLetter + (i % 2 == 0 ? "_I" : "X"));
                    snk = sff.getBEL().getPin(sff.getPhysicalPinMapping("D"));
                    sff.getSiteInst().routeIntraSiteNet(port.isOutput() ? newPhysNet : sPortPhysNet, src, snk);
                    bp = sff.getBEL().getPin(sff.getPhysicalPinMapping("Q"));
                    sff.getSiteInst().routeIntraSiteNet(port.isOutput() ? sPortPhysNet : newPhysNet, bp, bp);

                    i++;
                }
            }
            // d.routeSites();
            d.setAutoIOBuffers(false);
            d.setDesignOutOfContext(true);
            // Router r = new Router(d);
            // r.routeDesign();
            d.writeCheckpoint(dir + "/shell_kernel_if" + columnId + ".dcp");
            BufferedWriter bw = new BufferedWriter(new FileWriter(dir + "/shell_kernel_if" + columnId + ".pblock"));
            bw.write(bottomLeftSite + ":" + topRightSite + "\n");
            bw.close();
        }
    }
}