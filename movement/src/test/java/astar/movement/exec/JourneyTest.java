package astar.movement.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import astar.core.BlockPoint;
import astar.core.DefaultCostModel;
import astar.core.Heuristics;
import astar.core.PathStep;
import astar.core.TurnPenalty;
import astar.movement.Keys;
import astar.movement.exec.Journey.Event;
import astar.movement.exec.Journey.Status;
import astar.movement.sim.Aabb;
import astar.movement.sim.SimBlock;
import astar.movement.sim.SimWorld;
import astar.movement.sim.SimulatedPlayer;
import astar.pathing.ArrayBlockView;
import astar.pathing.BlockType;
import astar.pathing.EntityProfile;
import astar.pathing.TerrainCosts;
import astar.pathing.WorldPathfinder;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Disturbances in the simulator: pushes, lag-backs and blocks placed on the way. */
class JourneyTest {

    private static final SimBlock CUBE = SimBlock.solid(new Aabb(0, 0, 0, 1, 1, 1));

    /** A block grid the pathfinder reads and the simulator sees the same way. */
    private static final class Grid implements SimWorld {
        final ArrayBlockView view;

        Grid(int sizeX, int sizeZ) {
            view = new ArrayBlockView(sizeX, 5, sizeZ);
            for (int x = 0; x < sizeX; x++) {
                for (int z = 0; z < sizeZ; z++) {
                    view.set(x, 0, z, BlockType.SOLID);
                }
            }
        }

        /** A wall two blocks high. */
        void wall(int x, int z) {
            view.set(x, 1, z, BlockType.SOLID);
            view.set(x, 2, z, BlockType.SOLID);
        }

        @Override
        public SimBlock block(int x, int y, int z) {
            return view.inBounds(x, y, z) && view.blockAt(x, y, z) == BlockType.SOLID ? CUBE
                    : SimBlock.AIR;
        }
    }

    private record Run(Journey journey, SimulatedPlayer player) {}

    private static Run start(Grid g, int x, int z, BlockPoint goal) {
        return start(g, x, z, goal, 8);
    }

    private static Run start(Grid g, int x, int z, BlockPoint goal, int directions) {
        return start(g, x, z, goal, directions, Journey.Settings.DEFAULT);
    }

    private static Run start(Grid g, int x, int z, BlockPoint goal, int directions,
            Journey.Settings settings) {
        SimulatedPlayer player = new SimulatedPlayer(g, SimulatedPlayer.Attributes.PLAYER,
                x + 0.5, 1, z + 0.5, -90, true);
        return start(g, goal, player, new SimController(player), directions, settings);
    }

    private static Run start(Grid g, BlockPoint goal, SimulatedPlayer player,
            PlayerController controller, int directions, Journey.Settings settings) {
        Router router = new GridRouter(new WorldPathfinder(g.view, EntityProfile.DEFAULT,
                directions, DefaultCostModel.DEFAULT, TerrainCosts.DEFAULT, Heuristics.OCTILE_XZ,
                TurnPenalty.DEFAULT_PER_TURN), g);
        return new Run(new Journey(router, controller, goal, settings), player);
    }

    private static void assertArrived(Run r, BlockPoint goal) {
        assertEquals(Status.ARRIVED, r.journey().status(), () -> r.journey().log().toString());
        assertEquals(goal.x() + 0.5, r.player().x(), 0.3);
        assertEquals(goal.z() + 0.5, r.player().z(), 0.3);
    }

    @Test
    void walksToTheGoalWithNothingInTheWay() {
        Grid g = new Grid(30, 7);
        BlockPoint goal = new BlockPoint(25, 1, 3);
        Run r = start(g, 2, 3, goal);
        r.journey().run(400);
        assertArrived(r, goal);
        // The prediction matches the simulator exactly, so nothing counts as a disturbance.
        assertEquals(0, r.journey().count(Event.DISTURBED));
        assertTrue(r.journey().log().isEmpty(), () -> r.journey().log().toString());
    }

    @Test
    void walksSixteenWaysWithoutAZigzag() {
        Grid g = new Grid(30, 16);
        g.wall(12, 8);
        BlockPoint goal = new BlockPoint(26, 1, 13);
        // The grid steps as found, to see the search's 16-way steps.
        Run r = start(g, 2, 2, goal, 16, Journey.Settings.DEFAULT.withStraightLines(false));
        r.journey().run(500);
        assertArrived(r, goal);
        assertEquals(0, r.journey().count(Event.DISTURBED), () -> r.journey().log().toString());
        List<PathStep> path = r.journey().path();
        int longSteps = 0;
        for (int i = 1; i < path.size(); i++) {
            BlockPoint a = path.get(i - 1).pos();
            BlockPoint b = path.get(i).pos();
            longSteps += Math.abs(b.x() - a.x()) + Math.abs(b.z() - a.z()) == 3 ? 1 : 0;
        }
        assertTrue(longSteps >= 5, "only " + longSteps + " long steps in " + path);
    }

    @Test
    void walksStraightLinesAtAnyAngle() {
        // Open ground, a goal off every grid direction: the grid path runs along one direction
        // and then another, the smoothed line straight there.
        Grid g = new Grid(70, 70);
        BlockPoint goal = new BlockPoint(40, 1, 55);
        double[] turned = new double[2];
        int[] ticks = new int[2];
        for (int k = 0; k < 2; k++) {
            SimulatedPlayer player = new SimulatedPlayer(g, SimulatedPlayer.Attributes.PLAYER,
                    2.5, 1, 2.5, -90, true);
            float[] last = {Float.NaN};
            int at = k;
            SimController sim = new SimController(player);
            PlayerController controller = new PlayerController() {
                @Override
                public Observation observe() {
                    return sim.observe();
                }

                @Override
                public void tick(Keys keys, float yaw, float pitch) {
                    if (!Float.isNaN(last[0])) {
                        float d = Math.abs(yaw - last[0]) % 360;
                        turned[at] += Math.min(d, 360 - d);
                    }
                    last[0] = yaw;
                    sim.tick(keys, yaw, pitch);
                }
            };
            Run r = start(g, goal, player, controller, 8,
                    Journey.Settings.DEFAULT.withStraightLines(k == 1));
            while (r.journey().status() == Status.RUNNING && ticks[k] < 2000) {
                r.journey().tick();
                ticks[k]++;
            }
            assertArrived(r, goal);
        }
        assertTrue(ticks[1] < ticks[0], ticks[1] + " ticks on the line, " + ticks[0] + " on the grid");
        assertTrue(turned[1] < 0.8 * turned[0],
                turned[1] + " degrees turned on the line, " + turned[0] + " on the grid");
    }

    @Test
    void getsBackOnAfterAHardPushSideways() {
        Grid g = new Grid(30, 11);
        BlockPoint goal = new BlockPoint(25, 1, 5);
        Run r = start(g, 2, 5, goal);
        r.journey().run(20);
        SimulatedPlayer p = r.player();
        // Knockback: thrown sideways and up, as a hit would.
        p.setPositionAndVelocity(p.x(), p.y(), p.z(), p.vx(), 0.4, 1.2);
        r.journey().run(400);
        assertArrived(r, goal);
        assertTrue(r.journey().count(Event.DISTURBED) >= 1);
    }

    @Test
    void carriesOnAfterALagBack() {
        Grid g = new Grid(30, 7);
        BlockPoint goal = new BlockPoint(25, 1, 3);
        Run r = start(g, 2, 3, goal);
        r.journey().run(30);
        SimulatedPlayer p = r.player();
        double back = p.x() - 5;
        p.setPositionAndVelocity(back, p.y(), p.z(), 0, p.vy(), 0);
        r.journey().run(400);
        assertArrived(r, goal);
        assertEquals(1, r.journey().count(Event.TELEPORTED), () -> r.journey().log().toString());
    }

    @Test
    void goesAroundABlockPlacedOnThePathAhead() {
        Grid g = new Grid(30, 7);
        BlockPoint goal = new BlockPoint(25, 1, 3);
        Run r = start(g, 2, 3, goal);
        r.journey().run(10);
        // A wall across most of the way, with a gap at one side.
        for (int z = 1; z < 7; z++) {
            g.wall(15, z);
        }
        r.journey().run(400);
        assertArrived(r, goal);
        assertTrue(r.journey().count(Event.BLOCKED) >= 1, () -> r.journey().log().toString());
        assertTrue(r.journey().count(Event.REJOINED) + r.journey().count(Event.REROUTED) >= 1);
        assertEquals(0, r.journey().count(Event.STUCK), () -> r.journey().log().toString());
    }

    @Test
    void goesTheOtherWayRoundWhenTheWayAheadIsShut() {
        // Two ways round a pillar; the one taken is walled off after it starts.
        Grid g = new Grid(31, 9);
        for (int x = 6; x < 25; x++) {
            for (int z = 2; z < 7; z++) {
                g.wall(x, z);
            }
        }
        BlockPoint goal = new BlockPoint(28, 1, 4);
        Run r = start(g, 2, 4, goal);
        r.journey().run(5);
        boolean north = r.journey().path().stream().anyMatch(s -> s.pos().z() < 2);
        int shut = north ? 1 : 7;
        g.wall(15, shut);
        g.wall(15, north ? 0 : 8);
        r.journey().run(600);
        assertArrived(r, goal);
        assertTrue(r.journey().count(Event.REJOINED) + r.journey().count(Event.REROUTED) >= 1,
                () -> r.journey().log().toString());
        assertTrue(r.journey().path().stream().noneMatch(s -> s.pos().z() == shut
                && s.pos().x() == 15));
    }

    @Test
    void givesUpWhenThereIsNoWayLeft() {
        Grid g = new Grid(30, 5);
        BlockPoint goal = new BlockPoint(25, 1, 2);
        Run r = start(g, 2, 2, goal);
        r.journey().run(10);
        for (int z = 0; z < 5; z++) {
            g.wall(15, z);
        }
        r.journey().run(400);
        assertEquals(Status.FAILED, r.journey().status());
        assertTrue(r.journey().reason().startsWith("no path"), r.journey().reason());
        // It stopped before walking into the wall.
        assertTrue(r.player().x() < 15 - 0.3);
    }
}
