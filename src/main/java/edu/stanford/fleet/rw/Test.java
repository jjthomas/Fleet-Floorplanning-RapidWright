package edu.stanford.fleet.rw;

import com.xilinx.rapidwright.design.*;
import com.xilinx.rapidwright.design.blocks.PBlock;
import com.xilinx.rapidwright.device.Device;

import java.util.ArrayList;

public class Test {
    public static void main(String[] args) {
        Design unrouted = Design.readCheckpoint("/Users/joseph/Fleet-Floorplanning-RapidWright/if_Summer0_placed.dcp");
        Design routed = Design.readCheckpoint("/Users/joseph/Fleet-Floorplanning-RapidWright/if_Summer0_simple_routed.dcp");
        PBlock pblock = new PBlock(Device.getDevice(Device.AWS_F1),
                "SLICE_X13Y630:SLICE_X16Y659 RAMB18_X1Y252:RAMB18_X1Y263 RAMB36_X1Y126:RAMB36_X1Y131 DSP48E2_X1Y252:DSP48E2_X1Y263");
        for (Net n : unrouted.getNets()) {
            Net n1 = routed.getNet(n.getName());
            if (n.getPIPs().size() == 0) {
                n1.setPIPs(new ArrayList<>());
            }
            /*
            for (SitePinInst spi : n1.getPins()) {
                n.createPin(spi.isOutPin(), spi.getName(), spi.getSiteInst());
            }
            */
            /*
            if (n.getPins().size() != n1.getPins().size()) {
                System.out.println("Mismatch in pins at net " + n + "; " + n.getPins().size() + " vs " + n1.getPins().size());
                System.out.println("\tExisting PIPs: " + n.getPIPs().size() + " vs " + n1.getPIPs().size());
                System.out.println("\t" + n.getPins());
                System.out.println("\t" + n1.getPins());
            }
            */
        }
        Router r = new Router(routed);
        r.setRoutingPblock(pblock);
        // r.elaboratePhysicalNets();
        r.routeDesign();
        routed.writeCheckpoint("/Users/joseph/Fleet-Floorplanning-RapidWright/if_Summer0_rw_routed.dcp");
    }
}
