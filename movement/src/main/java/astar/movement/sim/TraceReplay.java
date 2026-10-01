package astar.movement.sim;

import astar.movement.trace.Trace;
import astar.movement.trace.TickRecord;
import astar.movement.trace.Vec3;
import java.util.Map;

/**
 * Plays a recorded trace's inputs through {@link SimulatedPlayer} and measures how far the
 * simulation drifts from what the game did.
 */
public final class TraceReplay {

    private TraceReplay() {}

    /**
     * How a replay compared with the recording.
     *
     * @param ticks how many ticks were compared
     * @param maxPositionError the largest distance between simulated and recorded position
     * @param maxVelocityError the same, for velocity
     * @param firstDivergentTick the first tick whose position or velocity was off by more than
     *     the tolerance, or -1
     * @param firstDivergence what differed on that tick, or {@code ""}
     * @param unsupported the first thing met that the simulator doesn't model, or {@code ""}
     */
    public record Result(int ticks, double maxPositionError, double maxVelocityError,
            long firstDivergentTick, String firstDivergence, String unsupported) {

        public boolean matches() {
            return firstDivergentTick < 0;
        }
    }

    /**
     * Replays a trace.
     *
     * @param resync when true, each tick starts from the recorded position and velocity (the
     *     other state stays the simulation's own), so position errors don't add up; when false, the simulation runs
     *     freely from the first tick
     * @param tolerance how far off a position or velocity may be before a tick counts as
     *     divergent
     */
    public static Result replay(Trace trace, boolean resync, double tolerance) {
        if (trace.ticks().isEmpty()) {
            return new Result(0, 0, 0, -1, "", "");
        }
        Map<String, Double> a = trace.header().attributes();
        SimulatedPlayer.Attributes attributes = new SimulatedPlayer.Attributes(
                a.getOrDefault("minecraft:movement_speed", 0.1),
                a.getOrDefault("minecraft:jump_strength", 0.42),
                a.getOrDefault("minecraft:step_height", 0.6),
                a.getOrDefault("minecraft:gravity", 0.08),
                a.getOrDefault("minecraft:sneaking_speed", 0.3),
                a.getOrDefault("minecraft:friction_modifier", 1.0),
                a.getOrDefault("minecraft:air_drag_modifier", 1.0));
        TickRecord first = trace.ticks().get(0);
        SimulatedPlayer.State start = new SimulatedPlayer.State(
                first.posBefore().x(), first.posBefore().y(), first.posBefore().z(),
                first.velBefore().x(), first.velBefore().y(), first.velBefore().z(),
                first.yaw(), first.pitch(),
                flag(a, "state:on_ground"), flag(a, "state:horizontal_collision"),
                flag(a, "state:sprinting"), flag(a, "state:crouching"),
                flag(a, "state:sneak_key"), a.getOrDefault("state:fall_distance", 0.0));
        int food = (int) (double) a.getOrDefault("state:food", 20.0);
        SimulatedPlayer p = new SimulatedPlayer(new TraceWorld(trace), attributes, food, start);

        double maxPos = 0;
        double maxVel = 0;
        long divergent = -1;
        String divergence = "";
        String unsupported = "";
        for (TickRecord t : trace.ticks()) {
            if (resync) {
                p.setPositionAndVelocity(t.posBefore().x(), t.posBefore().y(),
                        t.posBefore().z(), t.velBefore().x(), t.velBefore().y(),
                        t.velBefore().z());
            }
            p.tick(t.keys(), t.yaw(), t.pitch());
            if (p.unsupported() != null && unsupported.isEmpty()) {
                unsupported = "tick " + t.tick() + ": " + p.unsupported();
            }
            double dp = new Vec3(p.x(), p.y(), p.z()).minus(t.pos()).length();
            double dv = new Vec3(p.vx(), p.vy(), p.vz()).minus(t.vel()).length();
            maxPos = Math.max(maxPos, dp);
            maxVel = Math.max(maxVel, dv);
            String flags = flagDifference(p, t);
            if (divergent < 0 && (dp > tolerance || dv > tolerance || !flags.isEmpty())) {
                divergent = t.tick();
                divergence = String.format(
                        "position off by %.3g (sim %.6f %.6f %.6f, game %.6f %.6f %.6f), "
                                + "velocity off by %.3g (sim %.6f %.6f %.6f, game %.6f %.6f %.6f)%s",
                        dp, p.x(), p.y(), p.z(), t.pos().x(), t.pos().y(), t.pos().z(), dv,
                        p.vx(), p.vy(), p.vz(), t.vel().x(), t.vel().y(), t.vel().z(), flags);
            }
        }
        return new Result(trace.ticks().size(), maxPos, maxVel, divergent, divergence,
                unsupported);
    }

    private static String flagDifference(SimulatedPlayer p, TickRecord t) {
        StringBuilder b = new StringBuilder();
        if (p.onGround() != t.onGround()) {
            b.append(", on ground ").append(p.onGround()).append(" vs ").append(t.onGround());
        }
        if (p.horizontalCollision() != t.horizontalCollision()) {
            b.append(", wall hit ").append(p.horizontalCollision()).append(" vs ")
                    .append(t.horizontalCollision());
        }
        if (p.sprinting() != t.sprinting()) {
            b.append(", sprinting ").append(p.sprinting()).append(" vs ").append(t.sprinting());
        }
        return b.toString();
    }

    private static boolean flag(Map<String, Double> a, String key) {
        return a.getOrDefault(key, 0.0) != 0.0;
    }
}
