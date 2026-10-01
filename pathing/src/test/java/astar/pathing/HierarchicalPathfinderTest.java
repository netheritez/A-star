package astar.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import astar.core.AStarSearch;
import astar.core.BlockPoint;
import astar.core.CostModel;
import astar.core.MoveSource;
import astar.core.MoveType;
import astar.core.PathStep;
import astar.core.Pos;
import astar.core.SearchResult;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Hierarchical A* against the plain search on random 3D worlds (water, ladders, doors and
 * terrain included): it must find a path exactly when the plain search does, every step must
 * be a real move, and it may cost more than the cheapest path but never less. These tests
 * turn off the plain search for short routes, except the ones about it, so the hierarchy
 * itself is what's tested.
 */
class HierarchicalPathfinderTest {
    private static final double EPS = 1e-9;

    @Test
    void findsAPathExactlyWhenPlainAStarDoes() {
        Random rng = new Random(7);
        int compared = 0;
        int found = 0;
        int cheapest = 0;
        for (int trial = 0; trial < 300; trial++) {
            boolean diagonal = trial % 2 == 0;
            int clusterSize = 2 + rng.nextInt(5);
            ArrayBlockView world = TestWorlds.random(rng, 4 + rng.nextInt(20), 4 + rng.nextInt(20));
            WorldPathfinder finder = new WorldPathfinder(world, EntityProfile.DEFAULT, diagonal);
            HierarchicalPathfinder hpa = HierarchicalPathfinder.build(finder, world, clusterSize)
                .shortRoutes(0, 0);
            List<BlockPoint> cells = standable(world, finder.validator());
            if (cells.size() < 2) {
                continue;
            }
            for (int q = 0; q < 8; q++) {
                BlockPoint start = cells.get(rng.nextInt(cells.size()));
                BlockPoint goal = cells.get(rng.nextInt(cells.size()));
                SearchResult plain = finder.find(start, goal);
                SearchResult h = hpa.find(start, goal);
                String where = "trial " + trial + ", " + start + " -> " + goal + ", clusters of "
                        + clusterSize + (diagonal ? ", 8-way" : ", 4-way");
                compared++;
                assertEquals(plain.found(), h.found(), "reachability, " + where);
                if (!h.found()) {
                    continue;
                }
                found++;
                assertValidPath(finder, h, start, goal, where);
                assertTrue(h.cost() >= plain.cost() - EPS,
                        "cheaper than the cheapest path, " + where);
                if (h.cost() <= plain.cost() + EPS) {
                    cheapest++;
                }
            }
        }
        assertTrue(compared > 1500 && found > 500, "compared " + compared + ", found " + found);
        // Near-optimal, not optimal: most small routes still come out cheapest.
        assertTrue(cheapest > found / 2, "cheapest " + cheapest + " of " + found);
    }

    @Test
    void graphEdgesAreRealMovesAndExactCosts() {
        Random rng = new Random(11);
        int inter = 0;
        int inside = 0;
        for (int trial = 0; trial < 60; trial++) {
            boolean diagonal = trial % 2 == 0;
            ArrayBlockView world = TestWorlds.random(rng, 6 + rng.nextInt(16), 6 + rng.nextInt(16));
            WorldPathfinder finder = new WorldPathfinder(world, EntityProfile.DEFAULT, diagonal);
            ClusterLayout layout = ClusterLayout.of(world, 3 + rng.nextInt(4));
            PortalGraph g = PortalGraph.build(layout, finder.moves(), finder.costs());
            for (int p = 0; p < g.portalCount(); p++) {
                long from = g.position(p);
                Map<Long, MoveType> real = moves(finder.moves(), from);
                for (int e = g.edgeStart[p]; e < g.edgeStart[p + 1]; e++) {
                    long to = g.position(g.edgeTo[e]);
                    if (g.edgeType[e] != PortalGraph.INSIDE) {
                        inter++;
                        MoveType type = MoveType.values()[g.edgeType[e]];
                        assertEquals(type, real.get(to), "move " + Pos.toString(from) + " -> "
                                + Pos.toString(to));
                        assertTrue(layout.clusterOf(from) != layout.clusterOf(to));
                        assertEquals(finder.costs().cost(from, to, type), g.edgeCost[e], EPS);
                    } else {
                        inside++;
                        int c = layout.clusterOf(from);
                        assertEquals(c, layout.clusterOf(to));
                        double expected = boundedDijkstra(
                                new BoundedMoveSource(finder.moves(), layout, c), finder.costs(),
                                from, to);
                        assertEquals(expected, g.edgeCost[e], EPS, "inside cluster " + c);
                    }
                }
            }
        }
        assertTrue(inter > 200 && inside > 200, "inter " + inter + ", inside " + inside);
    }

    @Test
    void clusterDijkstraMatchesAPlainDijkstraThatStaysInside() {
        Random rng = new Random(5);
        for (int trial = 0; trial < 40; trial++) {
            ArrayBlockView world = TestWorlds.random(rng, 5 + rng.nextInt(12), 5 + rng.nextInt(12));
            WorldPathfinder finder = new WorldPathfinder(world, EntityProfile.DEFAULT, true);
            ClusterLayout layout = ClusterLayout.of(world, 4);
            ClusterDijkstra d = new ClusterDijkstra(layout, finder.moves(), finder.costs());
            List<BlockPoint> cells = standable(world, finder.validator());
            for (int q = 0; q < 5 && !cells.isEmpty(); q++) {
                long from = cells.get(rng.nextInt(cells.size())).pack();
                int c = layout.clusterOf(from);
                Map<Long, Double> expected = dijkstraAll(
                        new BoundedMoveSource(finder.moves(), layout, c), finder.costs(), from);
                d.run(from, ClusterDijkstra.NO_TARGET);
                for (BlockPoint p : cells) {
                    double want = expected.getOrDefault(p.pack(), Double.POSITIVE_INFINITY);
                    assertEquals(want, d.distance(p.pack()), EPS, "to " + p);
                }
                assertEquals(expected.size(), d.settled());
            }
        }
    }

    @Test
    void goalDijkstraMatchesOneClusterDijkstraPerSource() {
        // Inserting the goal runs one backward search in place of a ClusterDijkstra from each
        // portal: the costs must be the very same numbers, or the abstract search could break
        // ties differently and HPA's paths would change.
        Random rng = new Random(13);
        int compared = 0;
        int reached = 0;
        for (int trial = 0; trial < 120; trial++) {
            boolean diagonal = trial % 2 == 0;
            ArrayBlockView world = TestWorlds.random(rng, 4 + rng.nextInt(20), 4 + rng.nextInt(20));
            WorldPathfinder finder = new WorldPathfinder(world, EntityProfile.DEFAULT, diagonal);
            ClusterLayout layout = ClusterLayout.of(world, 2 + rng.nextInt(7));
            PortalGraph graph = PortalGraph.build(layout, finder.moves(), finder.costs());
            ClusterDijkstra forward = new ClusterDijkstra(layout, finder.moves(), finder.costs());
            GoalDijkstra backward = new GoalDijkstra(layout, finder.moves(), finder.costs());
            List<BlockPoint> cells = standable(world, finder.validator());
            for (int q = 0; q < 10 && !cells.isEmpty(); q++) {
                long goal = cells.get(rng.nextInt(cells.size())).pack();
                int c = layout.clusterOf(goal);
                // The cluster's portals, as in a query, and a few more cells of the cluster.
                List<Long> sources = new ArrayList<>();
                for (int i = graph.clusterStart[c]; i < graph.clusterStart[c + 1]; i++) {
                    sources.add(graph.position(graph.clusterPortals[i]));
                }
                for (int k = 0; k < 3; k++) {
                    long p = cells.get(rng.nextInt(cells.size())).pack();
                    if (layout.clusterOf(p) == c && !sources.contains(p)) {
                        sources.add(p);
                    }
                }
                sources.remove(goal);
                long[] from = sources.stream().mapToLong(Long::longValue).toArray();
                backward.run(goal, from, from.length);
                for (long p : from) {
                    double want = forward.run(p, goal);
                    // Same paths, summed in the other direction: equal up to rounding.
                    assertEquals(want, backward.distance(p), 1e-9, Pos.toString(p) + " -> "
                            + Pos.toString(goal) + ", trial " + trial
                            + (diagonal ? ", 8-way" : ", 4-way"));
                    compared++;
                    if (want < Double.POSITIVE_INFINITY) {
                        reached++;
                    }
                }
            }
        }
        assertTrue(compared > 3000 && reached > 1000, "compared " + compared + ", reached "
                + reached);
    }

    @Test
    void leavesTheClusterWhenThatIsCheaper() {
        // Clusters of 4: start and goal share the top-left cluster, but a wall splits it, and
        // the only way round is through the cluster below.
        ArrayBlockView world = ArrayBlockView.flat(
                "..#.....",
                "..#.....",
                "..#.....",
                "..#.....",
                "........");
        WorldPathfinder finder = new WorldPathfinder(world, EntityProfile.DEFAULT, false);
        HierarchicalPathfinder hpa = HierarchicalPathfinder.build(finder, world, 4)
                .shortRoutes(0, 0);
        BlockPoint start = new BlockPoint(0, 1, 0);
        BlockPoint goal = new BlockPoint(3, 1, 0);
        SearchResult r = hpa.find(start, goal);
        assertTrue(r.found());
        assertEquals(finder.find(start, goal).cost(), r.cost(), EPS);
        assertTrue(r.path().stream().anyMatch(s -> s.pos().z() >= 4), "went round below");
        assertValidPath(finder, r, start, goal, "wall");
    }

    @Test
    void reportsNoPathBetweenSeparateAreas() {
        ArrayBlockView world = ArrayBlockView.flat(
                "...#....",
                "...#....",
                "...#....",
                "...#....");
        WorldPathfinder finder = new WorldPathfinder(world, EntityProfile.DEFAULT, true);
        HierarchicalPathfinder hpa = HierarchicalPathfinder.build(finder, world, 2)
                .shortRoutes(0, 0);
        SearchResult r = hpa.find(new BlockPoint(0, 1, 0), new BlockPoint(7, 1, 3));
        assertFalse(r.found());
        assertEquals(Double.POSITIVE_INFINITY, r.cost());
        assertFalse(hpa.find(new BlockPoint(3, 1, 0), new BlockPoint(0, 1, 0)).found(),
                "the start is inside the wall");
    }

    @Test
    void followsEditsMadeAfterTheGraphWasBuilt() {
        ArrayBlockView world = ArrayBlockView.flat(
                "........",
                "........",
                "........");
        WorldPathfinder finder = new WorldPathfinder(world, EntityProfile.DEFAULT, false);
        HierarchicalPathfinder hpa = HierarchicalPathfinder.build(finder, world, 4)
                .shortRoutes(0, 0);
        BlockPoint start = new BlockPoint(0, 1, 0);
        BlockPoint goal = new BlockPoint(7, 1, 0);
        assertTrue(hpa.find(start, goal).found());

        // Wall off the straight way across the cluster border: the route must go round it.
        world.set(4, 1, 0, BlockType.SOLID);
        world.set(4, 2, 0, BlockType.SOLID);
        SearchResult r = hpa.find(start, goal);
        assertTrue(r.found());
        assertTrue(r.cost() >= finder.find(start, goal).cost() - EPS, "never below the cheapest");
        assertValidPath(finder, r, start, goal, "round the new wall");

        // Close the border completely, then open it again.
        for (int z = 1; z < 3; z++) {
            world.set(4, 1, z, BlockType.SOLID);
            world.set(4, 2, z, BlockType.SOLID);
        }
        assertFalse(hpa.find(start, goal).found());
        world.set(4, 1, 2, BlockType.AIR);
        world.set(4, 2, 2, BlockType.AIR);
        r = hpa.find(start, goal);
        assertTrue(r.found());
        assertValidPath(finder, r, start, goal, "through the reopened gap");
    }

    @Test
    void startEqualsGoal() {
        ArrayBlockView world = ArrayBlockView.flat("....", "....");
        WorldPathfinder finder = new WorldPathfinder(world, EntityProfile.DEFAULT, true);
        HierarchicalPathfinder hpa = HierarchicalPathfinder.build(finder, world, 2)
                .shortRoutes(0, 0);
        SearchResult r = hpa.find(new BlockPoint(1, 1, 1), new BlockPoint(1, 1, 1));
        assertTrue(r.found());
        assertEquals(1, r.path().size());
        assertEquals(0, r.cost(), EPS);
    }

    // ---- Plain A* for short routes --------------------------------------------------------------

    @Test
    void shortRoutesUsePlainAStarAndCostTheLeast() {
        Random rng = new Random(11);
        int plain = 0;
        int handedOver = 0;
        for (int trial = 0; trial < 150; trial++) {
            boolean diagonal = trial % 2 == 0;
            ArrayBlockView world = TestWorlds.random(rng, 6 + rng.nextInt(20), 6 + rng.nextInt(20));
            WorldPathfinder finder = new WorldPathfinder(world, EntityProfile.DEFAULT, diagonal);
            // A small budget, so some routes run out of it and are handed to the hierarchy.
            HierarchicalPathfinder hpa = HierarchicalPathfinder.build(finder, world, 3)
                    .shortRoutes(10, 20 + rng.nextInt(60));
            List<BlockPoint> cells = standable(world, finder.validator());
            if (cells.size() < 2) {
                continue;
            }
            for (int q = 0; q < 8; q++) {
                BlockPoint start = cells.get(rng.nextInt(cells.size()));
                BlockPoint goal = cells.get(rng.nextInt(cells.size()));
                SearchResult best = finder.find(start, goal);
                SearchResult h = hpa.find(start, goal);
                HierarchicalPathfinder.QueryStats stats = hpa.lastQuery();
                String where = "trial " + trial + ", " + start + " -> " + goal;
                assertEquals(best.found(), h.found(), "reachability, " + where);
                boolean close = finder.heuristic().between(start, goal) <= 10;
                if (!close) {
                    assertEquals(0, stats.plainExpanded(), "tried plain A* when far, " + where);
                }
                if (stats.plain()) {
                    assertTrue(close, "plain A* when far, " + where);
                    assertTrue(stats.plainExpanded() <= hpa.shortBudget(), "over budget, " + where);
                    assertEquals(best.cost(), h.cost(), EPS, "plain A* is the cheapest, " + where);
                    plain++;
                } else if (close) {
                    assertTrue(stats.plainExpanded() > 0
                            && stats.plainExpanded() <= hpa.shortBudget(),
                            "plain A* tried within budget, " + where);
                    handedOver++;
                }
                if (h.found()) {
                    assertValidPath(finder, h, start, goal, where);
                    assertTrue(h.cost() >= best.cost() - EPS, "cheaper than the cheapest, " + where);
                    assertEquals(stats.total(), h.expanded(), "expanded counts every stage, " + where);
                }
            }
        }
        assertTrue(plain > 100 && handedOver > 20, "plain " + plain + ", handed over " + handedOver);
    }

    @Test
    void aZeroRangeAlwaysUsesTheHierarchy() {
        ArrayBlockView world = ArrayBlockView.flat("........", "........", "........", "........");
        WorldPathfinder finder = new WorldPathfinder(world, EntityProfile.DEFAULT, true);
        HierarchicalPathfinder hpa = HierarchicalPathfinder.build(finder, world, 2);
        assertEquals(HierarchicalPathfinder.defaultShortRange(2), hpa.shortRange());
        assertTrue(hpa.find(new BlockPoint(0, 1, 0), new BlockPoint(5, 1, 3)).found());
        assertTrue(hpa.lastQuery().plain(), "defaults: within 4 clusters, so plain A*");
        hpa.shortRoutes(0, 1000);
        assertTrue(hpa.find(new BlockPoint(0, 1, 0), new BlockPoint(5, 1, 3)).found());
        assertEquals(0, hpa.lastQuery().plainExpanded());
        assertTrue(hpa.lastQuery().insertSettled() > 0);
    }

    // ---- Helpers --------------------------------------------------------------------------------

    private static void assertValidPath(WorldPathfinder finder, SearchResult r, BlockPoint start,
            BlockPoint goal, String where) {
        List<PathStep> path = r.path();
        assertEquals(start, path.get(0).pos(), "starts at the start, " + where);
        assertEquals(goal, path.get(path.size() - 1).pos(), "ends at the goal, " + where);
        double cost = 0;
        for (int i = 1; i < path.size(); i++) {
            long from = path.get(i - 1).pos().pack();
            long to = path.get(i).pos().pack();
            MoveType type = moves(finder.moves(), from).get(to);
            assertNotNull(type, "not a move: " + path.get(i - 1).pos() + " -> "
                    + path.get(i).pos() + ", " + where);
            assertEquals(type, path.get(i).via(), "move type, " + where);
            cost += finder.costs().cost(from, to, type);
        }
        assertEquals(cost, r.cost(), 1e-6, "cost adds up, " + where);
    }

    private static Map<Long, MoveType> moves(MoveSource moves, long from) {
        Map<Long, MoveType> out = new HashMap<>();
        moves.moves(from, out::put);
        return out;
    }

    private static List<BlockPoint> standable(ArrayBlockView world, MoveValidator v) {
        List<BlockPoint> out = new ArrayList<>();
        for (int x = 0; x < world.sizeX(); x++) {
            for (int z = 0; z < world.sizeZ(); z++) {
                for (int y = 0; y < world.sizeY(); y++) {
                    if (v.canStand(x, y, z)) {
                        out.add(new BlockPoint(x, y, z));
                    }
                }
            }
        }
        return out;
    }

    private static double boundedDijkstra(MoveSource moves, CostModel costs, long from, long to) {
        return dijkstraAll(moves, costs, from).getOrDefault(to, Double.POSITIVE_INFINITY);
    }

    private static Map<Long, Double> dijkstraAll(MoveSource moves, CostModel costs, long from) {
        record Item(double d, long pos) {}
        Map<Long, Double> dist = new HashMap<>();
        dist.put(from, 0.0);
        PriorityQueue<Item> heap = new PriorityQueue<>((a, b) -> Double.compare(a.d(), b.d()));
        heap.add(new Item(0, from));
        java.util.Set<Long> done = new java.util.HashSet<>();
        while (!heap.isEmpty()) {
            Item cur = heap.poll();
            if (!done.add(cur.pos())) {
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

    /** Keeps the refinement honest: a bounded search never steps outside its cluster. */
    @Test
    void boundedSearchesStayInTheirCluster() {
        Random rng = new Random(3);
        for (int trial = 0; trial < 30; trial++) {
            ArrayBlockView world = TestWorlds.random(rng, 8 + rng.nextInt(10), 8 + rng.nextInt(10));
            WorldPathfinder finder = new WorldPathfinder(world, EntityProfile.DEFAULT, true);
            ClusterLayout layout = ClusterLayout.of(world, 4);
            List<BlockPoint> cells = standable(world, finder.validator());
            for (int q = 0; q < 5 && cells.size() > 1; q++) {
                BlockPoint a = cells.get(rng.nextInt(cells.size()));
                BlockPoint b = cells.get(rng.nextInt(cells.size()));
                int c = layout.clusterOf(a.pack());
                AStarSearch s = new AStarSearch(a, b, new BoundedMoveSource(finder.moves(), layout, c),
                        finder.costs(), finder.heuristic());
                s.runToEnd();
                for (BlockPoint p : s.result().closed()) {
                    assertEquals(c, layout.clusterOf(p.pack()), "left cluster " + c + " at " + p);
                }
            }
        }
    }
}
