package astar.pathing;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Keeps a pathfinder's move graph and landmarks in a file, so they needn't be built again the
 * next time the same map is loaded: building them takes seconds on a map the size of the
 * Dwarven Mines, reading them back a fraction of that. A file only fits the world it was made
 * for: {@link #read} checks the world's {@link ArrayBlockView#checksum} and the costs' key.
 */
public final class NavCache {
    private static final long MAGIC = 0x415354_4152_4E4156L; // "ASTARNAV"
    private static final int FORMAT = 1;

    private NavCache() {}

    /** A graph and its landmarks (null if there were none) read back. */
    public record Read(NavGraph graph, Landmarks landmarks) {}

    /**
     * Writes the graph and landmarks.
     *
     * @param world the world they were made for
     * @param key what else they depend on (the costs, the entity), in words
     */
    public static void write(DataOutputStream out, ArrayBlockView world, String key, NavGraph g,
            Landmarks lm) throws IOException {
        if (lm != null && lm.countsTurns()) {
            lm = null; // exact turn tables aren't kept
        }
        out.writeLong(MAGIC);
        out.writeInt(FORMAT);
        out.writeLong(world.checksum());
        out.writeUTF(key);
        long[] positions = g.positions();
        int[] start = g.moveStart();
        int[] to = g.moveTo();
        double[] cost = g.moveCost();
        byte[] type = g.moveType();
        out.writeInt(positions.length);
        out.writeInt(to.length);
        for (long p : positions) {
            out.writeLong(p);
        }
        for (int s : start) {
            out.writeInt(s);
        }
        for (int t : to) {
            out.writeInt(t);
        }
        for (double c : cost) {
            out.writeDouble(c);
        }
        out.write(type);
        out.writeInt(lm == null ? 0 : lm.count());
        if (lm != null) {
            for (long c : lm.cellsPicked()) {
                out.writeLong(c);
            }
            for (float[][] tables : new float[][][] {lm.fromTables(), lm.toTables()}) {
                for (float[] table : tables) {
                    for (float v : table) {
                        out.writeFloat(v);
                    }
                }
            }
        }
    }

    /**
     * Reads a graph and landmarks back for {@code finder}'s world, or null when the file was
     * made for another world or other costs (or another version of this format).
     */
    public static Read read(DataInputStream in, WorldPathfinder finder, String key)
            throws IOException {
        Loaded l = parse(in.readAllBytes(), key);
        return l == null ? null : l.restore(finder);
    }

    /**
     * Reads a file's graph and landmarks, before the world they're for is at hand (so it can go
     * on beside making the world), or null when it was made for other costs or another version
     * of this format. {@link Loaded#restore} then checks them against the world.
     */
    public static Loaded load(Path file, String key) throws IOException {
        byte[] data;
        try (InputStream in = Files.newInputStream(file)) {
            data = in.readAllBytes();
        }
        return parse(data, key);
    }

    /**
     * A graph and landmarks as a file holds them, not yet checked against a world.
     */
    public static final class Loaded {
        private final long checksum;
        private final long[] positions;
        private final LongIntMap index;
        private final int[] start;
        private final int[] to;
        private final double[] cost;
        private final byte[] type;
        private final long[] picked; // null if there are no landmarks
        private final float[][][] tables;

        private Loaded(long checksum, long[] positions, LongIntMap index, int[] start, int[] to,
                double[] cost, byte[] type, long[] picked, float[][][] tables) {
            this.checksum = checksum;
            this.positions = positions;
            this.index = index;
            this.start = start;
            this.to = to;
            this.cost = cost;
            this.type = type;
            this.picked = picked;
            this.tables = tables;
        }

        /**
         * The graph and landmarks for {@code finder} (once: they're not copied), or null if
         * they were made for another world.
         */
        public Read restore(WorldPathfinder finder) {
            if (!(finder.validator().world() instanceof ArrayBlockView world)
                    || checksum != world.checksum()) {
                return null;
            }
            return restoreUnchecked(finder);
        }

        /** {@link #restore} without checking the world (for timing it away from the game). */
        Read restoreUnchecked(WorldPathfinder finder) {
            NavGraph g = NavGraph.restored(finder, index, positions, start, to, cost, type);
            if (picked == null) {
                return new Read(g, null);
            }
            return new Read(g, Landmarks.restored(g, picked, tables[0], tables[1]));
        }
    }

    /**
     * The file's sections, each read in bulk ({@link ByteBuffer} views copy whole arrays at
     * once rather than a number at a time) and the big ones side by side on all cores.
     */
    static Loaded parse(byte[] data, String key) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(data); // big-endian, as DataOutputStream wrote it
        if (b.getLong() != MAGIC || b.getInt() != FORMAT) {
            return null;
        }
        long checksum = b.getLong();
        int keyLength = b.getShort() & 0xFFFF;
        String fileKey = new DataInputStream(new ByteArrayInputStream(data, b.position() - 2,
                keyLength + 2)).readUTF();
        if (!fileKey.equals(key)) {
            return null;
        }
        b.position(b.position() + keyLength);
        int n = b.getInt();
        int m = b.getInt();
        if (n < 0 || m < 0) {
            throw new IOException("bad sizes");
        }
        // Where each section starts; checked against the file's length before anything's read.
        long at = b.position();
        long positionsAt = at;
        long startAt = positionsAt + 8L * n;
        long toAt = startAt + 4L * (n + 1);
        long costAt = toAt + 4L * m;
        long typeAt = costAt + 8L * m;
        long countAt = typeAt + m;
        if (countAt + 4 > data.length) {
            throw new EOFException();
        }
        int count = b.getInt((int) countAt);
        if (count < 0) {
            throw new IOException("bad landmark count");
        }
        long pickedAt = countAt + 4;
        long tablesAt = pickedAt + 8L * count;
        if (tablesAt + 8L * count * n > data.length) {
            throw new EOFException();
        }
        long[] positions = new long[n];
        int[] start = new int[n + 1];
        int[] to = new int[m];
        double[] cost = new double[m];
        byte[] type = new byte[m];
        long[] picked = count == 0 ? null : new long[count];
        float[][][] tables = count == 0 ? null : new float[2][count][];
        java.util.List<Runnable> parts = new java.util.ArrayList<>();
        LongIntMap[] index = new LongIntMap[1];
        parts.add(() -> {
            b.slice((int) positionsAt, 8 * n).asLongBuffer().get(positions);
            index[0] = NavGraph.indexOf(positions);
        });
        parts.add(() -> b.slice((int) startAt, 4 * (n + 1)).asIntBuffer().get(start));
        parts.add(() -> b.slice((int) toAt, 4 * m).asIntBuffer().get(to));
        parts.add(() -> b.slice((int) costAt, 8 * m).asDoubleBuffer().get(cost));
        parts.add(() -> System.arraycopy(data, (int) typeAt, type, 0, m));
        if (count > 0) {
            parts.add(() -> b.slice((int) pickedAt, 8 * count).asLongBuffer().get(picked));
            for (int t = 0; t < 2 * count; t++) {
                int side = t / count;
                int l = t % count;
                long tableAt = tablesAt + 4L * n * t;
                parts.add(() -> {
                    float[] table = new float[n];
                    b.slice((int) tableAt, 4 * n).asFloatBuffer().get(table);
                    tables[side][l] = table;
                });
            }
        }
        parts.parallelStream().forEach(Runnable::run);
        return new Loaded(checksum, positions, index[0], start, to, cost, type, picked, tables);
    }
}
