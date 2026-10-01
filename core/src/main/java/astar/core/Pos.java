package astar.core;

/**
 * Packs block positions into a single {@code long}, using the same layout as Minecraft's
 * {@code BlockPos.asLong()}: x in the top 26 bits, z in the next 26, y in the low 12.
 *
 * <p>Range: x and z in [-33,554,432, 33,554,431], y in [-2048, 2047].
 */
public final class Pos {
    private static final int X_BITS = 26;
    private static final int Z_BITS = 26;
    private static final int Y_BITS = 12;
    private static final int Z_SHIFT = Y_BITS;
    private static final int X_SHIFT = Y_BITS + Z_BITS;
    private static final long X_MASK = (1L << X_BITS) - 1;
    private static final long Z_MASK = (1L << Z_BITS) - 1;
    private static final long Y_MASK = (1L << Y_BITS) - 1;

    private Pos() {}

    public static long pack(int x, int y, int z) {
        return ((x & X_MASK) << X_SHIFT) | ((z & Z_MASK) << Z_SHIFT) | (y & Y_MASK);
    }

    public static int x(long pos) {
        return (int) (pos >> X_SHIFT);
    }

    public static int y(long pos) {
        return (int) (pos << (64 - Y_BITS) >> (64 - Y_BITS));
    }

    public static int z(long pos) {
        return (int) (pos << (64 - X_SHIFT) >> (64 - Z_BITS));
    }

    public static long offset(long pos, int dx, int dy, int dz) {
        return pack(x(pos) + dx, y(pos) + dy, z(pos) + dz);
    }

    public static BlockPoint toPoint(long pos) {
        return new BlockPoint(x(pos), y(pos), z(pos));
    }

    public static String toString(long pos) {
        return "(" + x(pos) + ", " + y(pos) + ", " + z(pos) + ")";
    }
}
