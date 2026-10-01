package astar.pathing;

import astar.core.CostModel;
import astar.core.MoveSource;
import astar.core.MoveType;
import astar.core.Pos;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

/**
 * The abstract graph of hierarchical A*: a few cells on the edges of each cluster (portals),
 * joined by the moves between clusters and by the cheapest paths inside each cluster.
 *
 * <p>How it's built:
 *
 * <ol>
 *   <li>Every move out of a cell on a cluster's edge is generated once. The ones that end in
 *       another cluster are <b>transitions</b>.
 *   <li>Transitions from cluster A to cluster B are grouped into <b>entrances</b>: two are in
 *       the same entrance when their starts are the same cell or one move apart both ways, and
 *       so are their ends. A cave floor and a ledge above it stay separate entrances.
 *   <li>Each entrance keeps its middle transition, and its two ends too when it's more than
 *       {@value #WIDE} columns wide. Both cells of a kept transition become portals, joined by an
 *       edge that costs what the move costs.
 *   <li>From each portal, a {@link ClusterDijkstra} inside its cluster prices an edge to every
 *       other portal of the cluster it can reach.
 * </ol>
 *
 * <p>Every edge is directed (drops are one-way), and every edge costs at least the horizontal
 * distance it covers, since it's made of real moves: the core's heuristics stay admissible on
 * this graph. No route is lost: any path through a transition can go through its entrance's
 * kept one instead, since the cells of an entrance can reach each other inside their cluster.
 */
public final class PortalGraph {
    /** Entrances wider than this many columns keep both ends as well as the middle. */
    static final int WIDE = 6;
    /** In {@link #edgeType}: an edge inside a cluster rather than a single move. */
    static final byte INSIDE = -1;
    private static final MoveType[] TYPES = MoveType.values();

    final ClusterLayout layout;
    final long[] pos;           // portal id -> position
    final int[] edgeStart;      // portal id -> its first edge; edges of p are [start[p], start[p+1])
    final int[] edgeTo;         // portal ids
    final double[] edgeCost;
    final byte[] edgeType;      // MoveType ordinal for a move between clusters, or INSIDE
    final int[] clusterStart;   // cluster -> its first portal in clusterPortals
    final int[] clusterPortals; // portal ids, grouped by cluster
    private final LongIntMap ids;
    private final int entrances;
    private final int transitions;
    private final long settled;

    private PortalGraph(ClusterLayout layout, long[] pos, int[] edgeStart, int[] edgeTo,
            double[] edgeCost, byte[] edgeType, int[] clusterStart, int[] clusterPortals,
            LongIntMap ids, int entrances, int transitions, long settled) {
        this.layout = layout;
        this.pos = pos;
        this.edgeStart = edgeStart;
        this.edgeTo = edgeTo;
        this.edgeCost = edgeCost;
        this.edgeType = edgeType;
        this.clusterStart = clusterStart;
        this.clusterPortals = clusterPortals;
        this.ids = ids;
        this.entrances = entrances;
        this.transitions = transitions;
        this.settled = settled;
    }

    public ClusterLayout layout() {
        return layout;
    }

    public int portalCount() {
        return pos.length;
    }

    public int edgeCount() {
        return edgeTo.length;
    }

    public int entranceCount() {
        return entrances;
    }

    /** Moves between clusters found while building, before entrances picked a few of them. */
    public int transitionCount() {
        return transitions;
    }

    /** Cells settled by all the cluster Dijkstras of the build. */
    public long settledWhileBuilding() {
        return settled;
    }

    /** Roughly how much memory the graph takes, in bytes. */
    public long bytes() {
        return pos.length * (8L + 4 + 16) + edgeTo.length * (4L + 8 + 1) + clusterPortals.length * 4L;
    }

    /** The portal at this position, or -1. */
    public int idOf(long position) {
        return ids.get(position, -1);
    }

    public long position(int id) {
        return pos[id];
    }

    public boolean isPortal(long position) {
        return idOf(position) >= 0;
    }

    /** The move between two portals in neighbouring clusters, or null if there's no such edge. */
    public MoveType moveBetween(long from, long to) {
        int a = idOf(from);
        int b = idOf(to);
        if (a < 0 || b < 0) {
            return null;
        }
        for (int e = edgeStart[a]; e < edgeStart[a + 1]; e++) {
            if (edgeTo[e] == b && edgeType[e] != INSIDE) {
                return TYPES[edgeType[e]];
            }
        }
        return null;
    }

    /** Builds the graph for a world, one entity's moves and one cost model. */
    public static PortalGraph build(ClusterLayout layout, MoveSource moves, CostModel costs) {
        EdgeCells cells = EdgeCells.scan(layout, moves);
        Builder b = new Builder(layout, cells, costs);
        b.pickEntrances();
        return b.finish(moves);
    }

    // ---- Step 1: every move out of every cell on a cluster edge -------------------------------

    /** The cells on cluster edges that have at least one move, and their moves. */
    static final class EdgeCells implements MoveSource.MoveSink {
        final LongIntMap ids = new LongIntMap(1 << 14);
        long[] cellPos = new long[1024];
        int[] firstMove = new int[1025]; // moves of cell i are [firstMove[i], firstMove[i + 1])
        int count;
        long[] moveTo = new long[4096];
        byte[] moveType = new byte[4096];
        int moveCount;

        static EdgeCells scan(ClusterLayout layout, MoveSource moves) {
            EdgeCells c = new EdgeCells();
            for (int z = 0; z < layout.sizeZ(); z++) {
                for (int x = 0; x < layout.sizeX(); x++) {
                    if (!layout.onEdge(x, z)) {
                        continue;
                    }
                    for (int y = 0; y < layout.sizeY(); y++) {
                        long p = Pos.pack(x, y, z);
                        int before = c.moveCount;
                        moves.moves(p, c);
                        if (c.moveCount > before) {
                            c.addCell(p);
                        }
                    }
                }
            }
            return c;
        }

        @Override
        public void accept(long to, MoveType type) {
            if (moveCount == moveTo.length) {
                moveTo = Arrays.copyOf(moveTo, moveCount * 2);
                moveType = Arrays.copyOf(moveType, moveCount * 2);
            }
            moveTo[moveCount] = to;
            moveType[moveCount] = (byte) type.ordinal();
            moveCount++;
        }

        private void addCell(long p) {
            if (count + 1 == cellPos.length) {
                cellPos = Arrays.copyOf(cellPos, cellPos.length * 2);
                firstMove = Arrays.copyOf(firstMove, cellPos.length + 1);
            }
            ids.put(p, count);
            cellPos[count] = p;
            count++;
            firstMove[count] = moveCount;
        }

        /** Whether the edge cell {@code from} has a move to {@code to}. */
        boolean hasMove(long from, long to) {
            int i = ids.get(from, -1);
            if (i < 0) {
                return false;
            }
            for (int m = firstMove[i]; m < firstMove[i + 1]; m++) {
                if (moveTo[m] == to) {
                    return true;
                }
            }
            return false;
        }

        /** The same cell, or one move apart in both directions. */
        boolean joined(long a, long b) {
            return a == b || (hasMove(a, b) && hasMove(b, a));
        }
    }

    // ---- Steps 2 to 4 --------------------------------------------------------------------------

    private static final class Builder {
        private final ClusterLayout layout;
        private final EdgeCells cells;
        private final CostModel costs;

        // Transitions: the move index into cells.moveTo, and the cell it starts from.
        private int[] tMove;
        private int[] tCell;
        private int transitions;
        private int entrances;

        // Portals and the edges between clusters, as they're found.
        private final LongIntMap ids = new LongIntMap(1 << 12);
        private long[] pos = new long[256];
        private int portals;
        private final List<long[]> interEdges = new ArrayList<>(); // {from id, to id, move index}
        private final LongIntMap kept = new LongIntMap(1 << 12);  // (from id, to id) already kept

        Builder(ClusterLayout layout, EdgeCells cells, CostModel costs) {
            this.layout = layout;
            this.cells = cells;
            this.costs = costs;
        }

        private long from(int t) {
            return cells.cellPos[tCell[t]];
        }

        private long to(int t) {
            return cells.moveTo[tMove[t]];
        }

        /** Position along the cluster edge: neighbouring columns differ by at most 2. */
        private int along(int t) {
            long p = from(t);
            return Pos.x(p) + Pos.z(p);
        }

        private long pair(int t) {
            return (long) layout.clusterOf(from(t)) * layout.count() + layout.clusterOf(to(t));
        }

        void pickEntrances() {
            tMove = new int[256];
            tCell = new int[256];
            for (int i = 0; i < cells.count; i++) {
                int here = layout.clusterOf(cells.cellPos[i]);
                for (int m = cells.firstMove[i]; m < cells.firstMove[i + 1]; m++) {
                    if (layout.clusterOf(cells.moveTo[m]) != here) {
                        if (transitions == tMove.length) {
                            tMove = Arrays.copyOf(tMove, transitions * 2);
                            tCell = Arrays.copyOf(tCell, transitions * 2);
                        }
                        tMove[transitions] = m;
                        tCell[transitions] = i;
                        transitions++;
                    }
                }
            }
            // Sorted by cluster pair, then along the edge, then height and target, so each
            // pair's transitions are one run and neighbours along the edge sit close together.
            Integer[] order = new Integer[transitions];
            for (int t = 0; t < transitions; t++) {
                order[t] = t;
            }
            Arrays.sort(order, Comparator.<Integer>comparingLong(this::pair)
                    .thenComparingInt(this::along)
                    .thenComparingInt(t -> Pos.y(from(t)))
                    .thenComparingLong(this::from)
                    .thenComparingLong(this::to));
            int[] sorted = new int[transitions];
            for (int k = 0; k < transitions; k++) {
                sorted[k] = order[k];
            }

            int[] root = new int[transitions]; // union-find over positions in `sorted`
            for (int k = 0; k < transitions; k++) {
                root[k] = k;
            }
            int start = 0;
            while (start < transitions) {
                int end = start;
                long key = pair(sorted[start]);
                while (end < transitions && pair(sorted[end]) == key) {
                    end++;
                }
                for (int i = start; i < end; i++) {
                    int ti = sorted[i];
                    for (int j = i + 1; j < end && along(sorted[j]) - along(ti) <= 2; j++) {
                        int tj = sorted[j];
                        if (cells.joined(from(ti), from(tj)) && cells.joined(to(ti), to(tj))) {
                            union(root, i, j);
                        }
                    }
                }
                keepEntrances(sorted, root, start, end);
                start = end;
            }
        }

        /** Keeps the middle transition of each entrance in [start, end), and wide ones' ends. */
        private void keepEntrances(int[] sorted, int[] root, int start, int end) {
            // Members of each entrance, in sorted order, grouped by their root.
            int n = end - start;
            int[] byRoot = new int[n];
            for (int k = 0; k < n; k++) {
                byRoot[k] = start + k;
            }
            int[] rootOf = new int[n];
            for (int k = 0; k < n; k++) {
                rootOf[k] = find(root, start + k);
            }
            Integer[] idx = new Integer[n];
            for (int k = 0; k < n; k++) {
                idx[k] = k;
            }
            Arrays.sort(idx, Comparator.<Integer>comparingInt(k -> rootOf[k]).thenComparingInt(k -> k));
            int a = 0;
            while (a < n) {
                int b = a;
                while (b < n && rootOf[idx[b]] == rootOf[idx[a]]) {
                    b++;
                }
                entrances++;
                int first = sorted[start + idx[a]];
                int last = sorted[start + idx[b - 1]];
                int middle = sorted[start + idx[(a + b - 1) / 2]];
                keep(middle);
                if (along(last) - along(first) + 1 > WIDE) {
                    keep(first);
                    keep(last);
                }
                a = b;
            }
        }

        private void keep(int t) {
            int a = portal(from(t));
            int b = portal(to(t));
            long key = ((long) a << 32) | b;
            if (kept.get(key, -1) >= 0) {
                return;
            }
            kept.put(key, interEdges.size());
            interEdges.add(new long[] {a, b, tMove[t]});
        }

        private int portal(long p) {
            int id = ids.get(p, -1);
            if (id >= 0) {
                return id;
            }
            if (portals == pos.length) {
                pos = Arrays.copyOf(pos, portals * 2);
            }
            pos[portals] = p;
            ids.put(p, portals);
            return portals++;
        }

        private static int find(int[] root, int i) {
            while (root[i] != i) {
                root[i] = root[root[i]];
                i = root[i];
            }
            return i;
        }

        private static void union(int[] root, int i, int j) {
            int a = find(root, i);
            int b = find(root, j);
            if (a != b) {
                root[Math.max(a, b)] = Math.min(a, b);
            }
        }

        // ---- Step 5: edges inside clusters, and the finished graph ---------------------------

        PortalGraph finish(MoveSource moves) {
            long[] portalPos = Arrays.copyOf(pos, portals);
            int clusters = layout.count();
            int[] clusterStart = new int[clusters + 1];
            for (int p = 0; p < portals; p++) {
                clusterStart[layout.clusterOf(portalPos[p]) + 1]++;
            }
            for (int c = 0; c < clusters; c++) {
                clusterStart[c + 1] += clusterStart[c];
            }
            int[] clusterPortals = new int[portals];
            int[] fill = Arrays.copyOf(clusterStart, clusters);
            for (int p = 0; p < portals; p++) {
                clusterPortals[fill[layout.clusterOf(portalPos[p])]++] = p;
            }

            // The Dijkstras are independent per cluster, so they run in parallel; each thread
            // keeps its own arrays. Results are put together in cluster order, so the graph is
            // the same whatever the thread count.
            ThreadLocal<ClusterDijkstra> dijkstra =
                    ThreadLocal.withInitial(() -> new ClusterDijkstra(layout, moves, costs));
            Inside[] inside = new Inside[clusters];
            IntStream.range(0, clusters).parallel().forEach(c -> inside[c] = inside(
                    dijkstra.get(), portalPos, clusterPortals, clusterStart[c], clusterStart[c + 1]));

            int[] degree = new int[portals + 1];
            for (long[] e : interEdges) {
                degree[(int) e[0] + 1]++;
            }
            long settled = 0;
            for (Inside in : inside) {
                for (int k = 0; k < in.count; k++) {
                    degree[in.from[k] + 1]++;
                }
                settled += in.settled;
            }
            for (int p = 0; p < portals; p++) {
                degree[p + 1] += degree[p];
            }
            int edges = degree[portals];
            int[] edgeTo = new int[edges];
            double[] edgeCost = new double[edges];
            byte[] edgeType = new byte[edges];
            int[] next = Arrays.copyOf(degree, portals);
            for (long[] e : interEdges) {
                int from = (int) e[0];
                int m = (int) e[2];
                int k = next[from]++;
                edgeTo[k] = (int) e[1];
                edgeCost[k] = costs.cost(portalPos[from], portalPos[(int) e[1]],
                        TYPES[cells.moveType[m]]);
                edgeType[k] = cells.moveType[m];
            }
            for (Inside in : inside) {
                for (int i = 0; i < in.count; i++) {
                    int k = next[in.from[i]]++;
                    edgeTo[k] = in.to[i];
                    edgeCost[k] = in.cost[i];
                    edgeType[k] = INSIDE;
                }
            }
            return new PortalGraph(layout, portalPos, degree, edgeTo, edgeCost, edgeType,
                    clusterStart, clusterPortals, ids, entrances, transitions, settled);
        }

        /** The edges between the portals of one cluster. */
        private static Inside inside(ClusterDijkstra d, long[] pos, int[] clusterPortals,
                int first, int end) {
            Inside in = new Inside();
            for (int i = first; i < end; i++) {
                int p = clusterPortals[i];
                d.run(pos[p], ClusterDijkstra.NO_TARGET);
                in.settled += d.settled();
                for (int j = first; j < end; j++) {
                    int q = clusterPortals[j];
                    double cost = q == p ? Double.POSITIVE_INFINITY : d.distance(pos[q]);
                    if (cost < Double.POSITIVE_INFINITY) {
                        in.add(p, q, cost);
                    }
                }
            }
            return in;
        }
    }

    private static final class Inside {
        int[] from = new int[16];
        int[] to = new int[16];
        double[] cost = new double[16];
        int count;
        long settled;

        void add(int f, int t, double c) {
            if (count == from.length) {
                from = Arrays.copyOf(from, count * 2);
                to = Arrays.copyOf(to, count * 2);
                cost = Arrays.copyOf(cost, count * 2);
            }
            from[count] = f;
            to[count] = t;
            cost[count] = c;
            count++;
        }
    }
}
