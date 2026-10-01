package astar.movement.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import astar.core.BlockPoint;
import astar.core.MoveType;
import astar.core.PathStep;
import astar.movement.Scenario;
import astar.movement.exec.Executor.Status;
import astar.movement.plan.ExecutionPlan;
import astar.movement.plan.ExecutionPlan.Kind;
import astar.movement.plan.PlanBuilder;
import astar.movement.sim.Aabb;
import astar.movement.sim.SimBlock;
import astar.movement.sim.SimWorld;
import astar.movement.sim.SimulatedPlayer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExecutorTest {

    private static final SimBlock CUBE = SimBlock.solid(new Aabb(0, 0, 0, 1, 1, 1));

    /** Blocks placed one by one; everything else is air. */
    static final class World implements SimWorld {
        final Map<List<Integer>, SimBlock> blocks = new HashMap<>();

        World put(int x, int y, int z, SimBlock b) {
            blocks.put(List.of(x, y, z), b);
            return this;
        }

        World floor(int x0, int z0, int x1, int z1) {
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    put(x, -1, z, CUBE);
                }
            }
            return this;
        }

        /** Floor under every path cell and nothing else: a one-block rail over the void. */
        World rail(List<PathStep> path) {
            for (PathStep s : path) {
                put(s.pos().x(), s.pos().y() - 1, s.pos().z(), CUBE);
            }
            return this;
        }

        /** A one-block corridor along the path: floor under it, two-high walls around it. */
        World corridor(List<PathStep> path) {
            java.util.Set<List<Integer>> open = new java.util.HashSet<>();
            for (PathStep s : path) {
                open.add(List.of(s.pos().x(), s.pos().z()));
            }
            for (PathStep s : path) {
                int x = s.pos().x();
                int z = s.pos().z();
                put(x, -1, z, CUBE);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (!open.contains(List.of(x + dx, z + dz))) {
                            put(x + dx, 0, z + dz, CUBE);
                            put(x + dx, 1, z + dz, CUBE);
                            put(x + dx, -1, z + dz, CUBE);
                        }
                    }
                }
            }
            return this;
        }

        @Override
        public SimBlock block(int x, int y, int z) {
            return blocks.getOrDefault(List.of(x, y, z), SimBlock.AIR);
        }
    }

    /** A path through these (x, z) corners at y 0, one cell at a time. */
    static List<PathStep> path(int... corners) {
        List<PathStep> path = new ArrayList<>();
        path.add(new PathStep(new BlockPoint(corners[0], 0, corners[1]), null));
        for (int i = 2; i < corners.length; i += 2) {
            int x = corners[i - 2];
            int z = corners[i - 1];
            while (x != corners[i] || z != corners[i + 1]) {
                int dx = Integer.signum(corners[i] - x);
                int dz = Integer.signum(corners[i + 1] - z);
                x += dx;
                z += dz;
                path.add(new PathStep(new BlockPoint(x, 0, z),
                        dx != 0 && dz != 0 ? MoveType.DIAGONAL : MoveType.WALK));
            }
        }
        return path;
    }

    /** What happened on a run. */
    record Run(Status status, SimulatedPlayer player, Executor executor, double maxOffset,
            double lowestY, int sprintTicks, int bumps, List<String> log) {

        double toEnd() {
            return executor.follower().toEnd(new SimController(player).observe());
        }

        String dump() {
            return status + " after " + executor.ticks() + " ticks, " + toEnd()
                    + " from the end\n" + String.join("\n", log.subList(Math.max(0,
                            log.size() - 40), log.size()));
        }
    }

    static Run run(SimWorld world, List<PathStep> path, float startYaw) {
        ExecutionPlan plan = new PlanBuilder(world).build(path);
        ExecutionPlan.Node start = plan.nodes().get(0);
        SimulatedPlayer player = new SimulatedPlayer(world, SimulatedPlayer.Attributes.PLAYER,
                start.x(), start.y(), start.z(), startYaw, true);
        Executor ex = new Executor(new SimController(player), plan, Executor.Settings.DEFAULT);
        double maxOffset = 0;
        double lowest = player.y();
        int sprint = 0;
        int bumps = 0;
        List<String> log = new ArrayList<>();
        while (ex.status() == Status.RUNNING && ex.ticks() < 2000) {
            ex.tick();
            maxOffset = Math.max(maxOffset, ex.follower().offset());
            lowest = Math.min(lowest, player.y());
            sprint += player.sprinting() ? 1 : 0;
            bumps += player.horizontalCollision() ? 1 : 0;
            Follower.Intent in = ex.lastIntent();
            log.add(String.format("t%d %.3f %.3f %.3f v=%.3f yaw=%.1f want=%.1f %s off=%.2f",
                    ex.ticks(), player.x(), player.y(), player.z(),
                    Math.hypot(player.vx(), player.vz()), player.yaw(),
                    in == null ? 0 : in.yaw(), in == null ? "" : in.gait(),
                    ex.follower().offset()));
        }
        return new Run(ex.status(), player, ex, maxOffset, lowest, sprint, bumps, log);
    }

    @Test
    void sprintsAStraightRunAndStopsOnTheGoal() {
        List<PathStep> p = path(0, 0, 30, 0);
        Run r = run(new World().floor(-5, -5, 40, 5), p, -90);
        assertEquals(Status.ARRIVED, r.status(), r.dump());
        assertTrue(r.toEnd() <= Follower.GOAL_RADIUS, r.dump());
        assertTrue(r.sprintTicks() > 60, r.dump());
        assertTrue(r.executor().ticks() < 150, r.dump());
        assertTrue(r.maxOffset() < 0.05, r.dump());
    }

    @Test
    void turnsAroundWhenStartingTheWrongWay() {
        List<PathStep> p = path(0, 0, 10, 0);
        Run r = run(new World().floor(-5, -5, 20, 5), p, 90);
        assertEquals(Status.ARRIVED, r.status(), r.dump());
        assertTrue(r.maxOffset() < 0.3, r.dump());
    }

    @Test
    void followsCornersInAOneBlockCorridor() {
        List<PathStep> p = path(0, 0, 8, 0, 8, 8, 16, 8, 16, 0);
        Run r = run(new World().corridor(p), p, -90);
        assertEquals(Status.ARRIVED, r.status(), r.dump());
        assertTrue(r.maxOffset() < 0.35, r.dump());
        assertTrue(r.bumps() < 10, r.bumps() + " bumps\n" + r.dump());
    }

    @Test
    void staysOnAOneBlockRailWithTurns() {
        List<PathStep> p = path(0, 0, 6, 0, 6, 6, 12, 6, 12, 12, 4, 12);
        Run r = run(new World().rail(p), p, -90);
        assertEquals(Status.ARRIVED, r.status(), r.dump());
        assertEquals(0.0, r.lowestY(), r.dump());
    }

    @Test
    void followsDiagonals() {
        List<PathStep> p = path(0, 0, 6, 6, 12, 6, 6, 12);
        Run r = run(new World().floor(-5, -5, 20, 20), p, 0);
        assertEquals(Status.ARRIVED, r.status(), r.dump());
        assertTrue(r.maxOffset() < 0.5, r.dump());
    }

    @Test
    void walksDownAStepThatSneakingWouldStopAt() {
        // Floors a block apart that the path walks between, as it does down stairs.
        List<PathStep> p = new ArrayList<>(path(0, 0, 4, 0));
        for (int x = 5; x <= 9; x++) {
            p.add(new PathStep(new BlockPoint(x, -1, 0), MoveType.WALK));
        }
        World w = new World().floor(-5, -5, 4, 5);
        for (int x = 5; x <= 15; x++) {
            for (int z = -5; z <= 5; z++) {
                w.put(x, -2, z, CUBE);
            }
        }
        Run r = run(w, p, -90);
        assertEquals(Status.ARRIVED, r.status(), r.dump());
        assertEquals(-1.0, r.player().y(), 1e-9, r.dump());
    }

    @Test
    void walksAWholePlanSkippingMovesItCantDoYet() {
        // Up a ladder at x 5 onto a pillar's top, and on along it.
        List<PathStep> p = new ArrayList<>(path(0, 0, 5, 0));
        p.add(new PathStep(new BlockPoint(5, 1, 0), MoveType.CLIMB));
        p.add(new PathStep(new BlockPoint(5, 2, 0), MoveType.CLIMB));
        p.add(new PathStep(new BlockPoint(6, 2, 0), MoveType.WALK));
        for (int z = 1; z <= 6; z++) {
            p.add(new PathStep(new BlockPoint(6, 2, z), MoveType.WALK));
        }
        World w = new World().floor(-5, -5, 10, 10);
        for (int z = -5; z <= 10; z++) {
            w.put(6, 0, z, CUBE).put(6, 1, z, CUBE);
        }
        ExecutionPlan plan = new PlanBuilder(w).build(p);
        SimRun.Result r = SimRun.run(w, plan, Executor.Settings.DEFAULT, 2000);
        assertEquals(List.of(), r.failures());
        assertEquals(2, r.stretches().size());
        // Both climbs, and the step off the ladder's top.
        assertEquals(3, r.skipped());
        assertEquals(Status.UNSUPPORTED, r.stretches().get(0).status());
        assertEquals(Status.ARRIVED, r.stretches().get(1).status());
    }

    @Test
    void stopsBeforeALadder() {
        List<PathStep> p = new ArrayList<>(path(0, 0, 5, 0));
        p.add(new PathStep(new BlockPoint(5, 1, 0), MoveType.CLIMB));
        World w = new World().floor(-5, -5, 10, 5);
        Run r = run(w, p, -90);
        assertEquals(Status.UNSUPPORTED, r.status(), r.dump());
        assertEquals(Kind.CLIMB, r.executor().blockedBy());
        assertEquals(5, r.executor().end());
        assertTrue(Math.abs(r.player().x() - 5.5) <= Follower.GOAL_RADIUS, r.dump());
    }

    @Test
    void jumpsUpABlockOnTheWay() {
        List<PathStep> p = new ArrayList<>(path(0, 0, 5, 0));
        p.add(new PathStep(new BlockPoint(6, 1, 0), MoveType.JUMP_UP));
        for (int x = 7; x <= 14; x++) {
            p.add(new PathStep(new BlockPoint(x, 1, 0), MoveType.WALK));
        }
        World w = new World().floor(-5, -5, 20, 5);
        for (int x = 6; x <= 20; x++) {
            for (int z = -5; z <= 5; z++) {
                w.put(x, 0, z, CUBE);
            }
        }
        Run r = run(w, p, -90);
        assertEquals(Status.ARRIVED, r.status(), r.dump());
        assertEquals(1.0, r.player().y(), 1e-9, r.dump());
        // A running jump, not a stop at the foot of the block.
        assertTrue(r.sprintTicks() > 20, r.dump());
        assertTrue(r.bumps() == 0, r.dump());
    }

    @Test
    void jumpsUpAStaircaseOfWholeBlocks() {
        List<PathStep> p = new ArrayList<>(path(0, 0, 3, 0));
        for (int x = 4; x <= 7; x++) {
            p.add(new PathStep(new BlockPoint(x, x - 3, 0), MoveType.JUMP_UP));
        }
        p.add(new PathStep(new BlockPoint(8, 4, 0), MoveType.WALK));
        p.add(new PathStep(new BlockPoint(9, 4, 0), MoveType.WALK));
        // A rail one block wide, over nothing.
        World w = new World().rail(p);
        Run r = run(w, p, -90);
        assertEquals(Status.ARRIVED, r.status(), r.dump());
        assertEquals(4.0, r.player().y(), 1e-9, r.dump());
        assertTrue(r.maxOffset() < 0.5, r.dump());
    }

    @Test
    void jumpsOntoASingleBlockAndTurnsThere() {
        // On a rail over nothing: the top is one block, and the route turns on it, so the jump
        // has to come down on it and not sail past.
        List<PathStep> p = new ArrayList<>(path(0, 0, 5, 0));
        p.add(new PathStep(new BlockPoint(6, 1, 0), MoveType.JUMP_UP));
        for (int z = 1; z <= 5; z++) {
            p.add(new PathStep(new BlockPoint(6, 1, z), MoveType.WALK));
        }
        World w = new World().rail(p);
        Run r = run(w, p, -90);
        assertEquals(Status.ARRIVED, r.status(), r.dump());
        assertEquals(1.0, r.player().y(), 1e-9, r.dump());
        assertTrue(r.lowestY() >= 0, r.dump());
    }

    @Test
    void dropsThreeBlocksOntoARailThatTurnsBelow() {
        List<PathStep> p = new ArrayList<>();
        for (int x = 0; x <= 5; x++) {
            p.add(new PathStep(new BlockPoint(x, 3, 0), x == 0 ? null : MoveType.WALK));
        }
        p.add(new PathStep(new BlockPoint(6, 0, 0), MoveType.DROP));
        for (int z = 1; z <= 5; z++) {
            p.add(new PathStep(new BlockPoint(6, 0, z), MoveType.WALK));
        }
        World w = new World().rail(p);
        Run r = run(w, p, -90);
        assertEquals(Status.ARRIVED, r.status(), r.dump());
        assertEquals(0.0, r.player().y(), 1e-9, r.dump());
        assertTrue(r.lowestY() >= 0, r.dump());
    }

    @Test
    void walksOffADropAtSpeedWhereTheLandingRunsOnStraight() {
        List<PathStep> p = new ArrayList<>();
        for (int x = 0; x <= 5; x++) {
            p.add(new PathStep(new BlockPoint(x, 2, 0), x == 0 ? null : MoveType.WALK));
        }
        p.add(new PathStep(new BlockPoint(6, 0, 0), MoveType.DROP));
        for (int x = 7; x <= 16; x++) {
            p.add(new PathStep(new BlockPoint(x, 0, 0), MoveType.WALK));
        }
        World w = new World().rail(p);
        Run r = run(w, p, -90);
        assertEquals(Status.ARRIVED, r.status(), r.dump());
        assertEquals(0.0, r.player().y(), 1e-9, r.dump());
        assertTrue(r.sprintTicks() > 20, r.dump());
    }

    @Test
    void theAirMathMatchesTheSimulator() {
        // A sprint jump pressing forward on flat ground: how far it goes before it lands.
        World w = new World().floor(-5, -5, 30, 5);
        SimulatedPlayer player = new SimulatedPlayer(w, SimulatedPlayer.Attributes.PLAYER,
                0.5, 0, 0.5, -90, true);
        astar.movement.Keys run = new astar.movement.Keys(true, false, false, false, false,
                false, true);
        for (int i = 0; i < 20; i++) {
            player.tick(run, -90, 0);
        }
        double v = player.vx();
        double x0 = player.x();
        player.tick(new astar.movement.Keys(true, false, false, false, true, false, true), -90,
                0);
        int t = 0;
        while (!player.onGround() && t++ < 40) {
            player.tick(run, -90, 0);
        }
        double predicted = Follower.jumpTravel(v, 0, true, 0.098 * 1.3, 0.026);
        // Within a tick's push: good enough to pick the tick to jump on.
        assertEquals(player.x() - x0, predicted, 0.05);
    }

    @Test
    void aRecordedStretchPlaysBackToTheSameTrack() {
        List<PathStep> p = path(0, 0, 8, 0, 8, 8, 16, 8);
        World w = new World().corridor(p);
        ExecutionPlan plan = new PlanBuilder(w).build(p);
        SimRun.Result r = SimRun.run(w, plan, Executor.Settings.DEFAULT, 2000, true);
        SimRun.Recording rec = r.stretches().get(0).recording();
        // Played again from its start, as the game will, through the text form.
        Scenario script = Scenario.parse("t", rec.script().toText());
        Scenario.Start a = script.start();
        assertTrue(a.world());
        SimulatedPlayer player = new SimulatedPlayer(w, SimulatedPlayer.Attributes.PLAYER,
                a.x(), a.y(), a.z(), a.yaw(), true);
        assertEquals(rec.track().size(), script.ticks());
        for (int i = 0; i < script.ticks(); i++) {
            Scenario.Frame f = script.frames().get(i);
            player.tick(f.keys(), a.yaw() + f.yaw(), f.pitch());
            double[] q = rec.track().get(i);
            assertEquals(q[0], player.x(), 0, "x on tick " + i);
            assertEquals(q[1], player.y(), 0, "y on tick " + i);
            assertEquals(q[2], player.z(), 0, "z on tick " + i);
        }
    }
}
