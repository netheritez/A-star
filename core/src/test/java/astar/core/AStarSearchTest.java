package astar.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import astar.core.SearchResult.Status;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AStarSearchTest {
    private static final double EPS = 1e-9;
    private static final CostModel COSTS = DefaultCostModel.DEFAULT;
    private static final BlockPoint ORIGIN = new BlockPoint(0, 0, 0);

    private static BlockPoint p(int x, int z) {
        return new BlockPoint(x, 0, z);
    }

    /** Reference answer, written independently of AStarSearch: plain Dijkstra. */
    private static double dijkstraCost(MoveSource moves, BlockPoint start, BlockPoint goal) {
        record Item(double d, long pos) {}
        Map<Long, Double> dist = new HashMap<>();
        dist.put(start.pack(), 0.0);
        PriorityQueue<Item> heap = new PriorityQueue<>((a, b) -> Double.compare(a.d(), b.d()));
        heap.add(new Item(0, start.pack()));
        while (!heap.isEmpty()) {
            Item cur = heap.poll();
            if (cur.pos() == goal.pack()) {
                return cur.d();
            }
            if (cur.d() > dist.get(cur.pos())) {
                continue;
            }
            moves.moves(cur.pos(), (to, type) -> {
                double nd = cur.d() + COSTS.cost(cur.pos(), to, type);
                if (nd < dist.getOrDefault(to, Double.POSITIVE_INFINITY)) {
                    dist.put(to, nd);
                    heap.add(new Item(nd, to));
                }
            });
        }
        return Double.POSITIVE_INFINITY;
    }

    private static Heuristic heuristicFor(TestGrid grid) {
        return grid.diagonal ? Heuristics.OCTILE_XZ : Heuristics.MANHATTAN_XZ;
    }

    @Test
    void heuristicValues() {
        assertEquals(7, Heuristics.MANHATTAN_XZ.estimate(3, 9, 4), EPS);
        assertEquals(1 + 3 * Math.sqrt(2), Heuristics.OCTILE_XZ.estimate(3, 9, 4), EPS);
        assertEquals(0, Heuristics.ZERO.estimate(3, 9, 4), EPS);
        assertEquals(7, Heuristics.MANHATTAN_XZ.between(p(0, 0), new BlockPoint(-3, 5, 4)), EPS);
    }

    @Test
    void defaultCostModelRefusesCostsOfZeroOrLess() {
        assertThrows(IllegalArgumentException.class, () -> new DefaultCostModel(0, 2, 2, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new DefaultCostModel(1, -1, 2, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new DefaultCostModel(1, 1, 2, 1, -1));
        assertEquals(2.5, COSTS.cost(Pos.pack(0, 5, 0), Pos.pack(1, 2, 0), MoveType.DROP), EPS);
    }

    @Test
    void startIsGoal() {
        TestGrid grid = TestGrid.of(false, "...");
        SearchResult r = AStar.findPath(p(1, 0), p(1, 0), grid, COSTS, Heuristics.MANHATTAN_XZ);
        assertEquals(Status.FOUND, r.status());
        assertEquals(List.of(new PathStep(p(1, 0), null)), r.path());
        assertEquals(0, r.cost(), EPS);
    }

    @Test
    void straightLineRecordsMoveTypes() {
        TestGrid grid = TestGrid.of(false, ".....");
        SearchResult r = AStar.findPath(p(0, 0), p(4, 0), grid, COSTS, Heuristics.MANHATTAN_XZ);
        assertEquals(List.of(p(0, 0), p(1, 0), p(2, 0), p(3, 0), p(4, 0)), r.positions());
        assertEquals(null, r.path().get(0).via());
        assertEquals(MoveType.WALK, r.path().get(4).via());
        assertEquals(4, r.cost(), EPS);
    }

    @Test
    void unreachableReportsNoPath() {
        TestGrid grid = TestGrid.of(false,
                ".#.",
                ".#.",
                ".#.");
        SearchResult r = AStar.findPath(p(0, 0), p(2, 0), grid, COSTS, Heuristics.MANHATTAN_XZ);
        assertEquals(Status.NO_PATH, r.status());
        assertTrue(r.path().isEmpty());
        assertEquals(Set.of(p(0, 0), p(0, 1), p(0, 2)), r.closed());
    }

    @Test
    void matchesDijkstraOnRandomGrids() {
        Random rng = new Random(1234);
        for (int trial = 0; trial < 300; trial++) {
            TestGrid grid = TestGrid.random(rng, trial % 2 == 1);
            BlockPoint goal = grid.corner();
            SearchResult r = AStar.findPath(ORIGIN, goal, grid, COSTS, heuristicFor(grid));
            double expected = dijkstraCost(grid, ORIGIN, goal);

            assertEquals(Double.isFinite(expected), r.found(), "reachability, trial " + trial);
            if (r.found()) {
                assertEquals(ORIGIN, r.path().get(0).pos());
                assertEquals(goal, r.path().get(r.path().size() - 1).pos());
                assertEquals(expected, r.cost(), EPS, "cost, trial " + trial);
            }
        }
    }

    @Test
    void steppingGivesTheSameResultAsFindPath() {
        Random rng = new Random(55);
        for (int trial = 0; trial < 50; trial++) {
            TestGrid grid = TestGrid.random(rng, true);
            SearchResult oneShot =
                    AStar.findPath(ORIGIN, grid.corner(), grid, COSTS, Heuristics.OCTILE_XZ);

            AStarSearch s = new AStarSearch(ORIGIN, grid.corner(), grid, COSTS, Heuristics.OCTILE_XZ);
            int steps = 0;
            while (s.step() == Status.RUNNING) {
                steps++;
                assertEquals(Status.RUNNING, s.result().status());
                assertTrue(s.result().path().isEmpty());
            }
            assertEquals(oneShot, s.result(), "trial " + trial);
            assertTrue(steps >= s.expanded());
            assertEquals(s.status(), s.step(), "step after finishing is a no-op");
        }
    }

    @Test
    void runNeverExceedsItsBudget() {
        TestGrid grid = TestGrid.of(true,
                "..........",
                "..######..",
                "........#.",
                ".######.#.",
                "......#...",
                ".####.###.",
                "..........");
        AStarSearch s = new AStarSearch(ORIGIN, p(9, 6), grid, COSTS, Heuristics.OCTILE_XZ);
        int before = 0;
        while (s.run(3) == Status.RUNNING) {
            assertTrue(s.expanded() - before <= 3);
            before = s.expanded();
        }
        assertEquals(Status.FOUND, s.status());
        assertEquals(14 - 1 + Math.sqrt(2), s.result().cost(), EPS);
    }

    @Test
    void bestSoFarHeadsTowardTheGoal() {
        TestGrid grid = TestGrid.of(false,
                ".....#.",
                ".....#.",
                ".....#.");
        AStarSearch s = new AStarSearch(ORIGIN, p(6, 0), grid, COSTS, Heuristics.MANHATTAN_XZ);
        s.runToEnd();
        assertEquals(Status.NO_PATH, s.status());
        List<PathStep> best = s.bestSoFar();
        assertEquals(ORIGIN, best.get(0).pos());
        assertEquals(p(4, 0), best.get(best.size() - 1).pos()); // closest reachable cell
    }

    /** Records every event, and checks the invariants as they arrive. */
    private static final class CheckingListener implements SearchListener {
        final Set<BlockPoint> expanded = new HashSet<>();
        final Map<BlockPoint, Double> bestG = new HashMap<>();
        final List<String> order = new ArrayList<>();
        int expands;
        int starts;
        int finishes;

        @Override
        public void onStart(NodeView start, BlockPoint goal) {
            starts++;
            order.add("start");
            bestG.put(start.pos(), start.g());
        }

        @Override
        public void onExpand(NodeView node) {
            expands++;
            assertTrue(expanded.add(node.pos()), "expanded twice: " + node.pos());
            assertEquals(node.g() + node.h(), node.f(), EPS);
        }

        @Override
        public void onOpen(NodeView node, double stepCost) {
            assertFalse(bestG.containsKey(node.pos()), "opened twice: " + node.pos());
            assertFalse(expanded.contains(node.pos()));
            assertTrue(stepCost >= 1);
            bestG.put(node.pos(), node.g());
        }

        @Override
        public void onImprove(NodeView node, double oldG) {
            assertTrue(bestG.containsKey(node.pos()), "improved before opened: " + node.pos());
            assertEquals(bestG.get(node.pos()), oldG, EPS);
            assertTrue(node.g() < oldG, "not strictly cheaper");
            bestG.put(node.pos(), node.g());
        }

        @Override
        public void onFinish(SearchResult result) {
            finishes++;
            order.add("finish");
        }
    }

    @Test
    void listenerSeesConsistentEvents() {
        Random rng = new Random(77);
        for (int trial = 0; trial < 100; trial++) {
            TestGrid grid = TestGrid.random(rng, trial % 2 == 0);
            CheckingListener events = new CheckingListener();
            SearchResult watched = AStar.findPath(
                    ORIGIN, grid.corner(), grid, COSTS, heuristicFor(grid), events);
            SearchResult plain = AStar.findPath(ORIGIN, grid.corner(), grid, COSTS, heuristicFor(grid));

            assertEquals(plain, watched, "listener changed the result, trial " + trial);
            assertEquals(watched.expanded(), events.expands);
            assertEquals(watched.closed(), events.expanded);
            assertEquals(1, events.starts);
            assertEquals(1, events.finishes);
            assertEquals(List.of("start", "finish"), events.order);
        }
    }

    @Test
    void cheaperPathImprovesANodeInPlace() {
        // S -> C costs 5, but S -> A -> C costs 2, so C is opened at g = 5, then improved to 2.
        long s0 = Pos.pack(0, 0, 0), a = Pos.pack(1, 0, 0), c = Pos.pack(2, 0, 0);
        MoveSource graph = (from, sink) -> {
            if (from == s0) {
                sink.accept(a, MoveType.WALK);
                sink.accept(c, MoveType.JUMP_UP);
            } else if (from == a) {
                sink.accept(c, MoveType.WALK);
            }
        };
        CostModel costs = (from, to, type) -> type == MoveType.JUMP_UP ? 5 : 1;
        List<Double> improvedFrom = new ArrayList<>();
        SearchResult r = AStar.findPath(ORIGIN, Pos.toPoint(c), graph, costs, Heuristics.ZERO,
                new SearchListener() {
                    @Override
                    public void onImprove(NodeView node, double oldG) {
                        improvedFrom.add(oldG);
                        assertEquals(2, node.g(), EPS);
                        assertEquals(Pos.toPoint(a), node.parent());
                    }
                });
        assertEquals(List.of(5.0), improvedFrom);
        assertEquals(List.of(ORIGIN, Pos.toPoint(a), Pos.toPoint(c)), r.positions());
        assertEquals(2, r.cost(), EPS);
    }
}
