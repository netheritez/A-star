package astar.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import astar.core.BlockPoint;
import astar.core.MoveType;
import astar.core.PathStep;
import astar.core.SearchResult;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class PathSmootherTest {
    private static final double EPS = 1e-9;

    private static BlockPoint f(int x, int z) {
        return new BlockPoint(x, 1, z);
    }

    private record Smoothed(SearchResult raw, List<Waypoint> waypoints, WorldPathfinder finder) {}

    private static Smoothed route(ArrayBlockView world, boolean diagonal, BlockPoint start,
            BlockPoint goal) {
        WorldPathfinder finder = new WorldPathfinder(world, EntityProfile.DEFAULT, diagonal);
        SearchResult raw = finder.find(start, goal);
        return new Smoothed(raw, finder.smoother().smooth(raw.path()), finder);
    }

    @Test
    void aStraightCorridorIsOneSegment() {
        Smoothed s = route(ArrayBlockView.flat("......"), false, f(0, 0), f(5, 0));
        assertEquals(List.of(new Waypoint(f(0, 0), Waypoint.Kind.START),
                new Waypoint(f(5, 0), Waypoint.Kind.STRAIGHT)), s.waypoints());
    }

    @Test
    void anOpenFieldIsOneStraightLineShorterThanTheGridPath() {
        ArrayBlockView world = ArrayBlockView.flat("......", "......", "......");
        Smoothed s = route(world, true, f(0, 0), f(5, 2));
        assertEquals(2, s.waypoints().size());
        double straight = PathSmoother.length(PathSmoother.positions(s.waypoints()));
        assertEquals(Math.hypot(5, 2), straight, EPS);
        assertTrue(straight < s.raw().cost());
    }

    @Test
    void goingAroundAWallKeepsTheCorner() {
        // From the top left to the bottom left, around a wall that blocks the left side.
        ArrayBlockView world = ArrayBlockView.flat(
                ".....",
                "####.",
                ".....");
        Smoothed s = route(world, true, f(0, 0), f(0, 2));
        List<BlockPoint> points = PathSmoother.positions(s.waypoints());
        assertTrue(points.size() >= 3, "can't go straight through the wall: " + points);
        for (int i = 0; i + 1 < points.size(); i++) {
            assertTrue(s.finder().validator().canWalkStraight(points.get(i), points.get(i + 1)));
        }
        assertTrue(points.stream().anyMatch(p -> p.x() == 4), "passes the gap at x = 4: " + points);
    }

    @Test
    void jumpsAndDropsAreKeptExactly() {
        ArrayBlockView world = terrain();
        Smoothed s = route(world, true, new BlockPoint(0, 1, 0), new BlockPoint(10, 1, 0));
        List<PathStep> raw = s.raw().path();
        List<Waypoint> w = s.waypoints();
        List<BlockPoint> points = PathSmoother.positions(w);

        for (int i = 1; i < raw.size(); i++) {
            MoveType via = raw.get(i).via();
            if (via != MoveType.JUMP_UP && via != MoveType.DROP) {
                continue;
            }
            int k = points.indexOf(raw.get(i).pos());
            assertTrue(k > 0, "landing kept: " + raw.get(i));
            assertEquals(raw.get(i - 1).pos(), points.get(k - 1), "takeoff kept before " + raw.get(i));
            assertEquals(via == MoveType.JUMP_UP ? Waypoint.Kind.JUMP_UP : Waypoint.Kind.DROP,
                    w.get(k).kind());
        }
        assertTrue(w.size() < raw.size(), w.size() + " waypoints for " + raw.size() + " steps");
    }

    @Test
    void emptyAndSingleStepPaths() {
        PathSmoother smoother = new WorldPathfinder(ArrayBlockView.flat(".."), EntityProfile.DEFAULT,
                true).smoother();
        assertEquals(List.of(), smoother.smooth(List.of()));
        assertEquals(List.of(new Waypoint(f(0, 0), Waypoint.Kind.START)),
                smoother.smooth(List.of(new PathStep(f(0, 0), null))));
    }

    /** The terrain demo: stairs up a plateau, a ledge to drop onto, and a lava strip. */
    private static ArrayBlockView terrain() {
        ArrayBlockView w = new ArrayBlockView(12, 7, 7);
        w.fill(0, 0, 0, 11, 0, 6, BlockType.SOLID);
        w.fill(5, 1, 0, 7, 4, 6, BlockType.SOLID);
        for (int step = 1; step <= 4; step++) {
            w.fill(step, 1, 0, step, step, 0, BlockType.SOLID);
        }
        w.set(8, 1, 6, BlockType.SOLID);
        w.fill(9, 0, 3, 11, 0, 3, BlockType.HAZARD);
        return w;
    }

    // ---- Property test with an independent checker ----------------------------------------

    /**
     * Walks a straight segment in small steps, checking the rules by sampling rather than with
     * the exact geometry the validator uses: the cell under the centre must be standable, and
     * every cell under the body's edge must be clear and not above lava.
     */
    private static void assertWalkable(MoveValidator v, BlockPoint a, BlockPoint b) {
        double ax = a.x() + 0.5, az = a.z() + 0.5, bx = b.x() + 0.5, bz = b.z() + 0.5;
        double len = Math.hypot(bx - ax, bz - az);
        double r = v.profile().width() / 2 * 0.999;
        int samples = Math.max(2, (int) Math.ceil(len / 0.02));
        for (int i = 0; i <= samples; i++) {
            // A small irrational-ish offset keeps samples off exact grid corners.
            double t = Math.min(1, (i + 0.0137) / samples);
            double px = ax + t * (bx - ax);
            double pz = az + t * (bz - az);
            int cx = (int) Math.floor(px);
            int cz = (int) Math.floor(pz);
            double e = v.elevation(a);
            double ec = v.elevation(cx, a.y(), cz);
            assertTrue(Math.abs(ec - e) <= MoveValidator.LEVEL + EPS,
                    "no floor at about the same height under the centre at (" + px + ", " + pz + ") on " + a + "->" + b);
            for (int k = 0; k < 16; k++) {
                double angle = k * Math.PI / 8;
                int ex = (int) Math.floor(px + r * Math.cos(angle));
                int ez = (int) Math.floor(pz + r * Math.sin(angle));
                double ee = v.elevation(ex, a.y(), ez); // over a floor at about the same height?
                boolean floor = Math.abs(ee - e) <= MoveValidator.LEVEL + EPS
                        && Math.abs(ee - ec) <= MoveValidator.LEVEL + EPS;
                assertTrue(floor || (v.canBrushPast(ex, e, ez) && v.canBrushPast(ex, ec, ez)),
                        "body edge hits (" + ex + ", " + ez + ") on " + a + "->" + b);
            }
        }
    }

    @Test
    void smoothedPathsAreSafeAndNeverLongerOnRandomWorlds() {
        Random rng = new Random(2024);
        int checked = 0;
        for (int trial = 0; trial < 900; trial++) {
            boolean diagonal = trial % 2 == 1;
            ArrayBlockView world = TestWorlds.random(rng, 2 + rng.nextInt(10), 2 + rng.nextInt(10));
            WorldPathfinder finder = new WorldPathfinder(world, EntityProfile.DEFAULT, diagonal);
            BlockPoint start = TestWorlds.surface(world, finder.validator(), 0, 0);
            BlockPoint goal = TestWorlds.surface(world, finder.validator(),
                    world.sizeX() - 1, world.sizeZ() - 1);
            if (start == null || goal == null) {
                continue;
            }
            SearchResult raw = finder.find(start, goal);
            if (!raw.found()) {
                continue;
            }
            checked++;
            List<Waypoint> w = finder.smoother().smooth(raw.path());
            List<BlockPoint> points = PathSmoother.positions(w);
            List<BlockPoint> rawPoints = raw.positions();

            assertEquals(start, points.get(0));
            assertEquals(goal, points.get(points.size() - 1));
            assertEquals(Waypoint.Kind.START, w.get(0).kind());

            // Waypoints are a subsequence of the raw path.
            int at = 0;
            for (BlockPoint p : points) {
                while (at < rawPoints.size() && !rawPoints.get(at).equals(p)) {
                    at++;
                }
                assertTrue(at < rawPoints.size(), "waypoint " + p + " not on the raw path, trial " + trial);
            }

            assertTrue(PathSmoother.length(points) <= PathSmoother.length(rawPoints) + EPS,
                    "longer than the raw path, trial " + trial);

            MoveValidator v = finder.validator();
            for (int i = 1; i < w.size(); i++) {
                if (w.get(i).kind() != Waypoint.Kind.STRAIGHT) {
                    continue;
                }
                BlockPoint a = points.get(i - 1);
                BlockPoint b = points.get(i);
                if (a.y() == b.y()
                        && Math.abs(v.elevation(a) - v.elevation(b)) <= MoveValidator.LEVEL + EPS) {
                    assertWalkable(v, a, b); // a level straight line: check it by sampling
                } else {
                    // A step up or down (slab, stairs, fence top): never merged, so it must be
                    // one raw step of the path, which the move rules already validated.
                    int k = rawPoints.indexOf(a);
                    assertTrue(k >= 0 && k + 1 < rawPoints.size() && rawPoints.get(k + 1).equals(b),
                            "a height change merged into a longer segment: " + a + " -> " + b);
                }
            }
        }
        assertTrue(checked > 50, "only " + checked + " paths checked");
    }
}
