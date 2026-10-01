package astar.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import astar.core.BlockPoint;
import astar.core.CostModel;
import astar.core.DefaultCostModel;
import astar.core.MoveType;
import astar.core.PathStep;
import astar.core.SearchListener;
import astar.core.SearchResult;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import org.junit.jupiter.api.Test;

class WorldPathfinderTest {
    private static final double EPS = 1e-9;

    private static BlockPoint p(int x, int y, int z) {
        return new BlockPoint(x, y, z);
    }

    private static final String[] DEMO = {
        "..........",
        "..######..",
        "........#.",
        ".######.#.",
        "......#...",
        ".####.###.",
        "..........",
    };

    @Test
    void flatPortMatchesThe2dResults() {
        ArrayBlockView world = ArrayBlockView.flat(DEMO);
        SearchResult four = new WorldPathfinder(world, EntityProfile.DEFAULT, false)
                .find(p(0, 1, 0), p(9, 1, 6));
        SearchResult eight = new WorldPathfinder(world, EntityProfile.DEFAULT, true)
                .find(p(0, 1, 0), p(9, 1, 6));
        assertEquals(15, four.cost(), EPS);
        assertEquals(13 + Math.sqrt(2), eight.cost(), EPS);
        assertTrue(four.path().stream().allMatch(s -> s.pos().y() == 1), "stays on the floor");
    }

    @Test
    void climbsAStaircase() {
        // Floor, then steps of height 1, 2 and 3 along x.
        ArrayBlockView world = new ArrayBlockView(5, 7, 1);
        world.fill(0, 0, 0, 4, 0, 0, BlockType.SOLID);
        world.fill(1, 1, 0, 1, 1, 0, BlockType.SOLID);
        world.fill(2, 1, 0, 2, 2, 0, BlockType.SOLID);
        world.fill(3, 1, 0, 4, 3, 0, BlockType.SOLID);

        SearchResult r = new WorldPathfinder(world, EntityProfile.DEFAULT, false)
                .find(p(0, 1, 0), p(4, 4, 0));
        assertEquals(List.of(p(0, 1, 0), p(1, 2, 0), p(2, 3, 0), p(3, 4, 0), p(4, 4, 0)), r.positions());
        assertEquals(List.of(MoveType.JUMP_UP, MoveType.JUMP_UP, MoveType.JUMP_UP, MoveType.WALK),
                r.path().stream().skip(1).map(PathStep::via).toList());
        assertEquals(3 * 2 + 1, r.cost(), EPS);
    }

    @Test
    void walksAroundACliffThatsTooTall() {
        // A plateau (top at y = 4, feet at 5) along x = 0..1. Its east edge is a 4-block cliff,
        // except at z = 2 where a 1-high ledge makes it a 3-block drop.
        ArrayBlockView world = new ArrayBlockView(4, 7, 3);
        world.fill(0, 0, 0, 3, 0, 2, BlockType.SOLID);
        world.fill(0, 1, 0, 1, 4, 2, BlockType.SOLID);
        world.set(2, 1, 2, BlockType.SOLID);

        SearchResult r = new WorldPathfinder(world, EntityProfile.DEFAULT, false)
                .find(p(1, 5, 0), p(3, 1, 0));
        assertTrue(r.found());
        assertTrue(r.positions().contains(p(2, 2, 2)), "uses the ledge: " + r.positions());
        long drops = r.path().stream().filter(s -> s.via() == MoveType.DROP).count();
        assertEquals(2, drops); // 3 down onto the ledge, then 1 down to the ground

        SearchResult cautious = new WorldPathfinder(world, EntityProfile.DEFAULT.withMaxDrop(2), false)
                .find(p(1, 5, 0), p(3, 1, 0));
        assertFalse(cautious.found(), "no drop of 2 or less exists");
    }

    @Test
    void unstandableEndpointsReportNoPath() {
        ArrayBlockView world = ArrayBlockView.flat("..#");
        WorldPathfinder finder = new WorldPathfinder(world, EntityProfile.DEFAULT, false);
        assertFalse(finder.find(p(0, 1, 0), p(2, 1, 0)).found(), "goal inside a wall");
        assertFalse(finder.find(p(0, 2, 0), p(1, 1, 0)).found(), "start floating");
        assertThrows(IllegalArgumentException.class,
                () -> finder.search(p(0, 1, 0), p(2, 1, 0), SearchListener.NONE));
    }

    /** Independent Dijkstra over the same moves and costs. */
    private static double dijkstraCost(BlockMoveSource moves, CostModel costs, BlockPoint start,
            BlockPoint goal) {
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
                double nd = cur.d() + costs.cost(cur.pos(), to, type);
                if (nd < dist.getOrDefault(to, Double.POSITIVE_INFINITY)) {
                    dist.put(to, nd);
                    heap.add(new Item(nd, to));
                }
            });
        }
        return Double.POSITIVE_INFINITY;
    }

    @Test
    void matchesDijkstraOnRandom3dWorlds() {
        Random rng = new Random(2024);
        int compared = 0;
        int found = 0;
        for (int trial = 0; trial < 900; trial++) {
            boolean diagonal = trial % 2 == 1;
            ArrayBlockView world = TestWorlds.random(rng, 2 + rng.nextInt(10), 2 + rng.nextInt(10));
            WorldPathfinder finder = new WorldPathfinder(world, EntityProfile.DEFAULT, diagonal);
            BlockPoint start = TestWorlds.surface(world, finder.validator(), 0, 0);
            BlockPoint goal = TestWorlds.surface(world, finder.validator(), world.sizeX() - 1, world.sizeZ() - 1);
            if (start == null || goal == null) {
                continue;
            }
            compared++;

            SearchResult r = finder.find(start, goal);
            double expected = dijkstraCost(new BlockMoveSource(finder.validator(), diagonal),
                    finder.costs(), start, goal);

            assertEquals(Double.isFinite(expected), r.found(), "reachability, trial " + trial);
            if (r.found()) {
                found++;
                assertEquals(expected, r.cost(), EPS, "cost, trial " + trial);
                for (PathStep step : r.path()) {
                    assertTrue(finder.canStand(step.pos()), "unstandable step " + step);
                }
            }
        }
        assertTrue(compared > 600 && found > 100, "compared " + compared + ", found " + found);
    }
}
