package astar.pathing;

import astar.core.CostModel;
import astar.core.MoveGraph;
import astar.core.MoveSource;
import astar.core.MoveType;
import astar.core.Pos;
import java.util.Arrays;
import java.util.stream.IntStream;

/**
 * Every move among the cells reachable from a seed, worked out once and priced: the
 * {@link MoveGraph} a {@link WorldPathfinder} searches instead of working out each node's moves
 * as it goes. A search from any cell it covers only ever reaches cells it covers.
 *
 * <p>The costs are the pathfinder's own, terrain included, without any turn cost (searches add
 * that as they go). Built for one world version: after an edit it is {@link #stale} and is
 * built again.
 *
 * <p>Cells are numbered in the order the flood meets them, so neighbours mostly have nearby
 * ids. The flood works out each ring's moves on all cores. Once built it is read-only, so any
 * number of searches can share it.
 */
public final class NavGraph implements MoveGraph {
    private static final MoveType[] TYPES = MoveType.values();
    /** Rings smaller than this are worked out on one thread. */
    private static final int PARALLEL_MIN = 256;

    private final WorldPathfinder finder;
    private final int version;
    private final LongIntMap index;
    private final long[] positions;
    private final int[] moveStart;
    private final int[] moveTo;
    private final double[] moveCost;
    private final byte[] moveType;
    private final double buildMs;
    private final double fullBuildMs;
    private volatile Reversed reversed;
    private volatile byte[][] moveHeading; // worked out when first asked; {null} if none

    private NavGraph(WorldPathfinder finder, int version, LongIntMap index, long[] positions,
            int[] moveStart, int[] moveTo, double[] moveCost, byte[] moveType, double buildMs,
            double fullBuildMs) {
        this.finder = finder;
        this.version = version;
        this.index = index;
        this.positions = positions;
        this.moveStart = moveStart;
        this.moveTo = moveTo;
        this.moveCost = moveCost;
        this.moveType = moveType;
        this.buildMs = buildMs;
        this.fullBuildMs = fullBuildMs;
    }

    /**
     * A graph read back from a file ({@link NavCache}), for the finder's world as it is now;
     * {@code index} is {@link #indexOf}{@code (positions)}, made while the file was read.
     */
    static NavGraph restored(WorldPathfinder finder, LongIntMap index, long[] positions,
            int[] moveStart, int[] moveTo, double[] moveCost, byte[] moveType) {
        return new NavGraph(finder, finder.worldVersion(), index, positions, moveStart, moveTo,
                moveCost, moveType, 0, 0);
    }

    /** Each position's cell number: its place in {@code positions}. */
    static LongIntMap indexOf(long[] positions) {
        LongIntMap index = new LongIntMap(positions.length);
        for (int i = 0; i < positions.length; i++) {
            index.put(positions[i], i);
        }
        return index;
    }

    /** Floods the moves out from {@code seed} (packed) and prices them with the finder's costs. */
    public static NavGraph build(WorldPathfinder finder, long seed) {
        long t0 = System.nanoTime();
        int version = finder.worldVersion();
        MoveSource moves = finder.moves() instanceof MoveCache c ? c.source() : finder.moves();
        CostModel costs = finder.costs();
        LongIntMap index = new LongIntMap(1024);
        long[] cells = new long[1024];
        int count = 0;
        int[] start = new int[1025];
        int[] to = new int[4096];
        double[] cost = new double[4096];
        byte[] type = new byte[4096];
        int m = 0;

        index.put(seed, 0);
        cells[count++] = seed;
        int ring = 0;
        while (ring < count) {
            int ringEnd = count;
            // Each cell's moves, worked out (on all cores for a big ring), then numbered in order.
            Found[] found = new Found[ringEnd - ring];
            long[] ringCells = cells;
            int first = ring;
            IntStream work = IntStream.range(0, found.length);
            if (found.length >= PARALLEL_MIN) {
                work = work.parallel();
            }
            work.forEach(i -> found[i] = Found.of(moves, costs, ringCells[first + i]));
            for (int i = 0; i < found.length; i++) {
                Found f = found[i];
                start[ring + i] = m;
                if (m + f.count > to.length) {
                    int n = Math.max(to.length * 2, m + f.count);
                    to = Arrays.copyOf(to, n);
                    cost = Arrays.copyOf(cost, n);
                    type = Arrays.copyOf(type, n);
                }
                for (int k = 0; k < f.count; k++) {
                    long b = f.to[k];
                    int id = index.get(b, -1);
                    if (id < 0) {
                        if (count == cells.length) {
                            cells = Arrays.copyOf(cells, count * 2);
                            start = Arrays.copyOf(start, count * 2 + 1);
                        }
                        id = count;
                        index.put(b, count);
                        cells[count++] = b;
                    }
                    to[m] = id;
                    cost[m] = f.cost[k];
                    type[m++] = f.type[k];
                }
            }
            ring = ringEnd;
        }
        start[count] = m;
        double ms = (System.nanoTime() - t0) / 1e6;
        return new NavGraph(finder, version, index, Arrays.copyOf(cells, count),
                Arrays.copyOf(start, count + 1), Arrays.copyOf(to, m), Arrays.copyOf(cost, m),
                Arrays.copyOf(type, m), ms, ms);
    }

    /**
     * How far, in columns, a block change can reach: a cell's moves only depend on blocks
     * within this many columns of its own (the longest move is two columns, plus the body's
     * width and the corner checks), and so does what they cost. With a wall cost, what a move
     * costs reaches one column further ({@link #REACH_KEEPING_ROOM}).
     */
    static final int REACH = 3;
    /** The same with a wall cost: the room where a move ends depends on blocks two columns on. */
    static final int REACH_KEEPING_ROOM = 4;

    /** Patches no more than this share of the cells; beyond it, building afresh is as quick. */
    private static final double PATCH_LIMIT = 0.25;

    /**
     * This graph brought up to date after edits, if the world can say where they were: only the
     * cells near the changed columns get their moves worked out again, and cells newly reachable
     * from them are added. Cells keep their ids; ones nothing leads to any more stay, with
     * whatever moves they still have, so a search never reaches them.
     *
     * @return the patched graph, this one if nothing changed, or null when the changes are
     *     unknown or too many (build afresh then)
     */
    public NavGraph patch() {
        int now = finder.worldVersion();
        if (now == version) {
            return this;
        }
        if (!(finder.world() instanceof ArrayBlockView world)) {
            return null;
        }
        int[] changed = world.changedColumnsSince(version);
        if (changed == null) {
            return null;
        }
        long t0 = System.nanoTime();
        int sizeX = world.sizeX();
        int sizeZ = world.sizeZ();
        // The columns whose cells' moves may have changed.
        boolean[] dirty = new boolean[sizeX * sizeZ];
        int reach = finder.keepsRoom() ? REACH_KEEPING_ROOM : REACH;
        for (int c : changed) {
            int cx = c % sizeX;
            int cz = c / sizeX;
            for (int x = Math.max(0, cx - reach); x <= Math.min(sizeX - 1, cx + reach); x++) {
                for (int z = Math.max(0, cz - reach); z <= Math.min(sizeZ - 1, cz + reach); z++) {
                    dirty[z * sizeX + x] = true;
                }
            }
        }
        int n = positions.length;
        int[] redo = new int[16];
        int redoCount = 0;
        for (int v = 0; v < n; v++) {
            long p = positions[v];
            int x = Pos.x(p);
            int z = Pos.z(p);
            if (x >= 0 && x < sizeX && z >= 0 && z < sizeZ && dirty[z * sizeX + x]) {
                if (redoCount == redo.length) {
                    redo = Arrays.copyOf(redo, redoCount * 2);
                }
                redo[redoCount++] = v;
            }
        }
        if (redoCount > PATCH_LIMIT * n) {
            return null;
        }

        MoveSource moves = finder.moves() instanceof MoveCache c ? c.source() : finder.moves();
        CostModel costs = finder.costs();
        MoveValidator validator = finder.validator();
        LongIntMap index = this.index.copy();
        long[] cells = Arrays.copyOf(positions, n + 64);
        int count = n;
        // New move lists: the dirty cells', then any new cells', found as the flood goes.
        Found[] fresh = new Found[n + 64];
        int[] queue = Arrays.copyOf(redo, redoCount);
        int queued = redoCount;
        for (int i = 0; i < queued; i++) {
            int v = queue[i];
            long p = cells[v];
            Found f = validator.canStand(Pos.x(p), Pos.y(p), Pos.z(p))
                    ? Found.of(moves, costs, p) : new Found(costs, p);
            if (v >= fresh.length) {
                fresh = Arrays.copyOf(fresh, Math.max(fresh.length * 2, v + 1));
            }
            fresh[v] = f;
            for (int k = 0; k < f.count; k++) {
                long b = f.to[k];
                if (index.get(b, -1) < 0) {
                    if (count == cells.length) {
                        cells = Arrays.copyOf(cells, count * 2);
                    }
                    index.put(b, count);
                    cells[count] = b;
                    if (queued == queue.length) {
                        queue = Arrays.copyOf(queue, queued * 2);
                    }
                    queue[queued++] = count++;
                }
            }
        }
        fresh = Arrays.copyOf(fresh, Math.max(fresh.length, count));

        int[] start = new int[count + 1];
        for (int v = 0; v < count; v++) {
            start[v + 1] = start[v] + (fresh[v] != null ? fresh[v].count
                    : moveStart[v + 1] - moveStart[v]);
        }
        int m = start[count];
        int[] to = new int[m];
        double[] cost = new double[m];
        byte[] type = new byte[m];
        for (int v = 0; v < count; v++) {
            Found f = fresh[v];
            int at = start[v];
            if (f == null) {
                int from = moveStart[v];
                int len = moveStart[v + 1] - from;
                System.arraycopy(moveTo, from, to, at, len);
                System.arraycopy(moveCost, from, cost, at, len);
                System.arraycopy(moveType, from, type, at, len);
            } else {
                for (int k = 0; k < f.count; k++) {
                    to[at + k] = index.get(f.to[k], -1);
                    cost[at + k] = f.cost[k];
                    type[at + k] = f.type[k];
                }
            }
        }
        double ms = (System.nanoTime() - t0) / 1e6;
        return new NavGraph(finder, now, index, Arrays.copyOf(cells, count), start, to, cost,
                type, ms, fullBuildMs);
    }

    /** One cell's moves, as its move source lists them. */
    private static final class Found implements MoveSource.MoveSink {
        private final CostModel costs;
        private final long from;
        long[] to = new long[16];
        double[] cost = new double[16];
        byte[] type = new byte[16];
        int count;

        private Found(CostModel costs, long from) {
            this.costs = costs;
            this.from = from;
        }

        static Found of(MoveSource moves, CostModel costs, long from) {
            Found f = new Found(costs, from);
            moves.moves(from, f);
            return f;
        }

        @Override
        public void accept(long b, MoveType t) {
            if (count == to.length) {
                to = Arrays.copyOf(to, count * 2);
                cost = Arrays.copyOf(cost, count * 2);
                type = Arrays.copyOf(type, count * 2);
            }
            to[count] = b;
            cost[count] = costs.cost(from, b, t);
            type[count++] = (byte) t.ordinal();
        }
    }

    /** Whether the world has changed since it was built. */
    public boolean stale() {
        return finder.worldVersion() != version;
    }

    /** Whether the cell (packed) is in the graph: reachable from the seed. */
    public boolean covers(long cell) {
        return index.get(cell, -1) >= 0;
    }

    @Override
    public int cell(long pos) {
        return index.get(pos, -1);
    }

    /** How many cells it covers. */
    public int cells() {
        return positions.length;
    }

    /** How many moves it holds. */
    public int moves() {
        return moveTo.length;
    }

    /** How long making this graph took: building it, or patching it after an edit. */
    public double buildMs() {
        return buildMs;
    }

    /** How long the last full build (flood) of this graph's line took. */
    public double fullBuildMs() {
        return fullBuildMs;
    }

    /** About how much memory it takes, in bytes. */
    public long bytes() {
        return positions.length * (8L + 4 + 24) + moveTo.length * 13L
                + (reversed == null ? 0 : moveTo.length * 16L + positions.length * 4L);
    }

    @Override
    public long[] positions() {
        return positions;
    }

    @Override
    public int[] moveStart() {
        return moveStart;
    }

    @Override
    public int[] moveTo() {
        return moveTo;
    }

    @Override
    public double[] moveCost() {
        return moveCost;
    }

    @Override
    public byte[] moveType() {
        return moveType;
    }

    @Override
    public byte[] moveHeading() {
        byte[][] h = moveHeading;
        if (h == null) {
            h = new byte[][] {MoveGraph.headings(this)};
            moveHeading = h;
        }
        return h[0];
    }

    /** The move type of move e. */
    public MoveType type(int e) {
        return TYPES[moveType[e]];
    }

    /** The pathfinder it was built for. */
    WorldPathfinder finder() {
        return finder;
    }

    /** The same moves listed by the cell they end in, worked out the first time it's asked. */
    Reversed reversed() {
        Reversed r = reversed;
        if (r == null) {
            synchronized (this) {
                r = reversed;
                if (r == null) {
                    r = new Reversed(this);
                    reversed = r;
                }
            }
        }
        return r;
    }

    /**
     * Moves by the cell they end in: for e in {@code start[v]..start[v+1]}, {@code from[e]}
     * is where it starts, {@code move[e]} its id in the forward arrays.
     */
    static final class Reversed {
        final int[] start;
        final int[] from;
        final double[] cost;
        final int[] move;

        private Reversed(NavGraph g) {
            int n = g.positions.length;
            int m = g.moveTo.length;
            start = new int[n + 1];
            for (int e = 0; e < m; e++) {
                start[g.moveTo[e] + 1]++;
            }
            for (int v = 0; v < n; v++) {
                start[v + 1] += start[v];
            }
            int[] fill = Arrays.copyOf(start, n);
            from = new int[m];
            cost = new double[m];
            move = new int[m];
            for (int a = 0; a < n; a++) {
                for (int e = g.moveStart[a]; e < g.moveStart[a + 1]; e++) {
                    int slot = fill[g.moveTo[e]]++;
                    from[slot] = a;
                    cost[slot] = g.moveCost[e];
                    move[slot] = e;
                }
            }
        }
    }
}
