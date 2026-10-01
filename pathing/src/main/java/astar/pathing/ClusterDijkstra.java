package astar.pathing;

import astar.core.CostModel;
import astar.core.MoveSource;
import astar.core.MoveType;
import astar.core.PathStep;
import astar.core.Pos;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Dijkstra from one cell to every cell of its cluster, never leaving the cluster. Hierarchical
 * search runs it from every portal to price the paths between them, from the start of each
 * query (the goal has its own, backward search: {@link GoalDijkstra}), and to turn each hop
 * inside a cluster back into real steps.
 *
 * <p>All state lives in arrays indexed by the cell's place in the cluster, reused from run to
 * run: a run only resets what it touched (a stamp per cell says which run wrote it). One
 * instance is not thread-safe.
 */
public final class ClusterDijkstra {
    /** Pass as {@code stopAt} to settle the whole cluster. */
    public static final long NO_TARGET = Long.MIN_VALUE;

    private final ClusterLayout layout;
    private final MoveSource moves;
    private final CostModel costs;

    private final double[] dist;
    private final long[] parent;
    private final byte[] via;
    private final int[] stamp;
    private int run;

    // A binary heap with lazy deletion: a cell may be in it more than once, and entries with a
    // key above the cell's distance are skipped when they come out.
    private double[] heapKey = new double[256];
    private long[] heapPos = new long[256];
    private int heapSize;

    private int cluster;
    private long current;
    private double currentDist;
    private int pops;
    private final MoveSource.MoveSink relax = this::relax;

    public ClusterDijkstra(ClusterLayout layout, MoveSource moves, CostModel costs) {
        this.layout = layout;
        this.moves = moves;
        this.costs = costs;
        this.dist = new double[layout.cellsPerCluster()];
        this.parent = new long[layout.cellsPerCluster()];
        this.via = new byte[layout.cellsPerCluster()];
        this.stamp = new int[layout.cellsPerCluster()];
    }

    /**
     * Runs from {@code source}, inside its cluster, until every reachable cell is settled or
     * {@code stopAt} is.
     *
     * @return the cost to {@code stopAt}, or infinity if it wasn't reached (or not given)
     */
    public double run(long source, long stopAt) {
        if (++run == 0) { // wrapped around: clear the stamps once
            Arrays.fill(stamp, 0);
            run = 1;
        }
        cluster = layout.clusterOf(source);
        pops = 0;
        heapSize = 0;
        set(source, 0, source, (byte) -1);
        push(0, source);
        while (heapSize > 0) {
            double d = heapKey[0];
            long pos = pop();
            if (d > distance(pos)) {
                continue; // a stale entry: this cell was reached more cheaply since
            }
            pops++;
            if (pos == stopAt) {
                return d;
            }
            current = pos;
            currentDist = d;
            moves.moves(pos, relax);
        }
        return Double.POSITIVE_INFINITY;
    }

    /** The cost from the last run's source, or infinity if that run didn't reach it. */
    public double distance(long pos) {
        if (!layout.contains(cluster, pos)) {
            return Double.POSITIVE_INFINITY;
        }
        int i = layout.localIndex(cluster, pos);
        return stamp[i] == run ? dist[i] : Double.POSITIVE_INFINITY;
    }

    /** Cells settled by the last run: its share of the work, comparable to nodes expanded. */
    public int settled() {
        return pops;
    }

    private void relax(long to, MoveType type) {
        if (!layout.contains(cluster, to)) {
            return;
        }
        double d = currentDist + costs.cost(current, to, type);
        if (d < distance(to)) {
            set(to, d, current, (byte) type.ordinal());
            push(d, to);
        }
    }

    private void set(long pos, double d, long from, byte type) {
        int i = layout.localIndex(cluster, pos);
        dist[i] = d;
        parent[i] = from;
        via[i] = type;
        stamp[i] = run;
    }

    private static final MoveType[] TYPES = MoveType.values();

    /**
     * The cheapest path from the last run's source to {@code target}, which that run must have
     * settled (pass it as {@code stopAt}), or an empty list if it wasn't reached.
     */
    public List<PathStep> pathTo(long target) {
        if (distance(target) == Double.POSITIVE_INFINITY) {
            return List.of();
        }
        List<PathStep> path = new ArrayList<>();
        for (long p = target; ; ) {
            int i = layout.localIndex(cluster, p);
            path.add(new PathStep(Pos.toPoint(p), via[i] < 0 ? null : TYPES[via[i]]));
            if (via[i] < 0) {
                break;
            }
            p = parent[i];
        }
        Collections.reverse(path);
        return path;
    }

    private void push(double key, long pos) {
        if (heapSize == heapKey.length) {
            heapKey = Arrays.copyOf(heapKey, heapSize * 2);
            heapPos = Arrays.copyOf(heapPos, heapSize * 2);
        }
        int i = heapSize++;
        while (i > 0) {
            int parent = (i - 1) / 2;
            if (heapKey[parent] <= key) {
                break;
            }
            heapKey[i] = heapKey[parent];
            heapPos[i] = heapPos[parent];
            i = parent;
        }
        heapKey[i] = key;
        heapPos[i] = pos;
    }

    private long pop() {
        long top = heapPos[0];
        heapSize--;
        if (heapSize > 0) {
            double key = heapKey[heapSize];
            long pos = heapPos[heapSize];
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
                heapPos[i] = heapPos[child];
                i = child;
            }
            heapKey[i] = key;
            heapPos[i] = pos;
        }
        return top;
    }
}
