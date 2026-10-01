package astar.core;

import astar.core.SearchResult.Status;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The A* loop as a resumable object. {@link #step()} does one expansion, so a caller can spread
 * a long search over many ticks with {@link #run(int)}.
 *
 * <p>Node state lives in a {@link NodeTable} (flat arrays, no object per node) and the open set
 * is a binary min-heap of node ids ordered by f, then h. The heap follows exactly the same rules
 * as {@link MinHeap}, so results, including tie-breaking, are the same as the original
 * object-per-node version.
 *
 * <p>Given a {@link MoveGraph} instead of a {@link MoveSource}, it reads each node's moves and
 * their prices from the graph's arrays, and (unless it keeps a node per heading) numbers its
 * nodes by the graph's cells, so it needs no hashing either. With the same moves, listed in the
 * same order, at the same prices, it expands the same nodes and returns the same path.
 *
 * <p>Those per-cell node tables are as big as the graph (megabytes on a whole map), so a
 * finished search hands its table back, cleared, for the next search on the same graph.
 * Allocating and filling a fresh one took longer than many searches themselves.
 */
public final class AStarSearch {
    private static final MoveType[] MOVES = MoveType.values();
    private final long goal;
    private final MoveSource moves;         // null when searching a graph
    private final MoveGraph graph;          // null when searching a move source
    private final CostModel costs;
    private final TurningCostModel turning; // costs, if it charges for turns; else null
    private final Heuristic heuristic;
    private final SearchListener listener;
    private final boolean observed;

    // A graph's spare node table, cleared, kept between searches. Its pos array is dropped
    // while it waits, so the graph's positions (the key) are only weakly held.
    private static final Map<long[], NodeTable> SPARE_TABLES = new WeakHashMap<>();

    private NodeTable nodes; // null once a dense search has finished and handed its table back
    private final long[] positions; // the graph's cell positions, for dense searches; else null
    private int[] touched; // dense searches: every node given a g, to clear on hand-back
    private int touchedCount;
    private List<PathStep> finalPath; // dense searches: kept when the table goes back
    private double finalCost = Double.POSITIVE_INFINITY;
    // The heap holds node ids with their sort keys (f, then h) alongside, so comparisons read
    // neighbouring memory instead of jumping into the node arrays.
    private int[] heap = new int[1024];
    private double[] heapF = new double[1024];
    private double[] heapH = new double[1024];
    private int heapSize;
    private int[] closedOrder = new int[1024]; // expansion order, for results
    private int closedCount;

    private Status status = Status.RUNNING;
    private int found = NodeTable.NONE;
    private int best = NodeTable.NONE; // closest expanded node to the goal, for bestSoFar()
    private int current;               // the node being expanded; read by the move sink
    private final MoveSource.MoveSink relax = this::relax;
    private final boolean dense; // node ids are the graph's cells
    private final boolean perHeading; // a node per (position, heading)
    // Graph searches with a turn cost that depends only on headings: each move's heading, and
    // the turn cost by (heading in, heading out). Both null otherwise.
    private final byte[] moveHeading;
    private final double[] turnByHeading;

    public AStarSearch(BlockPoint start, BlockPoint goal, MoveSource moves, CostModel costs,
            Heuristic heuristic) {
        this(start, goal, moves, costs, heuristic, SearchListener.NONE);
    }

    public AStarSearch(BlockPoint start, BlockPoint goal, MoveSource moves, CostModel costs,
            Heuristic heuristic, SearchListener listener) {
        this(start, goal, moves, null, costs, heuristic, listener);
    }

    /**
     * Searches a graph of moves worked out ahead of time. {@code costs} only adds turn costs
     * (when it is a {@link TurningCostModel}); the moves' own prices come from the graph.
     *
     * @throws IllegalArgumentException if the start isn't in the graph
     */
    public AStarSearch(BlockPoint start, BlockPoint goal, MoveGraph graph, CostModel costs,
            Heuristic heuristic, SearchListener listener) {
        this(start, goal, null, graph, costs, heuristic, listener);
    }

    private AStarSearch(BlockPoint start, BlockPoint goal, MoveSource moves, MoveGraph graph,
            CostModel costs, Heuristic heuristic, SearchListener listener) {
        this.goal = goal.pack();
        this.moves = moves;
        this.graph = graph;
        this.costs = costs;
        this.turning = costs instanceof TurningCostModel t ? t : null;
        this.heuristic = heuristic;
        this.listener = listener;
        this.observed = listener != SearchListener.NONE;

        boolean dense = graph != null && (turning == null || !turning.exact());
        int s;
        if (graph != null) {
            s = graph.cell(start.pack());
            if (s < 0) {
                throw new IllegalArgumentException("The start " + start + " isn't in the graph");
            }
            this.nodes = dense ? borrow(graph.positions()) : new NodeTable(1024);
            if (!dense) {
                s = nodes.getOrCreate(start.pack());
            }
        } else {
            this.nodes = new NodeTable(1024);
            s = nodes.getOrCreate(start.pack());
        }
        this.dense = dense;
        this.positions = dense ? graph.positions() : null;
        this.touched = dense ? new int[1024] : null;
        this.perHeading = turning != null && turning.exact();
        double[] table = graph != null && turning != null ? turning.turnCostByHeading() : null;
        byte[] headings = table != null ? graph.moveHeading() : null;
        this.moveHeading = headings;
        this.turnByHeading = headings != null ? table : null;
        heuristic.prepare(start.pack(), this.goal);
        nodes.g[s] = 0;
        nodes.h[s] = heuristic.between(start.pack(), this.goal);
        push(s);
        if (observed) {
            listener.onStart(view(s), goal);
        }
    }

    public Status status() {
        return status;
    }

    /** Expands one node. Does nothing once the search has finished. */
    public Status step() {
        if (status != Status.RUNNING) {
            return status;
        }
        if (heapSize == 0) {
            return finish(Status.NO_PATH);
        }

        int c = poll();
        if (nodes.pos[c] == goal) {
            found = c;
            return finish(Status.FOUND);
        }

        nodes.closed[c] = true;
        if (closedCount == closedOrder.length) {
            closedOrder = Arrays.copyOf(closedOrder, closedCount * 2);
        }
        closedOrder[closedCount++] = c;
        if (best == NodeTable.NONE || nodes.h[c] < nodes.h[best]
                || (nodes.h[c] == nodes.h[best] && nodes.g[c] < nodes.g[best])) {
            best = c;
        }
        if (observed) {
            listener.onExpand(view(c));
        }

        current = c;
        if (graph == null) {
            moves.moves(nodes.pos[c], relax);
        } else {
            expandGraph(c);
        }
        return status;
    }

    /** Relaxes the moves out of node c, read from the graph. */
    private void expandGraph(int c) {
        long from = nodes.pos[c];
        int cell = dense ? c : graph.cell(from);
        int[] start = graph.moveStart();
        int[] target = graph.moveTo();
        double[] price = graph.moveCost();
        byte[] type = graph.moveType();
        long[] positions = graph.positions();
        int end = start[cell + 1];
        if (turning == null) {
            for (int e = start[cell]; e < end; e++) {
                int next = target[e];
                if (!nodes.closed[next]) {
                    update(c, next, positions[next], MOVES[type[e]], price[e]);
                }
            }
        } else if (!turning.exact()) {
            int parent = nodes.parent[c];
            long before = parent == NodeTable.NONE ? 0 : nodes.pos[parent];
            if (moveHeading != null) {
                // One table look-up per move: the heading in is the step from the parent.
                int in = parent == NodeTable.NONE ? 0 : headingOf(before, from);
                if (in >= 0) {
                    byte[] mh = moveHeading;
                    double[] turn = turnByHeading;
                    int row = in * 26;
                    for (int e = start[cell]; e < end; e++) {
                        int next = target[e];
                        if (!nodes.closed[next]) {
                            update(c, next, positions[next], MOVES[type[e]],
                                    price[e] + turn[row + mh[e]]);
                        }
                    }
                    return;
                }
            }
            for (int e = start[cell]; e < end; e++) {
                int next = target[e];
                if (nodes.closed[next]) {
                    continue;
                }
                long to = positions[next];
                MoveType t = MOVES[type[e]];
                double stepCost = parent == NodeTable.NONE ? price[e]
                        : price[e] + turning.turnCost(before, from, to, t);
                update(c, next, to, t, stepCost);
            }
        } else {
            int head = nodes.heading[c];
            long before = head == 0 ? 0 : Pos.offset(from, -NodeTable.headingX(head), 0,
                    -NodeTable.headingZ(head));
            if (moveHeading != null) {
                byte[] mh = moveHeading;
                double[] turn = turnByHeading;
                int row = head * 26;
                for (int e = start[cell]; e < end; e++) {
                    long to = positions[target[e]];
                    int out = mh[e];
                    int next = nodes.getOrCreate(to, out);
                    if (!nodes.closed[next]) {
                        update(c, next, to, MOVES[type[e]], price[e] + turn[row + out]);
                    }
                }
                return;
            }
            for (int e = start[cell]; e < end; e++) {
                long to = positions[target[e]];
                int next = nodes.getOrCreate(to, NodeTable.heading(Pos.x(to) - Pos.x(from),
                        Pos.z(to) - Pos.z(from)));
                if (nodes.closed[next]) {
                    continue;
                }
                MoveType t = MOVES[type[e]];
                double stepCost = head == 0 ? price[e]
                        : price[e] + turning.turnCost(before, from, to, t);
                update(c, next, to, t, stepCost);
            }
        }
    }

    /** The heading code of the step from {@code a} to {@code b}, or -1 if it is too long. */
    private static int headingOf(long a, long b) {
        int dx = Pos.x(b) - Pos.x(a);
        int dz = Pos.z(b) - Pos.z(a);
        return Math.abs(dx) > 2 || Math.abs(dz) > 2 ? -1 : NodeTable.heading(dx, dz);
    }

    private void relax(long to, MoveType type) {
        int c = current;
        long from = nodes.pos[c];
        int next;
        double stepCost;
        if (turning == null) {
            next = nodes.getOrCreate(to);
            stepCost = costs.cost(from, to, type);
        } else if (!turning.exact()) {
            // The turn from whichever parent reached this cell most cheaply.
            next = nodes.getOrCreate(to);
            int parent = nodes.parent[c];
            stepCost = parent == NodeTable.NONE ? costs.cost(from, to, type)
                    : turning.cost(nodes.pos[parent], from, to, type);
        } else {
            // One node per way into a cell, so the turn out of it is charged exactly: the
            // cheapest path counting turns, not just the cheapest way into each cell.
            next = nodes.getOrCreate(to, NodeTable.heading(Pos.x(to) - Pos.x(from),
                    Pos.z(to) - Pos.z(from)));
            int head = nodes.heading[c];
            stepCost = head == 0 ? costs.cost(from, to, type)
                    : turning.cost(Pos.offset(from, -NodeTable.headingX(head), 0,
                            -NodeTable.headingZ(head)), from, to, type);
        }
        if (nodes.closed[next]) {
            return;
        }
        update(c, next, to, type, stepCost);
    }

    /** Reaching node {@code next} (at {@code to}) from node c by a move costing stepCost. */
    private void update(int c, int next, long to, MoveType type, double stepCost) {
        double tentativeG = nodes.g[c] + stepCost;
        boolean inOpen = nodes.heapIndex[next] != NodeTable.NONE;
        if (inOpen && tentativeG >= nodes.g[next]) {
            return; // not an improvement
        }

        double oldG = nodes.g[next];
        nodes.g[next] = tentativeG;
        if (!inOpen) {
            // h depends only on the node: its position, and its heading when it keeps one.
            nodes.h[next] = perHeading ? heuristic.between(to, nodes.heading[next], goal)
                    : dense ? heuristic.between(graph, next, to, goal)
                    : heuristic.between(to, goal);
        }
        nodes.parent[next] = c;
        nodes.setVia(next, type);
        if (inOpen) {
            int i = nodes.heapIndex[next];
            heapF[i] = tentativeG + nodes.h[next];
            heapH[i] = nodes.h[next];
            siftUp(i); // f dropped: move it up in place
            if (observed) {
                listener.onImprove(view(next), oldG);
            }
        } else {
            push(next);
            if (observed) {
                listener.onOpen(view(next), stepCost);
            }
        }
    }

    /** Runs up to {@code maxExpansions} steps, stopping early if the search finishes. */
    public Status run(int maxExpansions) {
        for (int i = 0; i < maxExpansions && status == Status.RUNNING; i++) {
            step();
        }
        return status;
    }

    public Status runToEnd() {
        while (status == Status.RUNNING) {
            step();
        }
        return status;
    }

    public int expanded() {
        return closedCount;
    }

    /** The current state. While running, the path is empty. */
    public SearchResult result() {
        Set<BlockPoint> closedPoints = new LinkedHashSet<>(capacity(closedCount));
        for (int i = 0; i < closedCount; i++) {
            closedPoints.add(Pos.toPoint(posOf(closedOrder[i])));
        }
        Set<BlockPoint> openPoints = new LinkedHashSet<>(capacity(heapSize));
        for (int i = 0; i < heapSize; i++) {
            openPoints.add(Pos.toPoint(posOf(heap[i])));
        }
        boolean ok = status == Status.FOUND;
        return new SearchResult(
                status,
                ok ? path(found) : List.of(),
                ok ? cost(found) : Double.POSITIVE_INFINITY,
                Collections.unmodifiableSet(closedPoints),
                Collections.unmodifiableSet(openPoints),
                closedCount);
    }

    /**
     * The path to the expanded node closest to the goal (lowest h), or to the goal once found.
     * A fallback for callers that stop a search before it finishes. With a heuristic that is
     * always 0 (Dijkstra), every node ties and this is just the start.
     */
    public List<PathStep> bestSoFar() {
        if (found != NodeTable.NONE) {
            return path(found);
        }
        return best == NodeTable.NONE ? List.of() : path(best);
    }

    private Status finish(Status s) {
        status = s;
        if (observed) {
            listener.onFinish(result());
        }
        if (dense) {
            // Keep what result() and bestSoFar() still need, then hand the table back.
            int end = found != NodeTable.NONE ? found : best;
            if (end != NodeTable.NONE) {
                finalPath = reconstructPath(end);
                finalCost = nodes.g[end];
            }
            release(positions, nodes, touched, touchedCount);
            nodes = null;
            touched = null;
        }
        return s;
    }

    /** The path to a node: read from the table, or kept from it once it went back. */
    private List<PathStep> path(int id) {
        return nodes != null ? reconstructPath(id) : finalPath;
    }

    private double cost(int id) {
        return nodes != null ? nodes.g[id] : finalCost;
    }

    private long posOf(int id) {
        return dense ? positions[id] : nodes.pos[id];
    }

    /** A cleared node table for a graph: its spare one if free, else a new one. */
    private static NodeTable borrow(long[] positions) {
        NodeTable t;
        synchronized (SPARE_TABLES) {
            t = SPARE_TABLES.remove(positions);
        }
        if (t == null) {
            return new NodeTable(positions);
        }
        t.pos = positions;
        return t;
    }

    /** Clears the nodes a finished search touched and keeps the table for the next search. */
    private static void release(long[] positions, NodeTable t, int[] touched, int count) {
        t.reset(touched, count);
        t.pos = null;
        synchronized (SPARE_TABLES) {
            SPARE_TABLES.put(positions, t);
        }
    }

    private NodeView view(int id) {
        int p = nodes.parent[id];
        return new NodeView(Pos.toPoint(nodes.pos[id]), nodes.g[id], nodes.h[id],
                p == NodeTable.NONE ? null : Pos.toPoint(nodes.pos[p]), nodes.via(id));
    }

    /** Walks parent links back to the start, then reverses. */
    private List<PathStep> reconstructPath(int id) {
        List<PathStep> path = new ArrayList<>();
        for (int n = id; n != NodeTable.NONE; n = nodes.parent[n]) {
            path.add(new PathStep(Pos.toPoint(nodes.pos[n]), nodes.via(n)));
        }
        Collections.reverse(path);
        return List.copyOf(path);
    }

    private static int capacity(int n) {
        return (int) Math.min(Integer.MAX_VALUE - 8, n * 4L / 3 + 1);
    }

    // ---- The open set: a binary min-heap of node ids (same rules as MinHeap) ---------------

    /** Lower f first; on a tie, lower h (closer to the goal) first. */
    private static int compare(double fa, double ha, double fb, double hb) {
        int byF = Double.compare(fa, fb);
        return byF != 0 ? byF : Double.compare(ha, hb);
    }

    private void push(int id) {
        if (heapSize == heap.length) {
            int n = heapSize * 2;
            heap = Arrays.copyOf(heap, n);
            heapF = Arrays.copyOf(heapF, n);
            heapH = Arrays.copyOf(heapH, n);
        }
        if (dense) {
            if (touchedCount == touched.length) {
                touched = Arrays.copyOf(touched, touchedCount * 2);
            }
            touched[touchedCount++] = id;
        }
        int i = heapSize++;
        place(id, nodes.g[id] + nodes.h[id], nodes.h[id], i);
        siftUp(i);
    }

    private int poll() {
        int min = heap[0];
        heapSize--;
        if (heapSize > 0) {
            place(heap[heapSize], heapF[heapSize], heapH[heapSize], 0);
            siftDown(0);
        }
        nodes.heapIndex[min] = NodeTable.NONE;
        return min;
    }

    private void siftUp(int i) {
        int item = heap[i];
        double f = heapF[i];
        double h = heapH[i];
        while (i > 0) {
            int parent = (i - 1) / 2;
            if (compare(f, h, heapF[parent], heapH[parent]) >= 0) {
                break;
            }
            place(heap[parent], heapF[parent], heapH[parent], i);
            i = parent;
        }
        place(item, f, h, i);
    }

    private void siftDown(int i) {
        int item = heap[i];
        double f = heapF[i];
        double h = heapH[i];
        while (true) {
            int left = 2 * i + 1;
            if (left >= heapSize) {
                break;
            }
            int right = left + 1;
            int smaller = right < heapSize
                    && compare(heapF[right], heapH[right], heapF[left], heapH[left]) < 0 ? right : left;
            if (compare(f, h, heapF[smaller], heapH[smaller]) <= 0) {
                break;
            }
            place(heap[smaller], heapF[smaller], heapH[smaller], i);
            i = smaller;
        }
        place(item, f, h, i);
    }

    private void place(int id, double f, double h, int i) {
        heap[i] = id;
        heapF[i] = f;
        heapH[i] = h;
        nodes.heapIndex[id] = i;
    }
}
