package astar.viz;

import astar.core.BlockPoint;
import astar.movement.exec.Follower;
import astar.movement.exec.PlayerController;
import astar.movement.exec.SimRun;
import astar.movement.plan.ExecutionPlan;
import astar.movement.sim.TraceReplay;
import astar.movement.trace.TickRecord;
import astar.movement.trace.Trace;
import astar.movement.trace.Vec3;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Scores how closely the real game follows the executor: each stretch the executor walked in
 * the simulator is played in the game with the same keys and camera (from the scripts {@code
 * route --script} writes), and the recorded trace is compared with the simulated run.
 *
 * <p>Per stretch it reports:
 * <ul>
 *   <li><b>drift</b>: how far the real player got from the simulated one across the ground,
 *       at worst and at the end, and where it first parted by more than {@link #SPLIT}.
 *       This is everything together: the simulator's physics, and the difference
 *       between the real blocks and the pathfinder's simplified ones (stairs as half blocks,
 *       fences as full-width posts).
 *   <li><b>off route</b>: how far the real player got from the route's line, across the
 *       ground, against the room the plan gave it there.
 *   <li><b>end</b>: how far from the stretch's last step it stopped (0.3 counts as there).
 *   <li><b>replay</b>: the simulator run again on the blocks the trace recorded, from its
 *       first tick, with its inputs. Near zero means the physics is right, so any drift comes
 *       from the simplified blocks.
 * </ul>
 */
final class ChoreographyScore {

    /** Drift beyond this counts as the real player parting from the simulated one. */
    static final double SPLIT = 0.25;

    private ChoreographyScore() {}

    static String scriptName(int index) {
        return String.format("route-%02d", index);
    }

    /** Lines describing the scores of the traces in {@code dir}, one per stretch. */
    static List<String> score(SimRun.Result run, ExecutionPlan plan, BlockPoint origin, Path dir)
            throws IOException {
        List<String> out = new ArrayList<>();
        out.add(String.format("%-9s %-11s %6s %16s %14s %8s %9s", "stretch", "steps", "ticks",
                "drift worst/end", "off route/room", "end", "replay"));
        int index = 0;
        int scored = 0;
        int arrived = 0;
        double worstDrift = 0;
        double worstReplay = 0;
        for (SimRun.Stretch s : run.stretches()) {
            if (s.recording() == null) {
                continue;
            }
            String name = scriptName(index++);
            Optional<Path> file = newest(dir, name);
            if (file.isEmpty()) {
                out.add(String.format("%-9s %-11s no trace", name, s.from() + "-" + s.to()));
                continue;
            }
            Trace trace = Trace.read(file.get());
            List<TickRecord> ticks = trace.ticks();
            List<double[]> track = s.recording().track();
            double drift = 0;
            double endDrift = 0;
            int split = -1;
            BlockPoint splitAt = null;
            double offRoute = 0;
            double room = Double.MAX_VALUE;
            Follower line = new Follower(plan, s.from(), s.to(), Follower.Settings.DEFAULT);
            int n = Math.min(ticks.size(), track.size());
            for (int i = 0; i < n; i++) {
                Vec3 p = ticks.get(i).pos();
                double x = p.x() - origin.x();
                double y = p.y() - origin.y();
                double z = p.z() - origin.z();
                double[] q = track.get(i);
                // Across the ground: heights differ wherever the simplified blocks do (stairs
                // are half blocks there), without the player being anywhere else.
                endDrift = Math.hypot(x - q[0], z - q[2]);
                drift = Math.max(drift, endDrift);
                if (split < 0 && endDrift > SPLIT) {
                    split = i;
                    splitAt = new BlockPoint((int) Math.floor(p.x()), (int) Math.floor(p.y()),
                            (int) Math.floor(p.z()));
                }
                line.locate(new PlayerController.Observation(x, y, z, 0, 0, 0, 0, 0, true,
                        false, false));
                offRoute = Math.max(offRoute, line.offset());
                ExecutionPlan.Node at = plan.nodes().get(line.node());
                room = Math.min(room, Math.min(at.left().width(), at.right().width()));
            }
            Vec3 last = ticks.isEmpty() ? null : ticks.get(ticks.size() - 1).pos();
            ExecutionPlan.Node end = plan.nodes().get(s.to());
            double endDistance = last == null ? Double.NaN
                    : Math.hypot(last.x() - origin.x() - end.x(), last.z() - origin.z() - end.z());
            TraceReplay.Result replay = TraceReplay.replay(trace, false, 1e-6);
            scored++;
            arrived += endDistance <= Follower.GOAL_RADIUS ? 1 : 0;
            worstDrift = Math.max(worstDrift, drift);
            worstReplay = Math.max(worstReplay, replay.maxPositionError());
            out.add(String.format("%-9s %-11s %6d %7.3f / %6.3f %6.2f / %5.2f %8.2f %9.1e%s%s%s",
                    name, s.from() + "-" + s.to(), ticks.size(), drift, endDrift, offRoute,
                    room, endDistance, replay.maxPositionError(),
                    split < 0 ? "" : " (parted on tick " + split + " at " + splitAt + ")",
                    ticks.size() == track.size() ? "" : " (the trace has " + ticks.size()
                            + " ticks, the script " + track.size() + ")",
                    replay.unsupported().isEmpty() ? "" : " (" + replay.unsupported() + ")"));
        }
        out.add(String.format("scored %d stretches: %d stopped within %.1f of their end; worst"
                + " drift from the simulator %.3f blocks; worst replay error %.1e blocks",
                scored, arrived, Follower.GOAL_RADIUS, worstDrift, worstReplay));
        return out;
    }

    /** The newest trace recorded from a script, or empty. */
    private static Optional<Path> newest(Path dir, String name) throws IOException {
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(f -> {
                String n = f.getFileName().toString();
                return n.startsWith(name + "-") && (n.endsWith(".jsonl")
                        || n.endsWith(".jsonl.gz"));
            }).max(Comparator.comparing(f -> f.getFileName().toString()));
        }
    }
}
