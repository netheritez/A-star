package astar.movement.exec;

import astar.movement.Keys;
import astar.movement.Scenario;
import astar.movement.plan.ExecutionPlan;
import astar.movement.plan.ExecutionPlan.Node;
import astar.movement.sim.SimWorld;
import astar.movement.sim.SimulatedPlayer;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs a whole plan in the simulator, stretch by stretch. Where the executor stops in front of
 * a move it can't do yet (a ladder, water), the player is put down at the end of that move,
 * standing still and facing along the route, and the next stretch starts there.
 * The result says how each stretch went.
 */
public final class SimRun {

    private SimRun() {}

    /**
     * How one stretch ended.
     *
     * @param from the node it started at
     * @param to the node it was going to
     * @param status how it ended
     * @param ticks game ticks it took
     * @param where the node the player was on or just past at the end
     */
    public record Stretch(int from, int to, Executor.Status status, int ticks, int where,
            Recording recording) {}

    /**
     * What the executor did on a stretch, to play again elsewhere (the game), and where the
     * simulated player went.
     *
     * @param script the start (the stretch's first node, in the plan's coordinates) and the
     *     keys and camera of every tick, with yaws relative to the start's
     * @param track the simulated feet position after each tick, as {x, y, z}
     */
    public record Recording(Scenario script, List<double[]> track) {}

    /**
     * The whole run.
     *
     * @param maxOffset the furthest the player got from the route's line, across the ground
     * @param bumps ticks that ended against a wall
     * @param skipped moves it put the player past instead of doing them
     */
    public record Result(List<Stretch> stretches, int ticks, double maxOffset, int bumps,
            int skipped) {

        /** Stretches that didn't end where they were meant to. */
        public List<Stretch> failures() {
            return stretches.stream().filter(s -> s.status() != Executor.Status.ARRIVED
                    && s.status() != Executor.Status.UNSUPPORTED).toList();
        }
    }

    /** Runs the plan with a player that starts at its first node. */
    public static Result run(SimWorld world, ExecutionPlan plan, Executor.Settings settings,
            int maxTicksPerStretch) {
        return run(world, plan, settings, maxTicksPerStretch, false);
    }

    /**
     * Runs the plan with a player that starts at its first node.
     *
     * @param record whether to keep each stretch's {@link Recording}
     */
    public static Result run(SimWorld world, ExecutionPlan plan, Executor.Settings settings,
            int maxTicksPerStretch, boolean record) {
        List<Node> nodes = plan.nodes();
        List<Stretch> stretches = new ArrayList<>();
        int start = 0;
        int ticks = 0;
        int bumps = 0;
        int skipped = 0;
        double maxOffset = 0;
        while (start < nodes.size() - 1) {
            Node n = nodes.get(start);
            Node next = nodes.get(start + 1);
            float yaw = (float) Follower.yawToward(n.x(), n.z(), next.x(), next.z());
            SimulatedPlayer player = new SimulatedPlayer(world, SimulatedPlayer.Attributes.PLAYER,
                    n.x(), n.y(), n.z(), yaw, n.floor());
            List<Scenario.Frame> frames = new ArrayList<>();
            List<double[]> track = new ArrayList<>();
            SimController sim = new SimController(player);
            PlayerController controller = !record ? sim : new PlayerController() {
                @Override
                public Observation observe() {
                    return sim.observe();
                }

                @Override
                public void tick(Keys keys, float tickYaw, float pitch) {
                    // Played in the game as the start's yaw plus this, in floats as there.
                    frames.add(new Scenario.Frame(keys, tickYaw - yaw, pitch));
                    sim.tick(keys, yaw + (tickYaw - yaw), pitch);
                    track.add(new double[] {player.x(), player.y(), player.z()});
                }
            };
            Executor ex = new Executor(controller, plan, start, settings);
            if (ex.end() > start) {
                while (ex.status() == Executor.Status.RUNNING && ex.ticks() < maxTicksPerStretch) {
                    ex.tick();
                    maxOffset = Math.max(maxOffset, ex.follower().offset());
                    bumps += player.horizontalCollision() ? 1 : 0;
                }
                Executor.Status status = ex.status() == Executor.Status.RUNNING
                        ? Executor.Status.STUCK : ex.status();
                Recording recording = !record || frames.isEmpty() ? null : new Recording(
                        new Scenario("stretch-" + start, "nodes " + start + " to " + ex.end(),
                                new Scenario.Start(n.x(), n.y(), n.z(), yaw, true), frames),
                        track);
                stretches.add(new Stretch(start, ex.end(), status, ex.ticks(),
                        ex.follower().node(), recording));
                ticks += ex.ticks();
            }
            // Carry on from the end of this stretch, or of the move that stopped it; after a
            // failure, from the next node, so one bad spot doesn't hide the rest.
            if (ex.end() == nodes.size() - 1 && ex.status() == Executor.Status.ARRIVED) {
                break;
            }
            if (ex.end() > start && ex.status() != Executor.Status.UNSUPPORTED
                    && ex.status() != Executor.Status.ARRIVED) {
                start = Math.max(start + 1, ex.follower().node() + 1);
            } else {
                start = ex.end() + 1;
                skipped++;
            }
        }
        return new Result(stretches, ticks, maxOffset, bumps, skipped);
    }
}
