package astar.pathing;

import java.util.Arrays;

/**
 * A bounded, mutable in-memory world for tests, the visualizer and imported maps. Coordinates
 * run from 0 to size - 1 on each axis; anything outside is {@link BlockType#VOID}.
 *
 * <p>Each cell takes one byte, so a large imported map (say 576 x 256 x 502, about 74 million
 * cells) fits in about 74 MB.
 */
public final class ArrayBlockView implements BlockView {
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private static final BlockType[] TYPES = BlockType.values();

    private final byte[] blocks; // BlockType ordinals; AIR is 0
    private int version; // bumped on every change, so caches know to start again
    // The columns changed, one entry per change: entry i is version logBase + i + 1. Dropped
    // when full, so things built on the world can tell which columns changed since, if not
    // too much has.
    private static final int LOG_LIMIT = 1 << 16;
    private int[] logColumns = new int[64];
    private int logSize;
    private int logBase;

    /** Creates a world filled with air. */
    public ArrayBlockView(int sizeX, int sizeY, int sizeZ) {
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        if (sizeY > 2048) {
            // Packed positions hold y in [-2048, 2047]: higher cells couldn't be searched.
            throw new IllegalArgumentException("World too tall: " + sizeY + " cells (at most 2048)");
        }
        long cells = (long) sizeX * sizeY * sizeZ;
        if (cells > Integer.MAX_VALUE - 8) {
            throw new IllegalArgumentException("World too big: " + cells + " cells");
        }
        this.blocks = new byte[(int) cells];
        if (BlockType.AIR.ordinal() != 0) {
            Arrays.fill(blocks, (byte) BlockType.AIR.ordinal());
        }
    }

    /**
     * A world over the given cells, {@link BlockType} ordinals indexed {@code (y * sizeZ + z)
     * * sizeX + x}, used as is (not copied): the quick way to build a big one at once.
     */
    public static ArrayBlockView of(int sizeX, int sizeY, int sizeZ, byte[] ordinals) {
        if (ordinals.length != (long) sizeX * sizeY * sizeZ) {
            throw new IllegalArgumentException(ordinals.length + " cells for a " + sizeX + " x "
                    + sizeY + " x " + sizeZ + " world");
        }
        return new ArrayBlockView(sizeX, sizeY, sizeZ, ordinals);
    }

    private ArrayBlockView(int sizeX, int sizeY, int sizeZ, byte[] blocks) {
        if (sizeY > 2048) {
            throw new IllegalArgumentException("World too tall: " + sizeY + " cells (at most 2048)");
        }
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.blocks = blocks;
    }

    /**
     * Builds a world from ASCII layers, bottom first. {@code layers[y][z].charAt(x)}:
     * {@code '#'} solid, {@code '!'} hazard, {@code '_'} bottom slab, {@code '^'} stairs,
     * {@code '-'} carpet, {@code 's'} soul sand, {@code 'f'} fence (1.5 tall), {@code '~'} water,
     * {@code 'H'} ladder, anything else air.
     */
    public static ArrayBlockView fromLayers(String[]... layers) {
        ArrayBlockView world = new ArrayBlockView(layers[0][0].length(), layers.length, layers[0].length);
        for (int y = 0; y < layers.length; y++) {
            for (int z = 0; z < layers[y].length; z++) {
                for (int x = 0; x < layers[y][z].length(); x++) {
                    world.set(x, y, z, switch (layers[y][z].charAt(x)) {
                        case '#' -> BlockType.SOLID;
                        case '!' -> BlockType.HAZARD;
                        case '_' -> BlockType.PARTIAL_8;
                        case '^' -> BlockType.STAIRS;
                        case '-' -> BlockType.PARTIAL_1;
                        case 's' -> BlockType.PARTIAL_14;
                        case 'f' -> BlockType.TALL;
                        case '~' -> BlockType.WATER;
                        case 'H' -> BlockType.CLIMBABLE;
                        default -> BlockType.AIR;
                    });
                }
            }
        }
        return world;
    }

    /**
     * A flat world from a 2D map: solid floor at y = 0, 2-high walls at y = 1 and 2 on each
     * {@code '#'}, and air above. Entities stand with their feet at y = 1.
     */
    public static ArrayBlockView flat(String... rows) {
        ArrayBlockView world = new ArrayBlockView(rows[0].length(), 4, rows.length);
        for (int z = 0; z < rows.length; z++) {
            for (int x = 0; x < rows[z].length(); x++) {
                world.set(x, 0, z, BlockType.SOLID);
                if (rows[z].charAt(x) == '#') {
                    world.set(x, 1, z, BlockType.SOLID);
                    world.set(x, 2, z, BlockType.SOLID);
                }
            }
        }
        return world;
    }

    /** An independent copy: changes to one don't affect the other. */
    public ArrayBlockView copy() {
        ArrayBlockView c = new ArrayBlockView(sizeX, sizeY, sizeZ);
        System.arraycopy(blocks, 0, c.blocks, 0, blocks.length);
        return c;
    }

    /** A copy of the inclusive box, clamped to this world; its (0, 0, 0) is (x0, y0, z0). */
    public ArrayBlockView crop(int x0, int y0, int z0, int x1, int y1, int z1) {
        x0 = Math.max(0, x0);
        y0 = Math.max(0, y0);
        z0 = Math.max(0, z0);
        x1 = Math.min(sizeX - 1, x1);
        y1 = Math.min(sizeY - 1, y1);
        z1 = Math.min(sizeZ - 1, z1);
        ArrayBlockView c = new ArrayBlockView(x1 - x0 + 1, y1 - y0 + 1, z1 - z0 + 1);
        for (int y = y0; y <= y1; y++) {
            for (int z = z0; z <= z1; z++) {
                System.arraycopy(blocks, index(x0, y, z), c.blocks, c.index(0, y - y0, z - z0),
                        x1 - x0 + 1);
            }
        }
        return c;
    }

    public int sizeX() {
        return sizeX;
    }

    public int sizeY() {
        return sizeY;
    }

    public int sizeZ() {
        return sizeZ;
    }

    public boolean inBounds(int x, int y, int z) {
        return x >= 0 && x < sizeX && y >= 0 && y < sizeY && z >= 0 && z < sizeZ;
    }

    @Override
    public BlockType blockAt(int x, int y, int z) {
        return inBounds(x, y, z) ? TYPES[blocks[index(x, y, z)]] : BlockType.VOID;
    }

    public void set(int x, int y, int z, BlockType type) {
        if (!inBounds(x, y, z)) {
            throw new IndexOutOfBoundsException("(" + x + ", " + y + ", " + z + ") is outside the world");
        }
        blocks[index(x, y, z)] = (byte) type.ordinal();
        version++;
        if (logSize == LOG_LIMIT) {
            logSize = 0;
            logBase = version;
        } else {
            if (logSize == logColumns.length) {
                logColumns = Arrays.copyOf(logColumns, logSize * 2);
            }
            logColumns[logSize++] = z * sizeX + x;
        }
    }

    /** Changes whenever a block is set, so anything cached from this world can be dropped. */
    /** A checksum of every cell, to tell whether a file made for this world still fits it. */
    public long checksum() {
        java.util.zip.CRC32C crc = new java.util.zip.CRC32C();
        crc.update(blocks);
        return ((long) blocks.length << 32) ^ crc.getValue();
    }

    public int version() {
        return version;
    }

    /**
     * The columns (as {@code z * sizeX + x}, each once) where blocks were set since the world
     * was at {@code sinceVersion}, or null when that's no longer known (too many changes since,
     * or a version from before).
     */
    public int[] changedColumnsSince(int sinceVersion) {
        if (sinceVersion < logBase || sinceVersion > version) {
            return null;
        }
        return Arrays.stream(logColumns, sinceVersion - logBase, logSize).distinct().toArray();
    }

    /** Sets every block in the inclusive box. */
    public void fill(int x0, int y0, int z0, int x1, int y1, int z1, BlockType type) {
        for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) {
            for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++) {
                for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) {
                    set(x, y, z, type);
                }
            }
        }
    }

    private int index(int x, int y, int z) {
        return (int) (((long) y * sizeZ + z) * sizeX + x);
    }
}
