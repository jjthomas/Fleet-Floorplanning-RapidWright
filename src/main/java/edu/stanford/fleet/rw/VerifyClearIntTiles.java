package edu.stanford.fleet.rw;

import com.xilinx.rapidwright.design.*;
import com.xilinx.rapidwright.device.*;
import com.xilinx.rapidwright.design.blocks.PBlock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

public class VerifyClearIntTiles {
    public static void main(String[] args) throws IOException {
        String shellPath = args[0]; // "/home/jamestho/2_hole_cl.dcp";
        Design shell = Design.readCheckpoint(shellPath);

        String dir = "."; // "/home/jamestho/floorplanning";
        String pblockStr = new String(Files.readAllBytes(Paths.get(dir + "/if_shell_exclude.pblock")), StandardCharsets.UTF_8);
        PBlock pblock = new PBlock(Device.getDevice(Device.AWS_F1), pblockStr.trim());
        boolean foundPip = false;
        for (Net n : shell.getNets()) {
            if (n.getName().contains("/ifn") || n.isStaticNet()) {
                continue;
            }
            for (PIP p : n.getPIPs()) {
                if (pblock.containsTile(p.getTile())) {
                    System.out.println(n.getName() + " " + p.getTile().getName());
                    foundPip = true;
                }
            }
        }
        if (!foundPip) {
            System.out.println("Verification of " + shellPath + " finished...");
        } else {
            System.out.println("ERROR: Verification of " + shellPath + " finished with errors, see above");
        }
    }
}
