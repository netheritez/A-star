package astar.pathing;

/**
 * Landmark distance tables in floats: half the memory of doubles, and still consistent.
 *
 * <p>Rounding each distance to a float on its own could leave a move's two ends a hair further
 * apart than the move costs, and a landmark bound built on them would then be inconsistent by
 * that hair, enough for A* to settle for a path a hair too dear. So each distance is rounded
 * down, then every move is checked, nearest nodes first, in the same double arithmetic the
 * bounds use, and wherever its far end is more than its near end plus its cost, the far end is
 * lowered to the largest float that fits. A last check of every move confirms it. The bounds are then exactly consistent
 * over the tables' moves, which also makes them admissible.
 */
final class CompactDistances {
    /** The moves out of each node, with their costs: {@code sink.accept(to, cost)}. */
    interface Moves {
        void forEach(int node, Sink sink);
    }

    interface Sink {
        void accept(int to, double cost);
    }

    private CompactDistances() {}

    /**
     * {@code exact} in floats, with {@code f[to] <= f[from] + cost} for every move: for a table
     * of costs from a landmark, pass the forward moves; for costs to one, the reversed moves.
     */
    static float[] compact(double[] exact, Moves moves) {
        Run c = new Run(exact);
        // One pass in order of distance fixes nearly everything: each node's moves are checked
        // once its own value is final (every move into it starts nearer). The queue then only
        // confirms it, or fixes the rare leftover.
        int n = exact.length;
        long[] order = new long[n];
        int reached = 0;
        for (int i = 0; i < n; i++) {
            if (exact[i] < Double.POSITIVE_INFINITY) {
                order[reached++] = (long) Float.floatToIntBits((float) exact[i]) << 32 | i;
            }
        }
        order = java.util.Arrays.copyOf(order, reached);
        java.util.Arrays.sort(order);
        for (long key : order) {
            int u = (int) key;
            c.limitBase = c.f[u];
            c.queueing = false;
            moves.forEach(u, c);
        }
        c.queueing = true;
        while (c.size > 0) {
            int u = c.pop();
            c.limitBase = c.f[u];
            if (c.limitBase < Double.POSITIVE_INFINITY) {
                moves.forEach(u, c);
            }
        }
        return c.f;
    }

    /** One table being fixed: the floats, and a queue of nodes whose moves to check. */
    private static final class Run implements Sink {
        private final float[] f;
        private final int[] queue;
        private final boolean[] queued;
        private int head;
        private int tail;
        private int size;
        private double limitBase; // f[u] of the node whose moves are being checked
        private int fixes;
        private boolean queueing;

        private Run(double[] exact) {
            int n = exact.length;
            f = new float[n];
            queue = new int[Math.max(1, n)];
            queued = new boolean[n];
            for (int i = 0; i < n; i++) {
                f[i] = atMost(exact[i]);
                push(i);
            }
        }

        @Override
        public void accept(int w, double cost) {
            double limit = limitBase + cost;
            if (f[w] > limit) {
                f[w] = atMost(limit);
                fixes++;
                if (queueing && !queued[w]) {
                    push(w);
                }
            }
        }

        private void push(int i) {
            queue[tail] = i;
            tail = tail + 1 == queue.length ? 0 : tail + 1;
            size++;
            queued[i] = true;
        }

        private int pop() {
            int u = queue[head];
            head = head + 1 == queue.length ? 0 : head + 1;
            size--;
            queued[u] = false;
            return u;
        }
    }

    /** The largest float at most x (infinity stays infinity). */
    static float atMost(double x) {
        float g = (float) x;
        return g > x ? Math.nextDown(g) : g;
    }
}
