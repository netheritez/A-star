package astar.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import astar.core.AStarSearch;
import astar.core.BlockPoint;
import astar.core.CostModel;
import astar.core.Heuristic;
import astar.core.Heuristics;
import astar.core.MoveType;
import astar.core.PathStep;
import astar.core.Pos;
import astar.core.SearchResult;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Moves one across and two along, and the distance estimate that goes with them. */
class SixteenDirectionsTest {
    private static final double EPS = 1e-9;

    private static BlockPoint p(int x, int y, int z) {
        return new BlockPoint(x, y, z);
    }

    private static Map<Long, MoveType> moves(WorldPathfinder f, BlockPoint from) {
        Map<Long, MoveType> out = new HashMap<>();
        f.moves().moves(from.pack(), out::put);
        return out;
    }

    @Test
    void headsStraightAtTwentySevenDegreesOnOpenGround() {
        ArrayBlockView world = ArrayBlockView.flat(new String[] {
            "............",
            "............",
            "............",
            "............",
            "............",
            "............",
            "............",
        });
        SearchResult r = new WorldPathfinder(world, EntityProfile.DEFAULT, 16)
                .find(p(0, 1, 0), p(10, 1, 5));
        assertEquals(5 * Math.sqrt(5), r.cost(), EPS);
        assertEquals(6, r.path().size());
        // Every step the same way: no zigzag.
        for (int i = 1; i < r.path().size(); i++) {
            BlockPoint a = r.path().get(i - 1).pos();
            BlockPoint b = r.path().get(i).pos();
            assertEquals(2, b.x() - a.x());
            assertEquals(1, b.z() - a.z());
            assertEquals(MoveType.DIAGONAL, r.path().get(i).via());
        }
    }

    @Test
    void noLongStepWhereTheBodyWouldClipACorner() {
        // The line from (0, 0) to (2, 1) passes close to (1, 1): a wall there blocks it, one at
        // (1, 2), well clear of the line, doesn't.
        ArrayBlockView world = ArrayBlockView.flat(new String[] {
            "....",
            ".#..",
            "....",
        });
        WorldPathfinder f = new WorldPathfinder(world, EntityProfile.DEFAULT, 16);
        Map<Long, MoveType> from = moves(f, p(0, 1, 0));
        assertFalse(from.containsKey(p(2, 1, 1).pack()));
        assertFalse(from.containsKey(p(1, 1, 2).pack()), "(1, 1) is in the way of this one too");

        ArrayBlockView open = ArrayBlockView.flat(new String[] {
            "....",
            "....",
            ".#..",
        });
        Map<Long, MoveType> clear = moves(new WorldPathfinder(open, EntityProfile.DEFAULT, 16),
                p(0, 1, 0));
        assertEquals(MoveType.DIAGONAL, clear.get(p(2, 1, 1).pack()));
    }

    @Test
    void onlyOnLevelGround() {
        // A step up half-way along: the long step isn't taken, the grid moves are.
        ArrayBlockView world = ArrayBlockView.flat(new String[] {
            "...",
            "...",
        });
        world.set(1, 1, 0, BlockType.PARTIAL_8);
        world.set(1, 1, 1, BlockType.PARTIAL_8);
        world.set(2, 1, 1, BlockType.PARTIAL_8);
        WorldPathfinder f = new WorldPathfinder(world, EntityProfile.DEFAULT, 16);
        assertFalse(moves(f, p(0, 1, 0)).containsKey(p(2, 1, 1).pack()));
    }

    @Test
    void theEstimateIsTheShortestWayOnOpenGround() {
        // Dijkstra over the 16 moves on an open plane agrees with the estimate everywhere.
        ArrayBlockView world = new ArrayBlockView(15, 3, 15);
        world.fill(0, 0, 0, 14, 0, 14, BlockType.SOLID);
        WorldPathfinder f = new WorldPathfinder(world, EntityProfile.DEFAULT, 16);
        BlockPoint start = p(0, 1, 0);
        for (int x = 0; x < 15; x++) {
            for (int z = 0; z < 15; z++) {
                AStarSearch s = new AStarSearch(start, p(x, 1, z), f.moves(), f.costs(),
                        Heuristics.ZERO);
                s.runToEnd();
                assertEquals(s.result().cost(), Heuristics.SIXTEEN_XZ.estimate(x, 0, z), 1e-9,
                        "to " + x + ", " + z);
            }
        }
    }

    @Test
    void theEstimateIsConsistentWithEveryMove() {
        Random rng = new Random(16);
        for (int trial = 0; trial < 40; trial++) {
            ArrayBlockView world = TestWorlds.random(rng, 4 + rng.nextInt(16), 4 + rng.nextInt(16));
            WorldPathfinder f = new WorldPathfinder(world, EntityProfile.PLAYER, 16);
            CostModel c = f.costs();
            Heuristic h = f.heuristic();
            BlockPoint goal = p(rng.nextInt(world.sizeX()), 1, rng.nextInt(world.sizeZ()));
            for (int x = 0; x < world.sizeX(); x++) {
                for (int z = 0; z < world.sizeZ(); z++) {
                    for (int y = 0; y < world.sizeY(); y++) {
                        long a = Pos.pack(x, y, z);
                        f.moves().moves(a, (b, type) -> assertTrue(
                                h.between(a, goal.pack())
                                        <= c.cost(a, b, type) + h.between(b, goal.pack()) + EPS,
                                Pos.toString(a) + " to " + Pos.toString(b)));
                    }
                }
            }
        }
    }

    @Test
    void neverLongerThanEightWaysAndAsShortAsDijkstra() {
        Random rng = new Random(1616);
        int shorter = 0;
        int compared = 0;
        for (int trial = 0; trial < 150; trial++) {
            ArrayBlockView world = TestWorlds.random(rng, 6 + rng.nextInt(20), 6 + rng.nextInt(20));
            WorldPathfinder eight = new WorldPathfinder(world, EntityProfile.PLAYER, 8);
            WorldPathfinder sixteen = new WorldPathfinder(world, EntityProfile.PLAYER, 16);
            BlockPoint a = TestWorlds.surface(world, sixteen.validator(),
                    rng.nextInt(world.sizeX()), rng.nextInt(world.sizeZ()));
            BlockPoint b = TestWorlds.surface(world, sixteen.validator(),
                    rng.nextInt(world.sizeX()), rng.nextInt(world.sizeZ()));
            if (a == null || b == null) {
                continue;
            }
            SearchResult r8 = eight.find(a, b);
            SearchResult r16 = sixteen.find(a, b);
            AStarSearch dijkstra = new AStarSearch(a, b, sixteen.moves(), sixteen.costs(),
                    Heuristics.ZERO);
            dijkstra.runToEnd();
            assertEquals(dijkstra.result().found(), r16.found());
            if (!r16.found()) {
                continue;
            }
            assertEquals(dijkstra.result().cost(), r16.cost(), 1e-6, "trial " + trial);
            if (r8.found()) {
                assertTrue(r16.cost() <= r8.cost() + 1e-6, "trial " + trial);
                shorter += r16.cost() < r8.cost() - 1e-6 ? 1 : 0;
            }
            compared++;
            for (PathStep s : r16.path()) {
                assertTrue(s.pos().y() >= 0);
            }
        }
        assertTrue(compared > 50, "only " + compared);
        assertTrue(shorter > 0, "16 ways never found a shorter route");
    }
}
