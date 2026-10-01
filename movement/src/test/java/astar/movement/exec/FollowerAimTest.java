package astar.movement.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import astar.core.PathStep;
import astar.movement.exec.Executor.Status;
import astar.movement.plan.ExecutionPlan;
import astar.movement.plan.PlanBuilder;
import astar.movement.sim.SimulatedPlayer;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The point the follower steers for, which the in-game overlay draws as the lead marker. */
class FollowerAimTest {

    @Test
    void aimsAtAPointOnTheRouteJustAheadOfThePlayer() {
        List<PathStep> path = ExecutorTest.path(0, 0, 12, 0, 12, 10);
        ExecutorTest.World world = new ExecutorTest.World().floor(-5, -5, 20, 20);
        ExecutionPlan plan = new PlanBuilder(world).roundCorners(false).build(path);
        ExecutionPlan.Node start = plan.nodes().get(0);
        SimulatedPlayer player = new SimulatedPlayer(world, SimulatedPlayer.Attributes.PLAYER,
                start.x(), start.y(), start.z(), -90, true);
        Executor ex = new Executor(new SimController(player), plan, Executor.Settings.DEFAULT);
        double lastAimed = 0;
        while (ex.status() == Status.RUNNING && ex.ticks() < 1000) {
            ex.tick();
            Follower f = ex.follower();
            double[] p = f.placeAt(f.aimed());
            // Ahead of the player along the route, never behind, and never backwards.
            assertTrue(f.aimed() >= f.progress() - 1e-9, "aimed behind at tick " + ex.ticks());
            // (It pulls in a little while braking for a corner, as it aims less far ahead the
            // slower it goes: letting go sheds speed quickly.)
            assertTrue(f.aimed() >= lastAimed - 0.15, "went back at tick " + ex.ticks());
            assertTrue(f.aimed() - f.progress() < 4, "too far ahead at tick " + ex.ticks());
            lastAimed = f.aimed();
            // On the route: the path runs along z = 0, then along x = 12.
            boolean onRoute = Math.abs(p[2] - 0.5) < 1e-6 || Math.abs(p[0] - 12.5) < 1e-6;
            assertTrue(onRoute, "off the route at tick " + ex.ticks() + ": " + p[0] + ", "
                    + p[2]);
            assertEquals(start.y(), p[1], 1e-9);
        }
        assertEquals(Status.ARRIVED, ex.status());
    }

    @Test
    void placesAPointAFixedDistanceAheadOnTheRoute() {
        List<PathStep> path = ExecutorTest.path(0, 0, 12, 0, 12, 10);
        ExecutorTest.World world = new ExecutorTest.World().floor(-5, -5, 20, 20);
        ExecutionPlan plan = new PlanBuilder(world).roundCorners(false).build(path);
        ExecutionPlan.Node start = plan.nodes().get(0);
        ExecutionPlan.Node end = plan.nodes().get(plan.nodes().size() - 1);
        SimulatedPlayer player = new SimulatedPlayer(world, SimulatedPlayer.Attributes.PLAYER,
                start.x(), start.y(), start.z(), -90, true);
        Executor ex = new Executor(new SimController(player), plan, Executor.Settings.DEFAULT);
        while (ex.status() == Status.RUNNING && ex.ticks() < 1000) {
            ex.tick();
            Follower f = ex.follower();
            double[] p = f.placeAhead(4);
            // Walked back along the route, it's 4 blocks from the player's place (or the end).
            double along = p[0] - 0.5 + p[2] - 0.5;
            double want = Math.min(f.progress() + 4, 22);
            assertEquals(want, along, 1e-6, "at tick " + ex.ticks());
            boolean onRoute = Math.abs(p[2] - 0.5) < 1e-6 || Math.abs(p[0] - 12.5) < 1e-6;
            assertTrue(onRoute, "off the route at tick " + ex.ticks());
        }
        assertEquals(Status.ARRIVED, ex.status());
        double[] last = ex.follower().placeAhead(4);
        assertEquals(end.x(), last[0], 1e-9);
        assertEquals(end.z(), last[2], 1e-9);
    }
}
