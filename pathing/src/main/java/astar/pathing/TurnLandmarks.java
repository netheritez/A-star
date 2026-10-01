package astar.pathing;

import astar.core.Heuristic;
import astar.core.Pos;
import astar.core.TurnPenalty;
import java.util.Arrays;

/**
 * Landmark bounds that count turns, for searches that keep a node per heading
 * ({@link astar.core.TurningCostModel#exact exact turn costs}). {@link Landmarks} leave turns
 * out, so under a turn cost they fall short of the real cost by every turn still to come, and
 * the search looks at many more nodes. These work over the move graph's states instead: a
 * cell together with the heading it was entered by, and every move priced with the turn it
 * takes. The same two bounds then hold over states, turns included:
 *
 * <pre>
 *   d(s, t) >= d(L, t) - d(L, s)       d(s, t) >= d(s, L) - d(t, L)
 * </pre>
 *
 * with d(L, t) the cheapest over the headings the goal can be reached by, and d(t, L) the
 * dearest. Each is consistent over states, so their maximum with a consistent cell heuristic
 * is too, and an exact search returns the same cost with fewer nodes.
 *
 * <p>A state per way into each cell makes the tables several times bigger than per cell, so
 * there are fewer of them: 8 bytes per state per table, two tables per landmark.
 */
public final class TurnLandmarks {
    private final NavGraph graph;
    private final double perTurn;
    private final int[] headMask; // per cell: the heading codes it can be entered by
    private final int[] stateStart; // per cell: its first state
    private final int[] stateCell;
    private final byte[] stateHead;
    private final long[] landmarks;
    private final double[][] from; // from[l][s]: cheapest from landmark l (any first heading) to s
    private final double[][] to; // to[l][s]: cheapest from state s to landmark l
    private final double buildMs;

    private TurnLandmarks(NavGraph graph, double perTurn, int[] headMask, int[] stateStart,
            int[] stateCell, byte[] stateHead, long[] landmarks, double[][] from, double[][] to,
            double buildMs) {
        this.graph = graph;
        this.perTurn = perTurn;
        this.headMask = headMask;
        this.stateStart = stateStart;
        this.stateCell = stateCell;
        this.stateHead = stateHead;
        this.landmarks = landmarks;
        this.from = from;
        this.to = to;
        this.buildMs = buildMs;
    }

    /**
     * Works out the tables for these landmark cells (packed; cells outside the graph are
     * skipped) over the graph's moves, with {@code perTurn} charged per 45 degrees as
     * {@link TurnPenalty} does.
     */
    public static TurnLandmarks build(NavGraph g, double perTurn, long[] landmarkCells) {
        long t0 = System.nanoTime();
        int n = g.cells();
        long[] pos = g.positions();
        int[] out = g.moveStart();
        int[] moveTo = g.moveTo();
        int m = moveTo.length;

        // Each move's heading, and the headings each cell is entered by.
        byte[] moveHead = new byte[m];
        int[] mask = new int[n];
        for (int a = 0; a < n; a++) {
            for (int e = out[a]; e < out[a + 1]; e++) {
                int b = moveTo[e];
                int h = TurnPenalty.heading(Pos.x(pos[b]) - Pos.x(pos[a]),
                        Pos.z(pos[b]) - Pos.z(pos[a]));
                moveHead[e] = (byte) h;
                mask[b] |= 1 << h;
            }
        }
        int[] start = new int[n + 1];
        for (int v = 0; v < n; v++) {
            start[v + 1] = start[v] + Integer.bitCount(mask[v]);
        }
        int states = start[n];
        int[] cell = new int[states];
        byte[] head = new byte[states];
        for (int v = 0; v < n; v++) {
            int s = start[v];
            for (int bits = mask[v]; bits != 0; bits &= bits - 1) {
                cell[s] = v;
                head[s++] = (byte) Integer.numberOfTrailingZeros(bits);
            }
        }
        int[] moveState = new int[m];
        for (int e = 0; e < m; e++) {
            moveState[e] = state(start, mask, moveTo[e], moveHead[e]);
        }
        double[][] turn = new double[26][26];
        for (int a = 1; a < 26; a++) {
            for (int b = 1; b < 26; b++) {
                turn[a][b] = perTurn * TurnPenalty.turn(TurnPenalty.headingX(a),
                        TurnPenalty.headingZ(a), TurnPenalty.headingX(b), TurnPenalty.headingZ(b));
            }
        }

        long[] kept = Arrays.stream(landmarkCells).filter(g::covers).toArray();
        double[][] from = new double[kept.length][];
        double[][] to = new double[kept.length][];
        NavGraph.Reversed rev = g.reversed();
        java.util.stream.IntStream.range(0, kept.length).parallel().forEach(l -> {
            int lc = g.cell(kept[l]);
            from[l] = forward(g, lc, states, cell, head, moveHead, moveState, turn);
            to[l] = backward(g, rev, lc, states, start, mask, cell, head, moveHead, turn);
        });
        double ms = (System.nanoTime() - t0) / 1e6;
        return new TurnLandmarks(g, perTurn, mask, start, cell, head, kept, from, to, ms);
    }

    /** The state for entering cell v with heading code h, or -1 if it can't be. */
    private static int state(int[] start, int[] mask, int v, int h) {
        int bits = mask[v];
        if ((bits & (1 << h)) == 0) {
            return -1;
        }
        return start[v] + Integer.bitCount(bits & ((1 << h) - 1));
    }

    /** Cheapest costs from cell lc, with the first move's turn free, to every state. */
    private static double[] forward(NavGraph g, int lc, int states, int[] cell, byte[] head,
            byte[] moveHead, int[] moveState, double[][] turn) {
        int[] out = g.moveStart();
        double[] cost = g.moveCost();
        double[] dist = new double[states];
        Arrays.fill(dist, Double.POSITIVE_INFINITY);
        Landmarks.Heap heap = new Landmarks.Heap(states);
        for (int e = out[lc]; e < out[lc + 1]; e++) {
            int s = moveState[e];
            if (cost[e] < dist[s]) {
                dist[s] = cost[e];
                heap.push(s, cost[e]);
            }
        }
        while (heap.size > 0) {
            double d = heap.topKey();
            int s = heap.pop();
            if (d > dist[s]) {
                continue;
            }
            int v = cell[s];
            double[] turnFrom = turn[head[s]];
            for (int e = out[v]; e < out[v + 1]; e++) {
                int w = moveState[e];
                double nd = d + cost[e] + turnFrom[moveHead[e]];
                if (nd < dist[w]) {
                    dist[w] = nd;
                    heap.push(w, nd);
                }
            }
        }
        return dist;
    }

    /** Cheapest costs from every state to cell lc (arriving any way). */
    private static double[] backward(NavGraph g, NavGraph.Reversed rev, int lc, int states,
            int[] start, int[] mask, int[] cell, byte[] head, byte[] moveHead,
            double[][] turn) {
        double[] dist = new double[states];
        Arrays.fill(dist, Double.POSITIVE_INFINITY);
        Landmarks.Heap heap = new Landmarks.Heap(states);
        for (int s = start[lc]; s < start[lc + 1]; s++) {
            dist[s] = 0;
            heap.push(s, 0);
        }
        while (heap.size > 0) {
            double d = heap.topKey();
            int s = heap.pop();
            if (d > dist[s]) {
                continue;
            }
            int w = cell[s];
            int hw = head[s];
            // Every move into w with this heading, from every state of the cell it leaves.
            for (int i = rev.start[w]; i < rev.start[w + 1]; i++) {
                int e = rev.move[i];
                if (moveHead[e] != hw) {
                    continue;
                }
                int v = rev.from[i];
                double c = d + rev.cost[i];
                for (int sv = start[v]; sv < start[v + 1]; sv++) {
                    double nd = c + turn[head[sv]][hw];
                    if (nd < dist[sv]) {
                        dist[sv] = nd;
                        heap.push(sv, nd);
                    }
                }
            }
        }
        return dist;
    }

    /** Whether the world has changed since these were worked out. */
    public boolean stale() {
        return graph.stale();
    }

    public int count() {
        return landmarks.length;
    }

    /** How many states (cell and heading into it) the tables cover. */
    public int states() {
        return stateCell.length;
    }

    public double perTurn() {
        return perTurn;
    }

    public double buildMs() {
        return buildMs;
    }

    /** About how much memory the tables take, in bytes. */
    public long bytes() {
        return stateCell.length * (5L + 16L * landmarks.length) + headMask.length * 8L;
    }

    /**
     * The bounds for nodes with a heading, on top of {@code cells} (a heuristic for positions
     * alone, such as {@link Landmarks#heuristic}), which also answers for nodes without one and
     * after the world changes. Only valid for searches charging the same turn cost.
     */
    public Heuristic heuristic(Heuristic cells) {
        return new Alt(cells);
    }

    private final class Alt implements Heuristic {
        private final Heuristic cells;
        private long goal = Long.MIN_VALUE;
        private final double[] goalFrom = new double[landmarks.length]; // min d(L, t)
        private final double[] goalTo = new double[landmarks.length]; // max d(t, L)
        private boolean goalCovered;

        Alt(Heuristic cells) {
            this.cells = cells;
        }

        @Override
        public double estimate(int dx, int dy, int dz) {
            return cells.estimate(dx, dy, dz);
        }

        @Override
        public void prepare(long start, long goal) {
            cells.prepare(start, goal);
            setGoal(goal);
        }

        @Override
        public double between(long a, long b) {
            return cells.between(a, b);
        }

        @Override
        public double between(long a, int heading, long b) {
            double h = cells.between(a, heading, b);
            if (graph.stale()) {
                return h;
            }
            if (b != goal) {
                setGoal(b);
            }
            if (!goalCovered) {
                return h;
            }
            int v = graph.cell(a);
            if (v < 0) {
                return h;
            }
            int s = state(stateStart, headMask, v, heading);
            if (s < 0) {
                return h; // the start, before any move: no heading to go on
            }
            for (int l = 0; l < landmarks.length; l++) {
                double fs = from[l][s];
                if (fs < Double.POSITIVE_INFINITY && goalFrom[l] < Double.POSITIVE_INFINITY) {
                    h = Math.max(h, goalFrom[l] - fs);
                }
                double ts = to[l][s];
                if (ts < Double.POSITIVE_INFINITY && goalTo[l] < Double.POSITIVE_INFINITY) {
                    h = Math.max(h, ts - goalTo[l]);
                }
            }
            return h;
        }

        private void setGoal(long b) {
            goal = b;
            int t = graph.cell(b);
            goalCovered = t >= 0 && stateStart[t] < stateStart[t + 1];
            if (!goalCovered) {
                return;
            }
            for (int l = 0; l < landmarks.length; l++) {
                double lo = Double.POSITIVE_INFINITY;
                double hi = 0;
                for (int s = stateStart[t]; s < stateStart[t + 1]; s++) {
                    lo = Math.min(lo, from[l][s]);
                    hi = Math.max(hi, to[l][s]);
                }
                goalFrom[l] = lo;
                goalTo[l] = hi;
            }
        }
    }
}
