package astar.viz;

import astar.core.BlockPoint;
import astar.pathing.ArrayBlockView;
import astar.pathing.BlockType;
import astar.pathing.EntityProfile;
import astar.pathing.MoveValidator;
import java.awt.Color;
import java.awt.image.BufferedImage;

/**
 * A map seen from above: for each column, the highest floor the entity can stand on, and what
 * that floor is. For a closed map (caves under a roof) this is the cave floors, not the roof.
 *
 * <p>It draws the terrain at one pixel per block, lit from the north-west, in one of two
 * {@link Style styles}. Scale the image up or down to zoom.
 */
public final class HeightMap {

    /** How the terrain is coloured. */
    public enum Style {
        /** Grey, lighter where higher, so a route coloured by height stands out. */
        GREY,
        /** The height ramp (dark blue low, yellow high), so heights read at a glance. */
        HEIGHT
    }

    /** Blocks, but nowhere to stand: solid rock, from above. */
    static final Color ROCK = new Color(0x55555B);
    /** No blocks at all in the column. */
    static final Color VOID = new Color(0xFFFFFF);

    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    /** The highest standable feet cell per column, or -1. Indexed x * sizeZ + z. */
    private final int[] height;
    private final BlockType[] top;
    private final int minHeight;
    private final int maxHeight;

    private HeightMap(ArrayBlockView world) {
        sizeX = world.sizeX();
        sizeY = world.sizeY();
        sizeZ = world.sizeZ();
        height = new int[sizeX * sizeZ];
        top = new BlockType[sizeX * sizeZ];
        MoveValidator v = new MoveValidator(world, EntityProfile.DEFAULT);
        int[] histogram = new int[sizeY];
        int standable = 0;
        for (int x = 0; x < sizeX; x++) {
            for (int z = 0; z < sizeZ; z++) {
                int i = x * sizeZ + z;
                height[i] = -1;
                for (int y = sizeY - 1; y >= 1; y--) {
                    BlockType b = world.blockAt(x, y, z);
                    if (b == BlockType.HAZARD && top[i] == null) {
                        top[i] = b;
                    }
                    if (b != BlockType.AIR && top[i] == null) {
                        top[i] = BlockType.SOLID;
                    }
                    if (v.canStand(x, y, z)) {
                        height[i] = y;
                        top[i] = b.shape(false) == BlockType.WATER ? BlockType.WATER : world.blockAt(x, y - 1, z);
                        histogram[y]++;
                        standable++;
                        break;
                    }
                }
            }
        }
        // The colour ramp spans the heights most floors are at, so a few stray blocks far
        // above or below don't squeeze the rest of the map into one colour.
        int lo = percentile(histogram, standable, 0.02);
        int hi = percentile(histogram, standable, 0.98);
        minHeight = standable == 0 ? 0 : lo;
        maxHeight = standable == 0 ? 1 : Math.max(hi, lo + 1);
    }

    /** The height below which {@code fraction} of the standable columns lie. */
    private static int percentile(int[] histogram, int total, double fraction) {
        long target = Math.round(fraction * Math.max(0, total - 1));
        long seen = 0;
        for (int y = 0; y < histogram.length; y++) {
            seen += histogram[y];
            if (seen > target) {
                return y;
            }
        }
        return histogram.length - 1;
    }

    /** Scans every column of the world. */
    public static HeightMap of(ArrayBlockView world) {
        return new HeightMap(world);
    }

    public int sizeX() {
        return sizeX;
    }

    public int sizeZ() {
        return sizeZ;
    }

    /** The highest cell the entity can stand in at this column, or -1 if there's none. */
    public int height(int x, int z) {
        if (x < 0 || z < 0 || x >= sizeX || z >= sizeZ) {
            return -1;
        }
        return height[x * sizeZ + z];
    }

    /** That cell as a point, or {@code null} if the column has nowhere to stand. */
    public BlockPoint top(int x, int z) {
        int h = height(x, z);
        return h < 0 ? null : new BlockPoint(x, h, z);
    }

    /**
     * The range of floor heights the colour ramp spans: the 2nd and 98th percentiles of the
     * columns' standable heights (at least one apart). Floors outside it take the end colours.
     */
    public int minHeight() {
        return minHeight;
    }

    public int maxHeight() {
        return maxHeight;
    }

    /** The terrain colour of one column. */
    public Color colour(int x, int z, Style style) {
        int i = x * sizeZ + z;
        int h = height[i];
        BlockType t = top[i];
        if (h < 0) {
            return t == null ? VOID : t == BlockType.HAZARD ? Palette.HAZARD : ROCK;
        }
        if (t == BlockType.HAZARD) {
            return Palette.HAZARD;
        }
        if (t == BlockType.WATER) {
            return Palette.WATER;
        }
        int west = x > 0 && height[i - sizeZ] >= 0 ? height[i - sizeZ] : h;
        int north = z > 0 && height[i - 1] >= 0 ? height[i - 1] : h;
        double relief = Math.max(-2, Math.min(2, (h - west) + (h - north)));
        if (style == Style.GREY) {
            double shade = 0.55 + 0.35 * h / Math.max(1, sizeY - 1) + 0.06 * relief;
            int grey = (int) Math.max(40, Math.min(235, 255 * shade));
            return new Color(grey, grey, grey - 4);
        }
        Color c = heightColour((h - minHeight) / (double) (maxHeight - minHeight));
        double light = 1 + 0.09 * relief;
        return new Color(clamp(c.getRed() * light), clamp(c.getGreen() * light),
                clamp(c.getBlue() * light));
    }

    /** The whole map at one pixel per block (x across, z down). */
    public BufferedImage image(Style style) {
        BufferedImage img = new BufferedImage(sizeX, sizeZ, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < sizeX; x++) {
            for (int z = 0; z < sizeZ; z++) {
                img.setRGB(x, z, colour(x, z, style).getRGB());
            }
        }
        return img;
    }

    /** Heights, low to high: dark blue, teal, green, yellow (a viridis-like ramp). */
    public static Color heightColour(double t) {
        double[][] stops = {{0x2B, 0x1B, 0x6E}, {0x21, 0x6E, 0x9B}, {0x1F, 0xA1, 0x87},
            {0x6C, 0xCB, 0x5A}, {0xF3, 0xD3, 0x2B}};
        t = Math.max(0, Math.min(1, t)) * (stops.length - 1);
        int i = Math.min(stops.length - 2, (int) t);
        double f = t - i;
        return new Color((int) (stops[i][0] + f * (stops[i + 1][0] - stops[i][0])),
                (int) (stops[i][1] + f * (stops[i + 1][1] - stops[i][1])),
                (int) (stops[i][2] + f * (stops[i + 1][2] - stops[i][2])));
    }

    private static int clamp(double v) {
        return (int) Math.max(0, Math.min(255, Math.round(v)));
    }
}
