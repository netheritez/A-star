package astar.core;

import java.util.Arrays;

/**
 * All of one search's node state in flat arrays, with no object per node: an open-addressing
 * hash table from (packed position, heading) to node id, and per-node arrays indexed by id.
 * This keeps large searches (100,000+ nodes) fast and nearly garbage-free.
 *
 * <p>The heading is the step that led into the node, as a small code (see {@link #heading}),
 * so a search that charges for turns can keep one node per way into a cell. Searches that
 * don't only use heading 0, and then a node is just its position.
 */
final class NodeTable {
    static final int NONE = -1;
    private static final MoveType[] MOVES = MoveType.values();

    // Hash table: key(position, heading) -> id + 1 (0 = empty slot). Kept at most half full.
    private long[] keys;
    private int[] ids;
    private int mask;
    private int shift; // 64 - log2(table size): slot = top bits of the Fibonacci product

    // Per-node state, indexed by id.
    long[] pos;
    byte[] heading; // the step into the node (see heading()), 0 for none
    double[] g;
    double[] h;
    int[] parent;
    byte[] via; // MoveType ordinal + 1, or 0 for the start
    boolean[] closed;
    int[] heapIndex;
    private int size;

    NodeTable(int expectedNodes) {
        int n = Math.max(16, Integer.highestOneBit(Math.max(1, expectedNodes) - 1) << 1);
        pos = new long[n];
        heading = new byte[n];
        g = new double[n];
        h = new double[n];
        parent = new int[n];
        via = new byte[n];
        closed = new boolean[n];
        heapIndex = new int[n];
        keys = new long[2 * n];
        ids = new int[2 * n];
        mask = 2 * n - 1;
        shift = 64 - Integer.numberOfTrailingZeros(2 * n);
    }

    /**
     * A table whose node ids are the cells of a {@link MoveGraph}: node {@code c} is cell
     * {@code c}, every node exists from the start (g = infinity), and there is no hashing. Only
     * for searches with one node per cell. {@code positions} is shared, never written.
     */
    NodeTable(long[] positions) {
        int n = positions.length;
        pos = positions;
        heading = new byte[n];
        g = new double[n];
        Arrays.fill(g, Double.POSITIVE_INFINITY);
        h = new double[n];
        parent = new int[n];
        Arrays.fill(parent, NONE);
        via = new byte[n];
        closed = new boolean[n];
        heapIndex = new int[n];
        Arrays.fill(heapIndex, NONE);
        size = n;
    }

    /**
     * Puts the given nodes of a dense table back to their state before any search (g = infinity,
     * no parent, not open or closed), so the table can serve another search on the same graph
     * without allocating and filling arrays the size of the whole graph again.
     */
    void reset(int[] ids, int count) {
        for (int i = 0; i < count; i++) {
            int id = ids[i];
            g[id] = Double.POSITIVE_INFINITY;
            h[id] = 0;
            parent[id] = NONE;
            via[id] = 0;
            closed[id] = false;
            heapIndex[id] = NONE;
        }
    }

    int size() {
        return size;
    }

    /** The heading code of a step (see {@link TurnPenalty#heading}). */
    static int heading(int dx, int dz) {
        return TurnPenalty.heading(dx, dz);
    }

    static int headingX(int code) {
        return TurnPenalty.headingX(code);
    }

    static int headingZ(int code) {
        return TurnPenalty.headingZ(code);
    }

    /** The node for a position, created (with g = infinity) if it doesn't exist yet. */
    int getOrCreate(long p) {
        return getOrCreate(p, 0);
    }

    /** The node for a position entered with this heading, created if it doesn't exist yet. */
    int getOrCreate(long p, int head) {
        long k = key(p, head);
        int slot = slot(k);
        while (ids[slot] != 0) {
            int id = ids[slot] - 1;
            if (keys[slot] == k && pos[id] == p && heading[id] == head) {
                return id;
            }
            slot = (slot + 1) & mask;
        }
        int id = size++;
        if (id == pos.length) {
            grow();
            return insertNew(p, head, id);
        }
        keys[slot] = k;
        ids[slot] = id + 1;
        init(id, p, head);
        return id;
    }

    /** The hash key: the position itself for heading 0, so plain searches hash as before. */
    private static long key(long p, int head) {
        return p ^ ((long) head * 0x632BE59BD9B4E019L);
    }

    private int insertNew(long p, int head, int id) {
        long k = key(p, head);
        int slot = slot(k);
        while (ids[slot] != 0) {
            slot = (slot + 1) & mask;
        }
        keys[slot] = k;
        ids[slot] = id + 1;
        init(id, p, head);
        return id;
    }

    private void init(int id, long p, int head) {
        pos[id] = p;
        heading[id] = (byte) head;
        g[id] = Double.POSITIVE_INFINITY;
        h[id] = 0;
        parent[id] = NONE;
        via[id] = 0;
        closed[id] = false;
        heapIndex[id] = NONE;
    }

    MoveType via(int id) {
        return via[id] == 0 ? null : MOVES[via[id] - 1];
    }

    void setVia(int id, MoveType type) {
        via[id] = type == null ? 0 : (byte) (type.ordinal() + 1);
    }

    private int slot(long p) {
        // Fibonacci hashing: the top bits of the product depend on every bit of p. (The low bits
        // only depend on p's low bits, which left x, packed at the top, almost out of the hash.)
        return (int) ((p * 0x9E3779B97F4A7C15L) >>> shift);
    }

    private void grow() {
        int n = pos.length * 2;
        pos = Arrays.copyOf(pos, n);
        heading = Arrays.copyOf(heading, n);
        g = Arrays.copyOf(g, n);
        h = Arrays.copyOf(h, n);
        parent = Arrays.copyOf(parent, n);
        via = Arrays.copyOf(via, n);
        closed = Arrays.copyOf(closed, n);
        heapIndex = Arrays.copyOf(heapIndex, n);
        keys = new long[2 * n];
        ids = new int[2 * n];
        mask = 2 * n - 1;
        shift = 64 - Integer.numberOfTrailingZeros(2 * n);
        for (int id = 0; id < size - 1; id++) { // the node being added is inserted by the caller
            long k = key(pos[id], heading[id]);
            int slot = slot(k);
            while (ids[slot] != 0) {
                slot = (slot + 1) & mask;
            }
            keys[slot] = k;
            ids[slot] = id + 1;
        }
    }
}
