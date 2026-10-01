package astar.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The array-based {@link AStarSearch} must behave exactly like the original object-per-node
 * {@link ReferenceAStarSearch}: same results, same listener events in the same order, same
 * partial paths, step for step.
 */
class EquivalenceTest {

    /** Records every listener event as a comparable value. */
    private static final class Events implements SearchListener {
        final List<Object> log = new ArrayList<>();

        @Override
        public void onStart(NodeView start, BlockPoint goal) {
            log.add(List.of("start", start, goal));
        }

        @Override
        public void onExpand(NodeView node) {
            log.add(List.of("expand", node));
        }

        @Override
        public void onOpen(NodeView node, double stepCost) {
            log.add(List.of("open", node, stepCost));
        }

        @Override
        public void onImprove(NodeView node, double oldG) {
            log.add(List.of("improve", node, oldG));
        }

        @Override
        public void onFinish(SearchResult result) {
            log.add(List.of("finish", result));
        }
    }

    /**
     * A random directed graph in a small box: each position has a few edges to nearby
     * positions (up, down or level) with random move types. Deterministic per seed.
     */
    private static MoveSource randomGraph(long seed) {
        MoveType[] types = MoveType.values();
        return (from, sink) -> {
            Random r = new Random(seed * 31 + from);
            int n = r.nextInt(6);
            for (int i = 0; i < n; i++) {
                int x = Math.floorMod(Pos.x(from) + r.nextInt(5) - 2, 12);
                int y = Math.floorMod(Pos.y(from) + r.nextInt(4) - 2, 5);
                int z = Math.floorMod(Pos.z(from) + r.nextInt(5) - 2, 12);
                sink.accept(Pos.pack(x, y, z), types[r.nextInt(types.length)]);
            }
        };
    }

    /** Integer-ish costs make lots of f and h ties, which is where tie-breaking shows. */
    private static final CostModel TIED_COSTS = (from, to, type) ->
            1 + Math.abs(Pos.x(from) - Pos.x(to)) + (type.ordinal() % 2);
    private static final CostModel ODD_COSTS = (from, to, type) ->
            1 + ((from * 7 + to * 13 + type.ordinal()) & 0xF) / 4.0;

    private static void assertSameSearch(BlockPoint start, BlockPoint goal, MoveSource moves,
            CostModel costs, Heuristic h, String label) {
        Events a = new Events();
        Events b = new Events();
        AStarSearch fast = new AStarSearch(start, goal, moves, costs, h, a);
        ReferenceAStarSearch ref = new ReferenceAStarSearch(start, goal, moves, costs, h, b);
        // Step both with the same uneven budgets, comparing as they go.
        int[] budgets = {1, 3, 7, 2, 50};
        int i = 0;
        while (fast.status() == SearchResult.Status.RUNNING
                || ref.status() == SearchResult.Status.RUNNING) {
            int n = budgets[i++ % budgets.length];
            assertEquals(ref.run(n), fast.run(n), label);
            assertEquals(ref.expanded(), fast.expanded(), label);
            assertEquals(ref.bestSoFar(), fast.bestSoFar(), label + " bestSoFar");
        }
        assertEquals(ref.result(), fast.result(), label + " result");
        assertEquals(ref.result().open(), fast.result().open(), label + " open set");
        assertEquals(new ArrayList<>(ref.result().closed()), new ArrayList<>(fast.result().closed()),
                label + " closed set, in expansion order");
        assertEquals(b.log, a.log, label + " listener events");
    }

    @Test
    void matchesTheReferenceOnRandomGraphs() {
        for (int seed = 0; seed < 400; seed++) {
            Random r = new Random(seed);
            BlockPoint start = new BlockPoint(r.nextInt(12), r.nextInt(5), r.nextInt(12));
            BlockPoint goal = new BlockPoint(r.nextInt(12), r.nextInt(5), r.nextInt(12));
            Heuristic h = switch (seed % 3) {
                case 0 -> Heuristics.ZERO;
                case 1 -> Heuristics.MANHATTAN_XZ;
                default -> Heuristics.OCTILE_XZ;
            };
            assertSameSearch(start, goal, randomGraph(seed), seed % 2 == 0 ? TIED_COSTS : ODD_COSTS,
                    h, "seed " + seed);
        }
    }

    @Test
    void matchesTheReferenceOnRandomGrids() {
        Random rng = new Random(99);
        for (int trial = 0; trial < 200; trial++) {
            TestGrid grid = TestGrid.random(rng, trial % 2 == 0);
            assertSameSearch(new BlockPoint(0, 0, 0), grid.corner(), grid, DefaultCostModel.DEFAULT,
                    grid.diagonal ? Heuristics.OCTILE_XZ : Heuristics.MANHATTAN_XZ, "grid " + trial);
        }
    }

    @Test
    void handlesSearchesBiggerThanItsInitialArrays() {
        // An open 150 x 150 grid with Dijkstra: tens of thousands of nodes, many resizes.
        TestGrid grid = new TestGrid(150, 150, java.util.Set.of(), true);
        assertSameSearch(new BlockPoint(0, 0, 0), new BlockPoint(149, 0, 149), grid,
                DefaultCostModel.DEFAULT, Heuristics.ZERO, "big grid");
        SearchResult r = AStar.findPath(new BlockPoint(0, 0, 0), new BlockPoint(149, 0, 149), grid,
                DefaultCostModel.DEFAULT, Heuristics.ZERO);
        assertEquals(149 * Math.sqrt(2), r.cost(), 1e-9);
    }
}
