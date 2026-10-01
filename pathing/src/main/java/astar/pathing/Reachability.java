package astar.pathing;

import astar.core.BlockPoint;
import astar.core.CostModel;
import astar.core.DefaultCostModel;
import astar.core.Pos;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.stream.IntStream;

/** Finds what's reachable, and far-apart endpoints for testing long routes. */
public final class Reachability {
    private Reachability() {}

    /** Two points and the cheapest path cost from one to the other. */
    public record Route(BlockPoint start, BlockPoint goal, double cost) {}

    /** The cheapest cost from {@code from} to every reachable standable cell (Dijkstra). */
    public static Map<Long, Double> costsFrom(MoveValidator validator, boolean diagonal,
            CostModel costs, BlockPoint from) {
        record Item(double d, long pos) {}
        BlockMoveSource moves = new BlockMoveSource(validator, diagonal);
        Map<Long, Double> dist = new HashMap<>();
        dist.put(from.pack(), 0.0);
        PriorityQueue<Item> heap = new PriorityQueue<>((a, b) -> Double.compare(a.d(), b.d()));
        heap.add(new Item(0, from.pack()));
        while (!heap.isEmpty()) {
            Item cur = heap.poll();
            if (cur.d() > dist.get(cur.pos())) {
                continue;
            }
            moves.moves(cur.pos(), (to, type) -> {
                double nd = cur.d() + costs.cost(cur.pos(), to, type);
                if (nd < dist.getOrDefault(to, Double.POSITIVE_INFINITY)) {
                    dist.put(to, nd);
                    heap.add(new Item(nd, to));
                }
            });
        }
        return dist;
    }

    /**
     * A long route across the world's largest walkable area.
     *
     * <ol>
     *   <li>Sample up to {@value #SEEDS} standable cells spread over the world, and keep the one
     *       with the biggest round-trip area (cells it can reach and get back from), so the
     *       route lands in the main area rather than a pocket or a one-way pit.
     *   <li>Within that area, find the farthest cell B, then the farthest cell C from B, and
     *       return B to C (the "double sweep": close to the longest route, though not
     *       guaranteed to be it). You can walk from B to C and back again.
     * </ol>
     */
    public static Optional<Route> longestRoute(ArrayBlockView world, EntityProfile profile,
            boolean diagonal) {
        Graph g = Graph.build(world, new MoveValidator(world, profile), diagonal);
        Area area = findMainArea(g);
        if (area == null) {
            return Optional.empty();
        }
        int b = farthest(g, area.reach(), area.seed(), area.roundTrip());
        double[] fromB = new double[g.count];
        g.costsFrom(b, fromB);
        int c = farthest(g, fromB, b, area.roundTrip());
        return Optional.of(new Route(Pos.toPoint(g.cells[b]), Pos.toPoint(g.cells[c]), fromB[c]));
    }

    /**
     * The cells of the world's largest round-trip area, as {@link #longestRoute} finds it: any
     * of them can walk to any other and back. Sorted, so picks from it are repeatable.
     */
    public static List<BlockPoint> mainArea(ArrayBlockView world, EntityProfile profile,
            boolean diagonal) {
        Graph g = Graph.build(world, new MoveValidator(world, profile), diagonal);
        Area area = findMainArea(g);
        if (area == null) {
            return List.of();
        }
        long[] cells = new long[area.size()];
        int n = 0;
        for (int i = nextSet(area.roundTrip(), 0); i >= 0; i = nextSet(area.roundTrip(), i + 1)) {
            cells[n++] = g.cells[i];
        }
        Arrays.sort(cells);
        return Arrays.stream(cells).mapToObj(Pos::toPoint).toList();
    }

    /** A seed (a node), its costs to every node, and its round-trip area as a bitset of nodes. */
    private record Area(int seed, double[] reach, long[] roundTrip, int size) {}

    private static Area findMainArea(Graph g) {
        if (g.standable == 0) {
            return null;
        }
        // Seeds spread evenly through the standable cells (which are ordered by column). Drops
        // are one-way, so what matters is each seed's round-trip area: the cells it can reach
        // AND get back from. (A ledge above a pit reaches a lot, but little of it comes back.)
        // Keep the seed with the biggest one, skipping seeds inside an area already measured.
        Area best = null;
        long[] measured = new long[words(g.count)];
        double[] reach = new double[g.count];
        long[] roundTrip = new long[words(g.count)];
        int[] queue = new int[g.count];
        int seeds = Math.min(SEEDS, g.standable);
        for (int i = 0; i < seeds; i++) {
            int seed = (int) ((long) i * g.standable / seeds);
            if (has(measured, seed)) {
                continue;
            }
            g.costsFrom(seed, reach);
            Arrays.fill(roundTrip, 0);
            int size = g.canReturn(reach, seed, roundTrip, queue);
            for (int w = 0; w < measured.length; w++) {
                measured[w] |= roundTrip[w];
            }
            if (best == null || size > best.size()) {
                // Keep this seed's arrays and measure the next seeds in the old best's.
                double[] spareReach = best == null ? new double[g.count] : best.reach();
                long[] spareTrip = best == null ? new long[words(g.count)] : best.roundTrip();
                best = new Area(seed, reach, roundTrip, size);
                reach = spareReach;
                roundTrip = spareTrip;
            }
        }
        return best;
    }

    /** How many start cells {@link #longestRoute} samples to find the largest walkable area. */
    public static final int SEEDS = 24;

    /** The node in {@code allowed} with the highest finite cost, lowest position on ties. */
    private static int farthest(Graph g, double[] costs, int fallback, long[] allowed) {
        int best = fallback;
        long bestPos = g.cells[fallback];
        double bestCost = 0;
        for (int i = nextSet(allowed, 0); i >= 0; i = nextSet(allowed, i + 1)) {
            double c = costs[i];
            if (c == Double.POSITIVE_INFINITY) {
                continue; // not reached
            }
            long pos = g.cells[i];
            if (c > bestCost || (c == bestCost && pos < bestPos)) { // stable ties
                bestCost = c;
                bestPos = pos;
                best = i;
            }
        }
        return best;
    }

    private static int words(int bits) {
        return (bits + 63) >>> 6;
    }

    private static boolean has(long[] bits, int i) {
        return (bits[i >>> 6] & (1L << i)) != 0;
    }

    private static int nextSet(long[] bits, int from) {
        int w = from >>> 6;
        if (w >= bits.length) {
            return -1;
        }
        long word = bits[w] & (-1L << from);
        while (true) {
            if (word != 0) {
                return (w << 6) + Long.numberOfTrailingZeros(word);
            }
            if (++w == bits.length) {
                return -1;
            }
            word = bits[w];
        }
    }

    /**
     * Every standable cell and its moves, worked out once and kept in flat arrays, so the
     * Dijkstra from each seed (and the walk back to it) only follows ints, instead of boxed
     * maps of positions with the moves worked out again from every seed. On the Mines (230,000
     * standable cells, 1.2 million moves) this takes a few MB.
     *
     * <p>Nodes are numbered in the order {@link #findMainArea} has always scanned the world
     * (by column: x, then z, then y up), so seeds and ties come out as before. A move can in
     * principle land on a cell that scan doesn't count as standable (or outside the world); such
     * cells get numbers after the standable ones, and their moves are worked out too.
     *
     * <p>The moves are kept both ways (compressed rows): out of each node for the Dijkstras,
     * and into each node for finding which cells can get back to a seed. A move's cost is kept
     * as an index into the few distinct costs there are, computed with
     * {@link DefaultCostModel#DEFAULT} exactly as the search computes them.
     */
    private static final class Graph {
        final int sizeX;
        final int sizeY;
        final int sizeZ;
        long[] cells = new long[1024];
        int count;
        int standable;
        /** Where each column's standable cells start in {@link #cells} (x * sizeZ + z). */
        final int[] columnStart;
        /** Cells that aren't in the scan, found as the targets of moves. Usually empty. */
        final LongIntMap extra = new LongIntMap(16);

        int[] outStart;
        int[] outTo = new int[4096];
        char[] outCost = new char[4096];
        int edges;
        double[] costTable = new double[16];
        int costCount;

        int[] inStart;
        int[] inFrom;

        // The Dijkstra's heap: nodes, ordered by their cost in the array being filled.
        private int[] heap;
        private int[] heapIndex; // where each node is in the heap, or -1
        private int heapSize;
        private double[] dist;

        private Graph(ArrayBlockView world) {
            sizeX = world.sizeX();
            sizeY = world.sizeY();
            sizeZ = world.sizeZ();
            columnStart = new int[sizeX * sizeZ + 1];
        }

        static Graph build(ArrayBlockView world, MoveValidator v, boolean diagonal) {
            Graph g = new Graph(world);
            // The world is stored a layer of y at a time, so scanning up each column read a
            // different layer every cell and missed the cache nearly every time (most of the
            // old running time on big maps). Scan in storage order, count each column's cells,
            // then put them in column order: by x, then z, then y up. The checks only read the
            // world, so the layers are scanned in parallel.
            long[][] layers = new long[g.sizeY][];
            IntStream.range(1, g.sizeY).parallel().forEach(y -> layers[y] = standableIn(g, v, y));
            int[] columnCount = new int[g.columnStart.length];
            int total = 0;
            for (int y = 1; y < g.sizeY; y++) {
                for (long p : layers[y]) {
                    columnCount[Pos.x(p) * g.sizeZ + Pos.z(p)]++;
                }
                total += layers[y].length;
            }
            int n = total;
            for (int col = 0; col < columnCount.length - 1; col++) {
                g.columnStart[col + 1] = g.columnStart[col] + columnCount[col];
            }
            g.cells = new long[Math.max(1024, n)];
            int[] next = Arrays.copyOf(g.columnStart, g.columnStart.length);
            for (int y = 1; y < g.sizeY; y++) { // bottom up, so each column fills in order of y
                for (long p : layers[y]) {
                    g.cells[next[Pos.x(p) * g.sizeZ + Pos.z(p)]++] = p;
                }
                layers[y] = null;
            }
            g.count = n;
            g.standable = n;

            CostModel costs = DefaultCostModel.DEFAULT;
            int[] starts = new int[Math.max(1024, n)];
            // count grows if a move lands on a cell outside the scan, which then gets expanded
            for (int i = 0; i < g.count; i++) {
                if (i == starts.length) {
                    starts = Arrays.copyOf(starts, i * 2);
                }
                starts[i] = g.edges;
                long from = g.cells[i];
                v.moves(Pos.x(from), Pos.y(from), Pos.z(from), diagonal,
                        (to, type) -> g.addEdge(g.node(to), costs.cost(from, to, type)));
            }
            g.outStart = Arrays.copyOf(starts, g.count + 1);
            g.outStart[g.count] = g.edges;
            g.reverse();
            g.heap = new int[g.count];
            g.heapIndex = new int[g.count];
            Arrays.fill(g.heapIndex, -1);
            return g;
        }

        /** The standable cells of layer {@code y}, in storage order (z, then x). */
        private static long[] standableIn(Graph g, MoveValidator v, int y) {
            long[] found = new long[64];
            int n = 0;
            for (int z = 0; z < g.sizeZ; z++) {
                for (int x = 0; x < g.sizeX; x++) {
                    if (v.canStand(x, y, z)) {
                        if (n == found.length) {
                            found = Arrays.copyOf(found, n * 2);
                        }
                        found[n++] = Pos.pack(x, y, z);
                    }
                }
            }
            return Arrays.copyOf(found, n);
        }

        private int add(long pos) {
            if (count == cells.length) {
                cells = Arrays.copyOf(cells, count * 2);
            }
            cells[count] = pos;
            return count++;
        }

        /** The node at {@code pos}, numbering it now if the scan didn't count it standable. */
        private int node(long pos) {
            int x = Pos.x(pos);
            int y = Pos.y(pos);
            int z = Pos.z(pos);
            if (x >= 0 && x < sizeX && z >= 0 && z < sizeZ && y >= 1 && y < sizeY) {
                // A column's cells are in order of y, and so of their packed positions.
                int col = x * sizeZ + z;
                int i = Arrays.binarySearch(cells, columnStart[col], columnStart[col + 1], pos);
                if (i >= 0) {
                    return i;
                }
            }
            int i = extra.get(pos, -1);
            if (i < 0) {
                i = add(pos);
                extra.put(pos, i);
            }
            return i;
        }

        private void addEdge(int to, double cost) {
            if (edges == outTo.length) {
                outTo = Arrays.copyOf(outTo, edges * 2);
                outCost = Arrays.copyOf(outCost, edges * 2);
            }
            outTo[edges] = to;
            outCost[edges] = costIndex(cost);
            edges++;
        }

        /** The index of {@code cost} among the distinct costs (a walk, a jump, each drop...). */
        private char costIndex(double cost) {
            long bits = Double.doubleToLongBits(cost);
            for (int i = 0; i < costCount; i++) {
                if (Double.doubleToLongBits(costTable[i]) == bits) {
                    return (char) i;
                }
            }
            if (costCount == Character.MAX_VALUE) {
                throw new IllegalStateException("too many distinct move costs");
            }
            if (costCount == costTable.length) {
                costTable = Arrays.copyOf(costTable, costCount * 2);
            }
            costTable[costCount] = cost;
            return (char) costCount++;
        }

        /** The moves into each node, from the moves out. */
        private void reverse() {
            inStart = new int[count + 1];
            for (int e = 0; e < edges; e++) {
                inStart[outTo[e] + 1]++;
            }
            for (int i = 0; i < count; i++) {
                inStart[i + 1] += inStart[i];
            }
            inFrom = new int[edges];
            int[] next = Arrays.copyOf(inStart, count);
            for (int from = 0; from < count; from++) {
                for (int e = outStart[from]; e < outStart[from + 1]; e++) {
                    inFrom[next[outTo[e]]++] = from;
                }
            }
        }

        /**
         * Fills {@code out} with the cheapest cost from {@code source} to every node, infinite
         * where it can't reach (Dijkstra). The same sums as {@link Reachability#costsFrom}: each
         * cost is the settled cost plus the move's, so the results are identical.
         */
        void costsFrom(int source, double[] out) {
            Arrays.fill(out, Double.POSITIVE_INFINITY);
            dist = out;
            out[source] = 0;
            push(source);
            while (heapSize > 0) {
                int u = pop();
                double d = out[u];
                for (int e = outStart[u]; e < outStart[u + 1]; e++) {
                    int to = outTo[e];
                    double nd = d + costTable[outCost[e]];
                    if (nd < out[to]) {
                        out[to] = nd;
                        if (heapIndex[to] < 0) {
                            push(to);
                        } else {
                            siftUp(heapIndex[to]);
                        }
                    }
                }
            }
            dist = null;
        }

        /**
         * Marks in {@code back} the nodes that {@code reach} reached (a finite cost) and that
         * can get back to {@code target}, walking the moves backwards from it. Returns how many.
         */
        int canReturn(double[] reach, int target, long[] back, int[] queue) {
            int head = 0;
            int tail = 0;
            back[target >>> 6] |= 1L << target;
            queue[tail++] = target;
            while (head < tail) {
                int to = queue[head++];
                for (int e = inStart[to]; e < inStart[to + 1]; e++) {
                    int prev = inFrom[e];
                    if (reach[prev] != Double.POSITIVE_INFINITY && !has(back, prev)) {
                        back[prev >>> 6] |= 1L << prev;
                        queue[tail++] = prev;
                    }
                }
            }
            return tail;
        }

        private void push(int node) {
            heap[heapSize] = node;
            heapIndex[node] = heapSize;
            siftUp(heapSize++);
        }

        private int pop() {
            int top = heap[0];
            heapIndex[top] = -1;
            int last = heap[--heapSize];
            if (heapSize > 0) {
                heap[0] = last;
                heapIndex[last] = 0;
                siftDown(0);
            }
            return top;
        }

        private void siftUp(int i) {
            int node = heap[i];
            double d = dist[node];
            while (i > 0) {
                int parent = (i - 1) >>> 1;
                int p = heap[parent];
                if (dist[p] <= d) {
                    break;
                }
                heap[i] = p;
                heapIndex[p] = i;
                i = parent;
            }
            heap[i] = node;
            heapIndex[node] = i;
        }

        private void siftDown(int i) {
            int node = heap[i];
            double d = dist[node];
            while (true) {
                int child = 2 * i + 1;
                if (child >= heapSize) {
                    break;
                }
                if (child + 1 < heapSize && dist[heap[child + 1]] < dist[heap[child]]) {
                    child++;
                }
                int c = heap[child];
                if (dist[c] >= d) {
                    break;
                }
                heap[i] = c;
                heapIndex[c] = i;
                i = child;
            }
            heap[i] = node;
            heapIndex[node] = i;
        }
    }
}
