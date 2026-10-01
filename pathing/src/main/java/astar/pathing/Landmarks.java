package astar.pathing;

import astar.core.Heuristic;
import astar.core.TurnPenalty;
import java.util.Arrays;

/**
 * A heuristic that knows the map: ALT (A*, landmarks and the triangle inequality). A few cells
 * are picked as landmarks, and the exact cost from each landmark to every cell, and from every
 * cell to each landmark, is worked out once. Then for any cell v and goal t, with d the
 * cheapest cost and L a landmark,
 *
 * <pre>
 *   d(v, t) >= d(L, t) - d(L, v)     (going through v can't beat the best way from L to t)
 *   d(v, t) >= d(v, L) - d(t, L)     (nor can going through t beat the best way from v to L)
 * </pre>
 *
 * so the best of these over the landmarks, and the flat heuristic, never overestimates. Each
 * bound is consistent (a move from v to w changes it by at most the move's cost), so their
 * maximum is too. Unlike a distance from the two ends, the bounds see walls, hills and the
 * long way round.
 *
 * <p>The costs are the pathfinder's own, terrain included, without any turn cost (a turn cost
 * only makes moves dearer, so the bounds stay below the real cost). When the pathfinder charges
 * exact turn costs, the first few landmarks also get {@link TurnLandmarks}, which count turns. Only the cells reachable
 * from the seed are covered; elsewhere, and after the world changes, it falls back to the flat
 * heuristic.
 *
 * <p>Built for one pathfinder, and not thread-safe: the heuristic keeps the last goal's
 * distances.
 */
public final class Landmarks {
    /** How many landmarks each search uses: the ones that say most about its start. */
    static final int ACTIVE = 4;
    /**
     * How many of the landmarks also get tables that count turns, for pathfinders charging
     * exact turn costs ({@link TurnLandmarks}): a few are enough, and each costs several times
     * the memory of a plain one.
     */
    static final int TURN_COUNT = 4;

    private final NavGraph graph;
    private final long[] landmarks;
    // Cheapest costs, in floats (see CompactDistances): from[l][v] from landmark l to cell v,
    // to[l][v] from cell v to landmark l.
    private final float[][] from;
    private final float[][] to;
    private final TurnLandmarks turns; // null unless the pathfinder charges exact turn costs
    private final double buildMs;

    private Landmarks(NavGraph graph, long[] landmarks, float[][] from, float[][] to,
            TurnLandmarks turns, double buildMs) {
        this.graph = graph;
        this.turns = turns;
        this.landmarks = landmarks;
        this.from = from;
        this.to = to;
        this.buildMs = buildMs;
    }

    /** Landmarks read back from a file ({@link NavCache}) over a graph read back with them. */
    static Landmarks restored(NavGraph graph, long[] landmarks, float[][] from, float[][] to) {
        return new Landmarks(graph, landmarks, from, to, null, 0);
    }

    long[] cellsPicked() {
        return landmarks;
    }

    float[][] fromTables() {
        return from;
    }

    float[][] toTables() {
        return to;
    }

    /** Whether they count turns exactly ({@link TurnLandmarks}), which {@link NavCache} can't keep. */
    boolean countsTurns() {
        return turns != null;
    }

    /**
     * Picks {@code count} landmarks among the cells reachable from {@code seed} and works out
     * their costs. Landmarks are spread out: each new one is the cell furthest (by path cost)
     * from the nearest one picked so far, starting from the cell furthest from the seed, so they
     * sit round the edges of the map, where the bounds are tightest.
     */
    public static Landmarks build(WorldPathfinder finder, long seed, int count) {
        if (count < 1) {
            throw new IllegalArgumentException("count must be at least 1");
        }
        long t0 = System.nanoTime();
        NavGraph g = finder.graphCovering(seed);
        double perTurn = finder.costs() instanceof TurnPenalty tp && tp.exact() ? tp.perTurn() : 0;
        return build(g, g.cell(seed), count, perTurn, t0);
    }

    private static Landmarks build(NavGraph g, int seedCell, int count, double perTurn,
            long t0) {
        int n = g.cells();
        long[] picked = new long[count];
        double[][] from = new double[count][];
        double[][] to = new double[count][];
        double[] nearest = new double[n];
        Arrays.fill(nearest, Double.POSITIVE_INFINITY);
        long[] cells = g.positions();
        int next = argMax(dijkstra(g, seedCell, true));
        for (int l = 0; l < count; l++) {
            picked[l] = cells[next];
            from[l] = dijkstra(g, next, true);
            to[l] = dijkstra(g, next, false);
            for (int v = 0; v < n; v++) {
                // Round trips, so a cell only a one-way drop leads to still counts as far.
                double d = from[l][v] + to[l][v];
                if (d < nearest[v]) {
                    nearest[v] = d;
                }
            }
            next = argMax(nearest);
        }
        float[][] compactFrom = new float[count][];
        float[][] compactTo = new float[count][];
        java.util.stream.IntStream.range(0, count).parallel().forEach(l -> {
            compactFrom[l] = compact(g, from[l], true);
            compactTo[l] = compact(g, to[l], false);
        });
        return finish(g, picked, compactFrom, compactTo, perTurn, t0);
    }

    /**
     * The same landmarks worked out again over a new graph of the same world (after an edit):
     * no picking, and every table at once on all cores. Landmarks the graph no longer covers
     * are dropped; with none left, it picks afresh.
     */
    public Landmarks rebuild(NavGraph g) {
        long t0 = System.nanoTime();
        long[] kept = Arrays.stream(landmarks).filter(g::covers).toArray();
        double perTurn = turns == null ? 0 : turns.perTurn();
        if (kept.length == 0) {
            return build(g, 0, landmarks.length, perTurn, t0);
        }
        int count = kept.length;
        float[][] f = new float[count][];
        float[][] t = new float[count][];
        java.util.stream.IntStream.range(0, 2 * count).parallel().forEach(i -> {
            int l = i / 2;
            int cell = g.cell(kept[l]);
            if (i % 2 == 0) {
                f[l] = compact(g, dijkstra(g, cell, true), true);
            } else {
                t[l] = compact(g, dijkstra(g, cell, false), false);
            }
        });
        return finish(g, kept, f, t, perTurn, t0);
    }

    private static Landmarks finish(NavGraph g, long[] picked, float[][] from, float[][] to,
            double perTurn, long t0) {
        // With exact turn costs, the first few (the most spread out) also count turns.
        TurnLandmarks turns = perTurn > 0 ? TurnLandmarks.build(g, perTurn,
                Arrays.copyOf(picked, Math.min(picked.length, TURN_COUNT))) : null;
        double ms = (System.nanoTime() - t0) / 1e6;
        return new Landmarks(g, picked, from, to, turns, ms);
    }

    /** A table in floats, consistent over the graph's moves (reversed for costs to a cell). */
    private static float[] compact(NavGraph g, double[] exact, boolean forwards) {
        if (forwards) {
            int[] start = g.moveStart();
            int[] ends = g.moveTo();
            double[] cost = g.moveCost();
            return CompactDistances.compact(exact, (u, sink) -> {
                for (int e = start[u]; e < start[u + 1]; e++) {
                    sink.accept(ends[e], cost[e]);
                }
            });
        }
        NavGraph.Reversed r = g.reversed();
        return CompactDistances.compact(exact, (u, sink) -> {
            for (int e = r.start[u]; e < r.start[u + 1]; e++) {
                sink.accept(r.from[e], r.cost[e]);
            }
        });
    }

    /** The index of the largest finite value. */
    private static int argMax(double[] values) {
        int best = 0;
        double top = -1;
        for (int i = 0; i < values.length; i++) {
            if (values[i] > top && values[i] < Double.POSITIVE_INFINITY) {
                top = values[i];
                best = i;
            }
        }
        return best;
    }

    /** Whether the world has changed since these were worked out. */
    public boolean stale() {
        return graph.stale();
    }

    /** Whether the cell (packed) is one they cover: reachable from the seed. */
    public boolean covers(long cell) {
        return graph.covers(cell);
    }

    /** How many cells the landmarks cover. */
    public int cells() {
        return graph.cells();
    }

    /** The move graph they were worked out over. */
    public NavGraph graph() {
        return graph;
    }

    public int count() {
        return landmarks.length;
    }

    /** The landmarks, packed. */
    public long[] landmarks() {
        return landmarks.clone();
    }

    /** The tables that count turns, or null (see {@link #build}). */
    public TurnLandmarks turns() {
        return turns;
    }

    public double buildMs() {
        return buildMs;
    }

    /** About how much memory the distance tables take, in bytes. */
    public long bytes() {
        return (long) graph.cells() * 8L * landmarks.length
                + (turns == null ? 0 : turns.bytes());
    }

    /**
     * The landmark bounds, with {@code flat} as a floor (and the whole estimate where the cells
     * aren't covered or the world has changed since they were built).
     */
    public Heuristic heuristic(Heuristic flat) {
        Heuristic cells = new Alt(flat);
        return turns == null ? cells : turns.heuristic(cells);
    }

    private final class Alt implements Heuristic {
        private final Heuristic flat;
        private long goal = Long.MIN_VALUE;
        private double[] goalFrom; // d(L, goal); NaN when unreachable
        private double[] goalTo; // d(goal, L)
        private boolean goalCovered;
        private final int[] active = new int[ACTIVE];
        private int activeCount;

        Alt(Heuristic flat) {
            this.flat = flat;
        }

        @Override
        public double estimate(int dx, int dy, int dz) {
            return flat.estimate(dx, dy, dz);
        }

        @Override
        public void prepare(long start, long goal) {
            flat.prepare(start, goal);
            setGoal(start, goal);
        }

        @Override
        public double between(long a, long b) {
            return between(a, b, -1);
        }

        @Override
        public double between(astar.core.MoveGraph g, int cell, long a, long b) {
            // The search's graph is the landmarks' own (not one patched since): same cells.
            return between(a, b, g == graph ? cell : -1);
        }

        /** The estimate from a (the graph's cell {@code v}, or -1 to look it up) to b. */
        private double between(long a, long b, int v) {
            double h = flat.between(a, b);
            if (graph.stale()) {
                return h;
            }
            if (b != goal) {
                setGoal(a, b);
            }
            if (!goalCovered) {
                return h;
            }
            if (v < 0) {
                v = graph.cell(a);
            }
            if (v < 0) {
                return h;
            }
            for (int i = 0; i < activeCount; i++) {
                int l = active[i];
                double fv = from[l][v];
                if (fv < Double.POSITIVE_INFINITY && goalFrom[l] < Double.POSITIVE_INFINITY) {
                    h = Math.max(h, goalFrom[l] - fv);
                }
                double tv = to[l][v];
                if (tv < Double.POSITIVE_INFINITY && goalTo[l] < Double.POSITIVE_INFINITY) {
                    h = Math.max(h, tv - goalTo[l]);
                }
            }
            return h;
        }

        /**
         * A new goal. A search asks first about its start, so the landmarks are ranked by how
         * much they say about the start, and only the best few are used for the whole search:
         * the rest rarely win, and each costs a look-up at every node. A fixed set for the
         * whole search keeps the estimate consistent.
         */
        private void setGoal(long a, long b) {
            goal = b;
            int t = graph.cell(b);
            goalCovered = t >= 0;
            if (!goalCovered) {
                return;
            }
            int k = landmarks.length;
            goalFrom = new double[k];
            goalTo = new double[k];
            for (int l = 0; l < k; l++) {
                goalFrom[l] = from[l][t];
                goalTo[l] = to[l][t];
            }
            int v = graph.cell(a);
            double[] score = new double[k];
            Integer[] order = new Integer[k];
            for (int l = 0; l < k; l++) {
                order[l] = l;
                score[l] = v < 0 ? 0 : Math.max(bound(goalFrom[l] - from[l][v]),
                        bound(to[l][v] - goalTo[l]));
            }
            Arrays.sort(order, (x, y) -> Double.compare(score[y], score[x]));
            activeCount = Math.min(k, ACTIVE);
            for (int i = 0; i < activeCount; i++) {
                active[i] = order[i];
            }
        }

        /** A bound, or 0 when it involves a cell the landmark can't reach or be reached from. */
        private static double bound(double d) {
            return Double.isNaN(d) || Double.isInfinite(d) ? 0 : d;
        }
    }

    /** Cheapest costs from cell s to every cell of the graph (forwards), or from every cell to s. */
    private static double[] dijkstra(NavGraph g, int s, boolean forwards) {
        int n = g.cells();
        double[] dist = new double[n];
        Arrays.fill(dist, Double.POSITIVE_INFINITY);
        dist[s] = 0;
        NavGraph.Reversed r = forwards ? null : g.reversed();
        int[] starts = forwards ? g.moveStart() : r.start;
        int[] ends = forwards ? g.moveTo() : r.from;
        double[] prices = forwards ? g.moveCost() : r.cost;
        Heap heap = new Heap(n);
        heap.push(s, 0);
        while (heap.size > 0) {
            double d = heap.topKey();
            int v = heap.pop();
            if (d > dist[v]) {
                continue;
            }
            for (int e = starts[v]; e < starts[v + 1]; e++) {
                int w = ends[e];
                double nd = d + prices[e];
                if (nd < dist[w]) {
                    dist[w] = nd;
                    heap.push(w, nd);
                }
            }
        }
        return dist;
    }

    /** A binary min-heap of (key, node) with lazy deletion. */
    static final class Heap {
        double[] keys;
        int[] nodes;
        int size;

        Heap(int capacity) {
            keys = new double[Math.max(16, capacity)];
            nodes = new int[keys.length];
        }

        void push(int node, double key) {
            if (size == keys.length) {
                keys = Arrays.copyOf(keys, size * 2);
                nodes = Arrays.copyOf(nodes, size * 2);
            }
            int i = size++;
            while (i > 0) {
                int p = (i - 1) >>> 1;
                if (keys[p] <= key) {
                    break;
                }
                keys[i] = keys[p];
                nodes[i] = nodes[p];
                i = p;
            }
            keys[i] = key;
            nodes[i] = node;
        }

        double topKey() {
            return keys[0];
        }

        int pop() {
            int top = nodes[0];
            double key = keys[--size];
            int node = nodes[size];
            int i = 0;
            while (true) {
                int c = 2 * i + 1;
                if (c >= size) {
                    break;
                }
                if (c + 1 < size && keys[c + 1] < keys[c]) {
                    c++;
                }
                if (keys[c] >= key) {
                    break;
                }
                keys[i] = keys[c];
                nodes[i] = nodes[c];
                i = c;
            }
            keys[i] = key;
            nodes[i] = node;
            return top;
        }
    }
}
