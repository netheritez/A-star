package astar.pathing;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * The shared parts of the files teleport hops are kept in ({@link WarpHops#write}, {@link
 * TransmitHops#write}): a header naming the world, the walking graph and the settings they were
 * found for, and bulk arrays. Building the hops for a map the size of the Dwarven Mines takes
 * tens of seconds; reading them back well under one.
 */
final class HopFile {
    private static final long MAGIC = 0x415354_4152_484F50L; // "ASTARHOP"
    private static final int FORMAT = 1;

    private HopFile() {}

    static void header(DataOutputStream out, ArrayBlockView world, NavGraph walking, String key)
            throws IOException {
        out.writeLong(MAGIC);
        out.writeInt(FORMAT);
        out.writeLong(world.checksum());
        out.writeLong(fingerprint(walking));
        out.writeUTF(key);
    }

    /** Whether the header read fits {@code world}, {@code walking} and {@code key}. */
    static boolean header(DataInputStream in, ArrayBlockView world, NavGraph walking, String key)
            throws IOException {
        return in.readLong() == MAGIC && in.readInt() == FORMAT
                && in.readLong() == world.checksum() && in.readLong() == fingerprint(walking)
                && in.readUTF().equals(key);
    }

    /** The graph's cells, in order, as one number: hops name cells by their place in it. */
    static long fingerprint(NavGraph g) {
        long h = 0x9E3779B97F4A7C15L;
        for (long p : g.positions()) {
            h = (h ^ p) * 0xBF58476D1CE4E5B9L;
            h ^= h >>> 31;
        }
        return h ^ g.positions().length;
    }

    static void ints(DataOutputStream out, int[] a) throws IOException {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(a.length * 4);
        b.asIntBuffer().put(a);
        out.write(b.array());
    }

    static int[] ints(DataInputStream in, int n) throws IOException {
        byte[] raw = new byte[n * 4];
        in.readFully(raw);
        int[] a = new int[n];
        java.nio.ByteBuffer.wrap(raw).asIntBuffer().get(a);
        return a;
    }

    static void floats(DataOutputStream out, float[] a) throws IOException {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(a.length * 4);
        b.asFloatBuffer().put(a);
        out.write(b.array());
    }

    static float[] floats(DataInputStream in, int n) throws IOException {
        byte[] raw = new byte[n * 4];
        in.readFully(raw);
        float[] a = new float[n];
        java.nio.ByteBuffer.wrap(raw).asFloatBuffer().get(a);
        return a;
    }
}
