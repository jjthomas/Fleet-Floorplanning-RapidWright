package edu.stanford.fleet.rw;

import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

class ColumnPlan {
    public int ifStartSlice, ifEndSlice, kernelEndSlice, kernelHeight, numKernels, templateIdx;

    public ColumnPlan(int ifStartSlice, int ifEndSlice, int kernelEndSlice, int kernelHeight, int numKernels) {
        this.ifStartSlice = ifStartSlice;
        this.ifEndSlice = ifEndSlice;
        this.kernelEndSlice = kernelEndSlice;
        this.kernelHeight = kernelHeight;
        this.numKernels = numKernels;
        templateIdx = 60 / kernelHeight;
    }
}

public class FloorplanUtils {
    public static Device device;
    public static Tile[][] tiles;
    private static final int SLICE_MAX_Y = 899, RAMB18_MAX_Y = 359, RAMB36_MAX_Y = 179, DSP48E2_MAX_Y = 359;
    static {
        device = Device.getDevice(Device.AWS_F1);
        tiles = device.getTiles();
    }

    public static TileTypeEnum getColumnType(int c) {
        if (c < 0 || c >= tiles[0].length) {
            return TileTypeEnum.GTX_COMMON; // return unused type
        }
        for (int i = 0; i < tiles.length; i++) {
            switch (tiles[i][c].getTileTypeEnum()) {
                case INT:
                case INT_INTF_L:
                case INT_INTF_R:
                case BRAM:
                case CLEL_R:
                case CLEM:
                case CLEM_R:
                case DSP: return tiles[i][c].getTileTypeEnum();
            }
        }
        return TileTypeEnum.GTX_COMMON; // return unused type
    }

    public static Tile getFirstTileOfType(int c) {
        for (int i = 0; i < tiles.length; i++) {
            switch (tiles[i][c].getTileTypeEnum()) {
                case BRAM:
                case CLEL_R:
                case CLEM:
                case CLEM_R:
                case DSP: return tiles[i][c];
            }
        }
        return null; // shouldn't be reached
    }

    private static int getAbsColForSliceCol(int sliceCol) {
        return device.getSite("SLICE_X" + sliceCol + "Y899").getTile().getColumn();
    }

    private static int getLocalColForAbsCol(int absCol) {
        return Integer.parseInt(getFirstTileOfType(absCol).getSites()[0].getName().split("_")[1].split("Y")[0].substring(1));
    }

    public static String getPblockForSliceRange(int startSliceCol, int endSliceCol, int sliceHeight, int verticalIdx,
                                                boolean includeHalo) {
        int startCol = getAbsColForSliceCol(startSliceCol);
        int endCol = getAbsColForSliceCol(endSliceCol);
        int minSliceCol = startSliceCol, maxSliceCol = endSliceCol, minBramCol = -1, maxBramCol = -1, minDspCol = -1, maxDspCol = -1;
        if (includeHalo && getColumnType(startCol - 1) == TileTypeEnum.INT) {
            switch (getColumnType(startCol - 2)) {
                case CLEL_R:
                case CLEM:
                case CLEM_R:
                    minSliceCol = getLocalColForAbsCol(startCol - 2);
                    break;
                case INT_INTF_L:
                case INT_INTF_R:
                    if (getColumnType(startCol - 3) == TileTypeEnum.BRAM) {
                        minBramCol = getLocalColForAbsCol(startCol - 3);
                        maxBramCol = getLocalColForAbsCol(startCol - 3);
                    } else if (getColumnType(startCol - 3) == TileTypeEnum.DSP) {
                        minDspCol = getLocalColForAbsCol(startCol - 3);
                        maxDspCol = getLocalColForAbsCol(startCol - 3);
                    }
            }
        }
        for (int col = startCol; col <= endCol; col++) {
            switch (getColumnType(col)) {
                case BRAM:
                    if (minBramCol == -1) {
                        minBramCol = getLocalColForAbsCol(col);
                    }
                    maxBramCol = getLocalColForAbsCol(col);
                    break;
                case DSP:
                    if (minDspCol == -1) {
                        minDspCol = getLocalColForAbsCol(col);
                    }
                    maxDspCol = getLocalColForAbsCol(col);
            }
        }
        if (includeHalo && getColumnType(endCol + 1) == TileTypeEnum.INT) {
            switch (getColumnType(endCol + 2)) {
                case CLEL_R:
                case CLEM:
                case CLEM_R:
                    maxSliceCol = getLocalColForAbsCol(endCol + 2);
                    break;
                case INT_INTF_L:
                case INT_INTF_R:
                    if (getColumnType(endCol + 3) == TileTypeEnum.BRAM) {
                        if (minBramCol == -1) {
                            minBramCol = getLocalColForAbsCol(endCol + 3);
                        }
                        maxBramCol = getLocalColForAbsCol(endCol + 3);
                    } else if (getColumnType(endCol + 3) == TileTypeEnum.DSP) {
                        if (minDspCol == -1) {
                            minDspCol = getLocalColForAbsCol(endCol + 3);
                        }
                        maxDspCol = getLocalColForAbsCol(endCol + 3);
                    }
            }
        }
        String pblock = String.format("SLICE_X%dY%d:SLICE_X%dY%d", minSliceCol, SLICE_MAX_Y - sliceHeight * (verticalIdx + 1) + 1,
                maxSliceCol, SLICE_MAX_Y - sliceHeight * verticalIdx);
        if (minBramCol != -1) {
            int ramb18Height = sliceHeight / 5 * 2;
            int ramb36Height = sliceHeight / 5;
            pblock += String.format(" RAMB18_X%dY%d:RAMB18_X%dY%d", minBramCol, RAMB18_MAX_Y - ramb18Height * (verticalIdx + 1) + 1,
                    maxBramCol, RAMB18_MAX_Y - ramb18Height * verticalIdx);
            pblock += String.format(" RAMB36_X%dY%d:RAMB36_X%dY%d", minBramCol, RAMB36_MAX_Y - ramb36Height * (verticalIdx + 1) + 1,
                    maxBramCol, RAMB36_MAX_Y - ramb36Height * verticalIdx);
        }
        if (minDspCol != -1) {
            int dspHeight = sliceHeight / 5 * 2;
            pblock += String.format(" DSP48E2_X%dY%d:DSP48E2_X%dY%d", minDspCol, DSP48E2_MAX_Y - dspHeight * (verticalIdx + 1) + 1,
                    maxDspCol, DSP48E2_MAX_Y - dspHeight * verticalIdx);
        }
        return pblock;
    }

    public static List<ColumnPlan> readFloorplan(String floorplanPath) throws IOException {
        BufferedReader br = new BufferedReader(new FileReader(floorplanPath));
        List<ColumnPlan> floorplan = new ArrayList<>();
        String line;
        while ((line = br.readLine()) != null) {
            line = line.trim();
            if (line.startsWith("#")) {
                continue;
            }
            Scanner s = new Scanner(line);
            floorplan.add(new ColumnPlan(s.nextInt(), s.nextInt(), s.nextInt(), s.nextInt(), s.nextInt()));
        }
        return floorplan;
    }

    public static void main(String[] args) throws IOException {
        System.out.println(readFloorplan("floorplan.txt").size());
    }
}
