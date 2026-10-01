package astar.pathing;

import astar.core.CostModel;
import astar.core.MoveSource;
import astar.core.MoveType;
import java.util.Arrays;

/**
 * The cost from each of many cells to one target, all inside the target's cluster: what
 * inserting the goal of a hierarchical query needs, from every portal of the goal's cluster at
 * once. One {@link ClusterDijkstra} per portal would do the same, but a portal that can't reach
 * the goal floods its whole cluster, and a cluster with many portals floods it many times.
 *
 * <p>Instead this runs Dijkstra backwards from the target, over the moves reversed. Moves only
 * say where they go, not where they come from, so it first floods forwards from all the sources
 * together (one pass, however many there are) and keeps every move it meets, sorted by where
 * it ends. Only cells some source reaches can be on a path from a source, so that's every move
 * the backward search could need. Costs depend on direction (a drop is cheaper than the jump
 * back, and terrain is charged where a move ends), so each reversed move is priced as the
 * forward move it stands for. The bounds are {@link ClusterDijkstra}'s: a move counts only if
 * both ends are in the cluster, so the costs are the ones a {@link ClusterDijkstra} from each
 * source would find.
 *
 * <p>Like {@link ClusterDijkstra}, cells are found through arrays indexed by their place in the
 * cluster, stamped by run, and everything else is kept per cell reached, in arrays that are
 * reused (and only grow) from run to run. One instance is not thread-safe.
 */
final class GoalDijkstra {
    private final ClusterLayout layout;
    private final MoveSource moves;
    private final CostModel costs;

    // Per cell of the cluster: which run reached it, and its number in that run.
    private final int[] stamp;
    private final int[] node;
    private int run;
    private int cluster;

    // Per cell reached, by number (in the order the flood found them).
    private long[] pos = new long[256];
    private double[] dist = new double[256];
    private double[] step = new double[256]; // the cost of the first move towards the target
    private int[] next = new int[256]; // the cell that move goes to, or -1 at the target
    private boolean[] source = new boolean[256];
    private int[] inStart = new int[257];
    private int cells;

    // The moves met, then the same moves sorted by the cell they end in.
    private int[] edgeFrom = new int[1024];
    private int[] edgeTo = new int[1024];
    private byte[] edgeType = new byte[1024];
    private int[] incoming = new int[1024];
    private int edges;

    // A binary heap with lazy deletion, as in ClusterDijkstra.
    private double[] heapKey = new double[256];
    private int[] heapNode = new int[256];
    private int heapSize;

    private int target;
    private int flooding;
    private int work;
    private final MoveSource.MoveSink record = this::record;

    GoalDijkstra(ClusterLayout layout, MoveSource moves, CostModel costs) {
        this.layout = layout;
        this.moves = moves;
        this.costs = costs;
        this.stamp = new int[layout.cellsPerCluster()];
        this.node = new int[layout.cellsPerCluster()];
    }

    /**
     * Finds the cost to {@code target} from each of {@code sources} (cells of its cluster, other
     * than the target), staying inside the cluster. Read them with {@link #distance}.
     */
    void run(long target, long[] sources, int count) {
        if (++run == 0) { // wrapped around: clear the stamps once
            Arrays.fill(stamp, 0);
            run = 1;
        }
        cluster = layout.clusterOf(target);
        cells = 0;
        edges = 0;
        work = 0;
        this.target = -1;
        for (int i = 0; i < count; i++) {
            source[number(sources[i])] = true;
        }
        int wanted = cells;

        // 1. Flood forwards from every source at once, keeping each move inside the cluster.
        for (flooding = 0; flooding < cells; flooding++) {
            moves.moves(pos[flooding], record);
        }
        work += cells;
        if (!reached(target)) {
            return; // no source gets there
        }
        this.target = node[layout.localIndex(cluster, target)];

        // 2. Sort the moves by where they end (a counting sort), so each cell lists its way in.
        if (inStart.length < cells + 1) {
            inStart = new int[Math.max(cells + 1, inStart.length * 2)];
        }
        Arrays.fill(inStart, 0, cells + 1, 0);
        for (int e = 0; e < edges; e++) {
            inStart[edgeTo[e] + 1]++;
        }
        for (int c = 0; c < cells; c++) {
            inStart[c + 1] += inStart[c];
        }
        if (incoming.length < edges) {
            incoming = new int[edgeFrom.length];
        }
        for (int e = 0; e < edges; e++) {
            incoming[inStart[edgeTo[e]]++] = e;
        }
        for (int c = cells; c > 0; c--) { // the fill moved each start to the next one's
            inStart[c] = inStart[c - 1];
        }
        inStart[0] = 0;

        // 3. Dijkstra backwards from the target, until every source is settled.
        Arrays.fill(dist, 0, cells, Double.POSITIVE_INFINITY);
        int t = this.target;
        dist[t] = 0;
        next[t] = -1;
        heapSize = 0;
        push(0, t);
        while (heapSize > 0 && wanted > 0) {
            double d = heapKey[0];
            int v = pop();
            if (d > dist[v]) {
                continue; // a stale entry: this cell was reached more cheaply since
            }
            work++;
            if (source[v]) {
                wanted--;
            }
            for (int i = inStart[v]; i < inStart[v + 1]; i++) {
                int e = incoming[i];
                int u = edgeFrom[e];
                double c = costs.cost(pos[u], pos[v], TYPES[edgeType[e]]);
                if (d + c < dist[u]) {
                    dist[u] = d + c;
                    step[u] = c;
                    next[u] = v;
                    push(d + c, u);
                }
            }
        }
    }

    /**
     * The cost from {@code from} to the last run's target, or infinity if it can't get there in
     * the cluster. It adds the moves up from {@code from} onwards, in the order a forward search
     * would, so it's the very number a {@link ClusterDijkstra} gives along the same path.
     */
    double distance(long from) {
        if (target < 0 || !reached(from)) {
            return Double.POSITIVE_INFINITY;
        }
        int u = node[layout.localIndex(cluster, from)];
        if (dist[u] == Double.POSITIVE_INFINITY) {
            return dist[u];
        }
        double d = 0;
        for (; u != target; u = next[u]) {
            d += step[u];
        }
        return d;
    }

    /** Cells the last run expanded, forwards and backwards: its share of the work. */
    int settled() {
        return work;
    }

    private static final MoveType[] TYPES = MoveType.values();

    private boolean reached(long p) {
        return layout.contains(cluster, p) && stamp[layout.localIndex(cluster, p)] == run;
    }

    private void record(long to, MoveType type) {
        if (!layout.contains(cluster, to)) {
            return;
        }
        int v = number(to);
        if (edges == edgeFrom.length) {
            edgeFrom = Arrays.copyOf(edgeFrom, edges * 2);
            edgeTo = Arrays.copyOf(edgeTo, edges * 2);
            edgeType = Arrays.copyOf(edgeType, edges * 2);
        }
        edgeFrom[edges] = flooding;
        edgeTo[edges] = v;
        edgeType[edges++] = (byte) type.ordinal();
    }

    /** The cell's number in this run, numbering it (and queueing it for the flood) if new. */
    private int number(long p) {
        int i = layout.localIndex(cluster, p);
        if (stamp[i] == run) {
            return node[i];
        }
        if (cells == pos.length) {
            int n = cells * 2;
            pos = Arrays.copyOf(pos, n);
            dist = Arrays.copyOf(dist, n);
            step = Arrays.copyOf(step, n);
            next = Arrays.copyOf(next, n);
            source = Arrays.copyOf(source, n);
        }
        stamp[i] = run;
        node[i] = cells;
        pos[cells] = p;
        source[cells] = false;
        return cells++;
    }

    private void push(double key, int v) {
        if (heapSize == heapKey.length) {
            heapKey = Arrays.copyOf(heapKey, heapSize * 2);
            heapNode = Arrays.copyOf(heapNode, heapSize * 2);
        }
        int i = heapSize++;
        while (i > 0) {
            int parent = (i - 1) / 2;
            if (heapKey[parent] <= key) {
                break;
            }
            heapKey[i] = heapKey[parent];
            heapNode[i] = heapNode[parent];
            i = parent;
        }
        heapKey[i] = key;
        heapNode[i] = v;
    }

    private int pop() {
        int top = heapNode[0];
        heapSize--;
        if (heapSize > 0) {
            double key = heapKey[heapSize];
            int v = heapNode[heapSize];
            int i = 0;
            while (true) {
                int child = 2 * i + 1;
                if (child >= heapSize) {
                    break;
                }
                if (child + 1 < heapSize && heapKey[child + 1] < heapKey[child]) {
                    child++;
                }
                if (key <= heapKey[child]) {
                    break;
                }
                heapKey[i] = heapKey[child];
                heapNode[i] = heapNode[child];
                i = child;
            }
            heapKey[i] = key;
            heapNode[i] = v;
        }
        return top;
    }
}
