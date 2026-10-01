package astar.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import astar.core.BlockPoint;
import astar.core.DefaultCostModel;
import astar.core.Heuristic;
import astar.core.SearchResult;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Searching the {@link NavGraph} gives the same result as working out moves as it goes. */
class NavGraphTest {
    private static final BlockPoint START = new BlockPoint(0, 1, 1);
    private static final BlockPoint GOAL = new BlockPoint(19, 1, 1);

    private static WorldPathfinder finder(ArrayBlockView world, int directions, Heuristic h,
            double turn, boolean exact) {
        return new WorldPathfinder(world, EntityProfile.DEFAULT, directions,
                DefaultCostModel.DEFAULT, TerrainCosts.DEFAULT, h, turn, exact);
    }

    private static void assertSame(SearchResult a, SearchResult b, String what) {
        assertEquals(a.status(), b.status(), what);
        assertEquals(a.cost(), b.cost(), 0, what);
        assertEquals(a.expanded(), b.expanded(), what);
        assertEquals(a.path(), b.path(), what);
        assertEquals(a.closed(), b.closed(), what);
        assertEquals(a.open(), b.open(), what);
    }

    /** Same nodes, same order, same path: with and without turn costs, and with landmarks. */
    @Test
    void sameSearchAsWithoutTheGraph() {
        Random rng = new Random(41);
        int compared = 0;
        for (int trial = 0; trial < 600; trial++) {
            int directions = new int[] {4, 8, 16}[trial % 3];
            double turn = new double[] {0, 0.1, 0.5}[rng.nextInt(3)];
            boolean exact = turn > 0 && rng.nextInt(3) == 0;
            ArrayBlockView world = TestWorlds.random(rng, 3 + rng.nextInt(12), 3 + rng.nextInt(12));
            Heuristic flat = DefaultCostModel.DEFAULT.heuristic(directions);
            WorldPathfinder plain = finder(world, directions, flat, turn, exact).useGraph(false);
            BlockPoint start = TestWorlds.surface(world, plain.validator(), 0, 0);
            BlockPoint goal = TestWorlds.surface(world, plain.validator(), world.sizeX() - 1,
                    world.sizeZ() - 1);
            if (start == null || goal == null) {
                continue;
            }
            WorldPathfinder graph = finder(world, directions, flat, turn, exact);
            SearchResult a = plain.find(start, goal);
            SearchResult b = graph.find(start, goal);
            assertNotNull(graph.graph(), "searched over the graph");
            String what = "trial " + trial + ", " + directions + "-way, turn " + turn
                    + (exact ? " exact" : "");
            assertSame(a, b, what);
            // The way back, over the same graph when it covers the goal.
            assertSame(plain.find(goal, start), graph.find(goal, start), what + ", back");

            Landmarks lm = Landmarks.build(graph, start.pack(), 3);
            Heuristic alt = lm.heuristic(flat);
            assertSame(plain.withHeuristic(alt).find(start, goal),
                    graph.withHeuristic(alt).find(start, goal), what + ", landmarks");
            if (a.found()) {
                compared++;
            }
        }
        assertTrue(compared > 100, "compared " + compared);
    }

    /** Every move the move source lists is in the graph, at the cost model's price. */
    @Test
    void holdsEveryMoveAtItsPrice() {
        Random rng = new Random(42);
        for (int trial = 0; trial < 40; trial++) {
            ArrayBlockView world = TestWorlds.random(rng, 4 + rng.nextInt(8), 4 + rng.nextInt(8));
            WorldPathfinder f = finder(world, 8, DefaultCostModel.DEFAULT.heuristic(8), 0, false);
            BlockPoint seed = TestWorlds.surface(world, f.validator(), 0, 0);
            if (seed == null) {
                continue;
            }
            NavGraph g = NavGraph.build(f, seed.pack());
            long[] pos = g.positions();
            for (int c = 0; c < g.cells(); c++) {
                long a = pos[c];
                int[] at = {g.moveStart()[c]};
                f.moves().moves(a, (b, type) -> {
                    int e = at[0]++;
                    assertEquals(b, pos[g.moveTo()[e]]);
                    assertEquals(type, g.type(e));
                    assertEquals(f.costs().cost(a, b, type), g.moveCost()[e], 0);
                });
                assertEquals(g.moveStart()[c + 1], at[0], "no other moves");
                assertEquals(c, g.cell(a));
            }
        }
    }

    /** A floor 20 x 9 with a wall across it, open at one end. */
    private static ArrayBlockView walled() {
        ArrayBlockView w = new ArrayBlockView(20, 4, 9);
        w.fill(0, 0, 0, 19, 0, 8, BlockType.SOLID);
        w.fill(10, 1, 0, 10, 2, 7, BlockType.SOLID);
        return w;
    }

    @Test
    void builtOnceAndPatchedAfterAnEdit() {
        ArrayBlockView w = walled();
        Heuristic flat = DefaultCostModel.DEFAULT.heuristic(8);
        WorldPathfinder f = finder(w, 8, flat, 0.1, false);
        WorldPathfinder plain = finder(w, 8, flat, 0.1, false).useGraph(false);
        assertNull(f.graph());
        assertSame(plain.find(START, GOAL), f.find(START, GOAL), "first");
        NavGraph first = f.graph();
        assertNotNull(first);
        f.find(START, GOAL);
        f.find(GOAL, START);
        assertEquals(1, f.graphBuilds(), "kept between searches");

        // Open a gap in the middle of the wall: the old moves are wrong now.
        w.fill(10, 1, 4, 10, 2, 4, BlockType.AIR);
        assertNull(f.graph(), "stale after the edit");
        assertSame(plain.find(START, GOAL), f.find(START, GOAL), "after the edit");
        assertEquals(1, f.graphBuilds(), "patched, not built again");
        assertNotSame(first, f.graph());
        assertEquals(first.fullBuildMs(), f.graph().fullBuildMs());
    }

    /** After random edits, the patched graph searches exactly like working moves out afresh. */
    @Test
    void patchedGraphsMatchAfterRandomEdits() {
        Random rng = new Random(43);
        BlockType[] kinds = {BlockType.AIR, BlockType.SOLID, BlockType.PARTIAL_8,
            BlockType.STAIRS, BlockType.WATER, BlockType.CLIMBABLE, BlockType.SOUL_SAND,
            BlockType.DOOR, BlockType.HAZARD};
        int compared = 0;
        int patched = 0;
        for (int trial = 0; trial < 150; trial++) {
            int directions = new int[] {4, 8, 16}[trial % 3];
            double turn = trial % 2 == 0 ? 0 : 0.1;
            ArrayBlockView world = TestWorlds.random(rng, 16 + rng.nextInt(24),
                    16 + rng.nextInt(24));
            Heuristic flat = DefaultCostModel.DEFAULT.heuristic(directions);
            WorldPathfinder f = finder(world, directions, flat, turn, false);
            WorldPathfinder plain = finder(world, directions, flat, turn, false).useGraph(false);
            BlockPoint start = TestWorlds.surface(world, plain.validator(), 0, 0);
            BlockPoint goal = TestWorlds.surface(world, plain.validator(), world.sizeX() - 1,
                    world.sizeZ() - 1);
            if (start == null || goal == null) {
                continue;
            }
            f.find(start, goal);
            for (int round = 0; round < 3; round++) {
                // A few blocks changed in one small area.
                int cx = rng.nextInt(world.sizeX());
                int cz = rng.nextInt(world.sizeZ());
                for (int k = 0; k < 1 + rng.nextInt(4); k++) {
                    int x = Math.min(world.sizeX() - 1, cx + rng.nextInt(2));
                    int z = Math.min(world.sizeZ() - 1, cz + rng.nextInt(2));
                    world.set(x, rng.nextInt(world.sizeY()), z, kinds[rng.nextInt(kinds.length)]);
                }
                if (!plain.canStand(start) || !plain.canStand(goal)) {
                    break;
                }
                int builds = f.graphBuilds();
                String what = "trial " + trial + " round " + round;
                assertSame(plain.find(start, goal), f.find(start, goal), what);
                assertSame(plain.find(goal, start), f.find(goal, start), what + ", back");
                if (f.graphBuilds() == builds) {
                    patched++;
                }
                compared++;
            }
        }
        assertTrue(compared > 150, "compared " + compared);
        // Small worlds are often too small to patch (the edit reaches a quarter of the cells).
        assertTrue(patched > compared / 4, "patched " + patched + " of " + compared);
    }

    @Test
    void slowRebuildsRunInTheBackground() throws Exception {
        ArrayBlockView w = walled();
        Heuristic flat = DefaultCostModel.DEFAULT.heuristic(8);
        WorldPathfinder f = finder(w, 8, flat, 0, false);
        WorldPathfinder plain = finder(w, 8, flat, 0, false).useGraph(false);
        f.graphSyncLimit(-1);
        f.find(START, GOAL);
        assertNotNull(f.graph());

        // Too big to patch: the whole wall comes down.
        w.fill(10, 1, 0, 10, 2, 7, BlockType.AIR);
        w.fill(0, 3, 0, 19, 3, 8, BlockType.SOLID);
        assertSame(plain.find(START, GOAL), f.find(START, GOAL), "while it builds");
        long until = System.nanoTime() + 10_000_000_000L;
        while (f.buildingGraph() && System.nanoTime() < until) {
            Thread.sleep(5);
        }
        assertSame(plain.find(START, GOAL), f.find(START, GOAL), "after");
        assertNotNull(f.graph(), "the background build is in use");
        assertEquals(2, f.graphBuilds());
    }

    @Test
    void offWhenAskedAndForOtherWorlds() {
        ArrayBlockView w = walled();
        WorldPathfinder off = new WorldPathfinder(w, EntityProfile.DEFAULT, true).useGraph(false);
        off.find(START, GOAL);
        assertNull(off.graph());
        assertEquals(0, off.graphBuilds());
        BlockView view = w::blockAt;
        WorldPathfinder other = new WorldPathfinder(view, EntityProfile.DEFAULT, true);
        assertTrue(other.find(START, GOAL).found());
        assertNull(other.graph());
    }
}
