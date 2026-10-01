package astar.viz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import astar.core.BlockPoint;
import astar.core.MoveType;
import astar.core.PathStep;
import astar.movement.Scenario;
import astar.movement.exec.Executor;
import astar.movement.exec.SimRun;
import astar.movement.plan.ExecutionPlan;
import astar.movement.plan.PlanBuilder;
import astar.movement.trace.TickRecord;
import astar.movement.trace.Trace;
import astar.movement.trace.TraceHeader;
import astar.movement.trace.TraceWriter;
import astar.movement.trace.Vec3;
import astar.pathing.ArrayBlockView;
import astar.pathing.BlockType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChoreographyScoreTest {

    private static final BlockPoint ORIGIN = new BlockPoint(100, 60, -200);

    /** A corridor-free floor and an L-shaped route across it, walked in the simulator. */
    private static Object[] walked() {
        ArrayBlockView w = new ArrayBlockView(16, 4, 16);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                w.set(x, 0, z, BlockType.SOLID);
            }
        }
        List<PathStep> path = new ArrayList<>();
        path.add(new PathStep(new BlockPoint(2, 1, 2), null));
        for (int x = 3; x <= 10; x++) {
            path.add(new PathStep(new BlockPoint(x, 1, 2), MoveType.WALK));
        }
        for (int z = 3; z <= 10; z++) {
            path.add(new PathStep(new BlockPoint(10, 1, z), MoveType.WALK));
        }
        BlockTypeWorld world = new BlockTypeWorld(w);
        ExecutionPlan plan = new PlanBuilder(world).build(path);
        SimRun.Result run = SimRun.run(world, plan, Executor.Settings.DEFAULT, 2000, true);
        return new Object[] {plan, run};
    }

    /** Writes a trace of the recorded stretch, in world coordinates, shifted sideways by dz. */
    private static void writeTrace(Path file, SimRun.Recording rec, double dz)
            throws IOException {
        try (TraceWriter out = new TraceWriter(Files.newBufferedWriter(file))) {
            out.header(new TraceHeader(Trace.FORMAT, "test", "route-00", "now", Map.of()));
            Scenario.Start a = rec.script().start();
            Vec3 before = new Vec3(a.x() + ORIGIN.x(), a.y() + ORIGIN.y(), a.z() + ORIGIN.z());
            for (int i = 0; i < rec.track().size(); i++) {
                double[] q = rec.track().get(i);
                double shift = i >= rec.track().size() / 2 ? dz : 0;
                Vec3 p = new Vec3(q[0] + ORIGIN.x(), q[1] + ORIGIN.y(),
                        q[2] + ORIGIN.z() + shift);
                Scenario.Frame f = rec.script().frames().get(i);
                out.tick(new TickRecord(i, f.keys(), 0, 0, a.yaw() + f.yaw(), f.pitch(),
                        before, new Vec3(0, 0, 0), p, new Vec3(0, 0, 0), true, false, true,
                        false, false, false, false, 0));
                before = p;
            }
        }
    }

    @Test
    void aTraceThatFollowsTheSimulationExactlyScoresNoDrift(@TempDir Path dir)
            throws IOException {
        Object[] w = walked();
        ExecutionPlan plan = (ExecutionPlan) w[0];
        SimRun.Result run = (SimRun.Result) w[1];
        assertEquals(1, run.stretches().size());
        writeTrace(dir.resolve("route-00-20260101-000000.jsonl"),
                run.stretches().get(0).recording(), 0);
        List<String> lines = ChoreographyScore.score(run, plan, ORIGIN, dir);
        String row = lines.get(1);
        assertTrue(row.startsWith("route-00"), row);
        assertTrue(row.contains("  0.000 /  0.000"), row);
        assertTrue(!row.contains("parted"), row);
        assertTrue(lines.get(2).startsWith("scored 1 stretches: 1 stopped within 0.3"),
                lines.get(2));
    }

    @Test
    void driftSaysWhereThePlayerParted(@TempDir Path dir) throws IOException {
        Object[] w = walked();
        ExecutionPlan plan = (ExecutionPlan) w[0];
        SimRun.Result run = (SimRun.Result) w[1];
        SimRun.Recording rec = run.stretches().get(0).recording();
        writeTrace(dir.resolve("route-00-20260101-000000.jsonl"), rec, 0.5);
        String row = ChoreographyScore.score(run, plan, ORIGIN, dir).get(1);
        assertTrue(row.contains("  0.500 /  0.500"), row);
        assertTrue(row.contains("parted on tick " + rec.track().size() / 2), row);
    }

    @Test
    void aMissingTraceIsSaidSo(@TempDir Path dir) throws IOException {
        Object[] w = walked();
        List<String> lines = ChoreographyScore.score((SimRun.Result) w[1],
                (ExecutionPlan) w[0], ORIGIN, dir);
        assertTrue(lines.get(1).contains("no trace"), lines.get(1));
    }
}
