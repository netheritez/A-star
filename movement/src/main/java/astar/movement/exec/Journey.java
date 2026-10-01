package astar.movement.exec;

import astar.core.BlockPoint;
import astar.core.PathStep;
import astar.movement.Keys;
import astar.movement.plan.ExecutionPlan;
import astar.movement.plan.PlanBuilder;
import astar.movement.plan.StraightRoute;
import astar.movement.sim.SimulatedPlayer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Gets a player to a goal and back on track when something gets in the way: the
 * {@link Executor} walks the plan, and this watches over it.
 *
 * <ul>
 *   <li><b>Monitor.</b> Each tick it predicts where the player will be with the simulator
 *       (which matches the game to the bit on the blocks it knows) and compares. A small
 *       miss, in place or speed, is a push; a jump of blocks is a lag-back or teleport.
 *   <li><b>Path watch.</b> Every few ticks it checks the next steps can still be walked, so
 *       a block placed on the path or a door shut across it is seen before the player gets
 *       there.
 *   <li><b>Recovery.</b> When the player is off the route, got moved, or the way ahead is
 *       blocked, it first finds a short way back onto the route a few steps ahead (rejoin),
 *       and only if there's none plans the whole way again from where the player is. When
 *       the player is stuck, the step it couldn't reach is treated as a wall from then on.
 *       In the air it waits for the landing. After {@link Settings#maxRecoveries} tries
 *       without getting any further along, it gives up.
 * </ul>
 */
public final class Journey {

    public enum Status {
        RUNNING,
        /** At the goal. */
        ARRIVED,
        /** Stopped in front of a ladder or water, which the executor doesn't do yet. */
        UNSUPPORTED,
        /** No way to the goal any more, or recoveries kept failing; see {@link #reason()}. */
        FAILED
    }

    /** What happened on the way, counted in {@link #events()}. */
    public enum Event {
        /** The player moved differently from the prediction: pushed, hit, slowed. */
        DISTURBED,
        /** The player moved blocks away in a tick: a lag-back or a teleport. */
        TELEPORTED,
        /** A step ahead can't be walked any more. */
        BLOCKED,
        /** The executor found itself off the route. */
        OFF_COURSE,
        /** The executor made no progress. */
        STUCK,
        /** Got back onto the route a few steps ahead with a short path. */
        REJOINED,
        /** Planned the whole way again. */
        REROUTED
    }

    /**
     * How it recovers.
     *
     * @param rejoinAhead how many steps past the nearest one a rejoin aims for; it tries twice
     *     and three times as far too
     * @param rejoinBudget the most nodes a rejoin search may expand
     * @param watchAhead how many steps ahead the path watch checks
     * @param watchEvery how often it checks, in ticks
     * @param maxRecoveries recoveries in a row without getting further along before giving up
     * @param disturbance how far the player may land from the prediction before it counts as
     *     a disturbance, in blocks (and its speed, in blocks per tick)
     * @param teleport how far before it counts as a lag-back or teleport
     */
    public record Settings(Executor.Settings executor, int rejoinAhead, int rejoinBudget,
            int watchAhead, int watchEvery, int maxRecoveries, double disturbance,
            double teleport, boolean straightLines) {

        public static final Settings DEFAULT = new Settings(Executor.Settings.DEFAULT, 6, 4000,
                24, 5, 6, 0.05, 1.5, true);

        /** The same, with the mouse moved by this hand. */
        public Settings withHand(AimController.Hand hand) {
            return new Settings(executor.withHand(hand), rejoinAhead, rejoinBudget, watchAhead,
                    watchEvery, maxRecoveries, disturbance, teleport, straightLines);
        }

        /** The same, walking the grid steps as found instead of the smoothed lines, or not. */
        public Settings withStraightLines(boolean on) {
            return new Settings(executor, rejoinAhead, rejoinBudget, watchAhead, watchEvery,
                    maxRecoveries, disturbance, teleport, on);
        }
    }

    /** Why it's recovering. */
    private enum Cause {
        MOVED, BLOCKED, OFF_COURSE, STUCK
    }

    /** Cells next to one, nearest first. */
    private static final int[][] AROUND = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1},
        {1, 0, 1}, {1, 0, -1}, {-1, 0, 1}, {-1, 0, -1}, {0, -1, 0}, {1, -1, 0}, {-1, -1, 0},
        {0, -1, 1}, {0, -1, -1}, {0, 1, 0}, {1, 1, 0}, {-1, 1, 0}, {0, 1, 1}, {0, 1, -1}};

    private final Router router;
    private final PlayerController player;
    private final BlockPoint goal;
    private final Settings settings;
    private final Recorder recorder;
    private final Set<BlockPoint> avoid = new HashSet<>();
    private final Map<Event, Integer> events = new EnumMap<>(Event.class);
    private final List<String> log = new ArrayList<>();
    private List<PathStep> path;
    private ExecutionPlan plan;
    private Executor executor;
    private Status status = Status.RUNNING;
    private String reason = "";
    private int ticks;
    private int recoveries;
    /** Steps left to the goal at the best point so far, to tell progress from going round. */
    private int bestLeft = Integer.MAX_VALUE;
    /** A recovery waiting for the player to land. */
    private Cause pending;
    private int blockedAt = -1;
    private double[] predicted;
    private int rejoinLength;

    /** Plans from where the player is to the goal; fails at once if there's no path. */
    public Journey(Router router, PlayerController player, BlockPoint goal, Settings settings) {
        this(router, player, goal, (List<PathStep>) null, settings);
    }

    /**
     * Follows a path already found to the goal.
     *
     * @param path the steps from the player's cell to the goal, or null to plan them now
     */
    public Journey(Router router, PlayerController player, BlockPoint goal,
            List<PathStep> path, Settings settings) {
        this.router = router;
        this.player = player;
        this.goal = goal;
        this.settings = settings;
        this.recorder = new Recorder(player);
        if (path != null && !path.isEmpty()) {
            follow(path);
        } else {
            PlayerController.Observation p = player.observe();
            BlockPoint start = router.standingAt(p.x(), p.y(), p.z());
            List<PathStep> found = start == null ? List.of()
                    : router.find(start, goal, avoid, 0);
            if (found.isEmpty()) {
                fail(start == null ? "not standing anywhere to start from" : "no path");
            } else {
                follow(found);
            }
        }
    }

    /**
     * Follows a path already found and prepared ({@link #prepare}), at the speed the player
     * moves now.
     */
    public Journey(Router router, PlayerController player, BlockPoint goal, Prepared prepared,
            Settings settings) {
        this.router = router;
        this.player = player;
        this.goal = goal;
        this.settings = settings;
        this.recorder = new Recorder(player);
        follow(prepared);
    }

    public Status status() {
        return status;
    }

    /** Why it failed, or an empty string. */
    public String reason() {
        return reason;
    }

    /** How often each thing happened. */
    public Map<Event, Integer> events() {
        return events;
    }

    public int count(Event e) {
        return events.getOrDefault(e, 0);
    }

    /** One line per recovery: when, why and what it did. */
    public List<String> log() {
        return log;
    }

    public int ticks() {
        return ticks;
    }

    /** The path it's following now. */
    public List<PathStep> path() {
        return path;
    }

    public ExecutionPlan plan() {
        return plan;
    }

    public Executor executor() {
        return executor;
    }

    /** Cells it treats as walls because the player got stuck on the way to them. */
    public Set<BlockPoint> avoided() {
        return avoid;
    }

    /** Plays one tick. */
    public Status tick() {
        if (status != Status.RUNNING) {
            return status;
        }
        ticks++;
        PlayerController.Observation p = player.observe();
        boolean moved = watchMonitor(p);
        if (pending == null && ticks % settings.watchEvery() == 0) {
            int node = executor.follower().node();
            int blocked = router.firstBlocked(path, node,
                    Math.min(path.size() - 1, node + settings.watchAhead()));
            if (blocked >= 0) {
                count(Event.BLOCKED, "step " + blocked + " " + path.get(blocked).pos()
                        + " can't be walked now");
                blockedAt = blocked;
                pending = Cause.BLOCKED;
            }
        }
        if (moved && pending == null) {
            // Carry on if the executor still finds the player on the route; if not, it says so.
            executor.follower().locate(p);
            double room = executor.follower().narrowest(executor.follower().progress(),
                    executor.follower().progress());
            if (executor.follower().offset() > room) {
                pending = Cause.MOVED;
            }
        }
        if (pending != null) {
            if (!p.onGround()) {
                // Nothing to steer with in the air: let go and wait for the landing.
                recorder.tick(Keys.NONE, p.yaw(), p.pitch());
                predict(p);
                return status;
            }
            Cause c = pending;
            pending = null;
            recover(p, c);
            return status;
        }
        Executor.Status s = executor.tick();
        switch (s) {
            case RUNNING -> {
                predict(p);
                if (p.onGround() && fellOff(p)) {
                    count(Event.OFF_COURSE, "landed at height " + fmt(p.y()) + ", away from"
                            + " the route's floors near step " + executor.follower().node());
                    pending = Cause.OFF_COURSE;
                }
                int left = path.size() - 1 - executor.follower().node();
                if (left < bestLeft) {
                    bestLeft = left;
                    recoveries = 0;
                }
            }
            case ARRIVED -> status = Status.ARRIVED;
            case UNSUPPORTED -> status = Status.UNSUPPORTED;
            case OFF_COURSE -> {
                count(Event.OFF_COURSE, "off the route by " + fmt(executor.follower().offset()));
                pending = Cause.OFF_COURSE;
            }
            case STUCK -> {
                int next = Math.min(executor.follower().node() + 1, path.size() - 1);
                BlockPoint cell = path.get(next).pos();
                count(Event.STUCK, "no progress toward step " + next + " " + cell);
                if (!cell.equals(goal)) {
                    avoid.add(cell);
                }
                pending = Cause.STUCK;
            }
        }
        return status;
    }

    /** Ticks until it finishes or {@code maxTicks} pass. */
    public Status run(int maxTicks) {
        for (int i = 0; i < maxTicks && status == Status.RUNNING; i++) {
            tick();
        }
        return status;
    }

    /**
     * Whether the player is on the ground at a height the route doesn't go near here: fell
     * off an edge (the executor measures the route across the ground, so it can't tell).
     */
    private boolean fellOff(PlayerController.Observation p) {
        int node = executor.follower().node();
        List<ExecutionPlan.Node> nodes = plan.nodes();
        for (int i = Math.max(0, node - 1); i <= Math.min(nodes.size() - 1, node + 3); i++) {
            if (Math.abs(nodes.get(i).y() - p.y()) <= 1.3) {
                return false;
            }
        }
        return true;
    }

    /** Compares the player with last tick's prediction; whether it was moved by something. */
    private boolean watchMonitor(PlayerController.Observation p) {
        if (predicted == null) {
            return false;
        }
        double miss = Math.sqrt(sq(p.x() - predicted[0]) + sq(p.y() - predicted[1])
                + sq(p.z() - predicted[2]));
        // A hit changes the velocity first; the position follows a tick later.
        double kick = Math.sqrt(sq(p.vx() - predicted[3]) + sq(p.vy() - predicted[4])
                + sq(p.vz() - predicted[5]));
        predicted = null;
        if (miss > settings.teleport()) {
            count(Event.TELEPORTED, "moved " + fmt(miss) + " blocks in a tick");
            return true;
        }
        if (miss > settings.disturbance() || kick > settings.disturbance()) {
            events.merge(Event.DISTURBED, 1, Integer::sum);
            return true;
        }
        return false;
    }

    /** The double tap of forward after the last predicted tick, and which tick that was. */
    private boolean tapForward;
    private int tapTicks;
    private int tapTick = -2;

    /** Where the player will be after the tick just played, if nothing else moves it. */
    private void predict(PlayerController.Observation p) {
        if (recorder.keys == null) {
            return;
        }
        boolean sneak = recorder.lastSneak;
        SimulatedPlayer sim = new SimulatedPlayer(router.world(), attributes(), 20,
                new SimulatedPlayer.State(p.x(), p.y(), p.z(), p.vx(), p.vy(), p.vz(), p.yaw(),
                        p.pitch(), p.onGround(), p.horizontalCollision(), p.sprinting(), sneak,
                        sneak, p.fallDistance()));
        // A glancing bump into a wall doesn't stop a sprint.
        sim.setSoftCollision(p.horizontalCollision() && p.softCollision());
        if (tapTick == ticks - 1) {
            // Letting go of forward and pressing it again soon after starts a sprint.
            sim.setDoubleTap(tapForward, tapTicks);
        }
        sim.tick(recorder.keys, recorder.yaw, recorder.pitch);
        tapForward = sim.forwardHeld();
        tapTicks = sim.doubleTapTicks();
        tapTick = ticks;
        predicted = new double[] {sim.x(), sim.y(), sim.z(), sim.vx(), sim.vy(), sim.vz()};
    }

    private void recover(PlayerController.Observation p, Cause cause) {
        if (++recoveries > settings.maxRecoveries()) {
            fail("tried " + settings.maxRecoveries() + " times to get past step "
                    + executor.follower().node() + " without getting further");
            return;
        }
        router.refresh();
        BlockPoint start = router.standingAt(p.x(), p.y(), p.z());
        if (start == null) {
            // On an edge or a block that isn't in the grid: step back onto the route first.
            fail("not standing anywhere the pathfinder knows at " + fmt(p.x()) + ", "
                    + fmt(p.y()) + ", " + fmt(p.z()));
            return;
        }
        List<PathStep> rejoin = cause == Cause.STUCK ? List.of() : rejoin(start, cause);
        if (!rejoin.isEmpty()) {
            count(Event.REJOINED, "from " + start + " back onto the route at "
                    + rejoin.get(rejoinLength).pos() + " in " + rejoinLength + " steps");
            follow(rejoin);
            return;
        }
        List<PathStep> whole = router.find(start, goal, avoid, 0);
        if (whole.isEmpty() && !avoid.isEmpty()) {
            // The cells it got stuck on may be the only way; try them again.
            avoid.clear();
            whole = router.find(start, goal, avoid, 0);
        }
        for (int i = 0; whole.isEmpty() && i < AROUND.length; i++) {
            // A spot the grid has no way out of though the player stands there (on top of a
            // shape the grid rounds off): start from a cell next to it instead.
            int[] d = AROUND[i];
            whole = router.find(new BlockPoint(start.x() + d[0], start.y() + d[1],
                    start.z() + d[2]), goal, avoid, 0);
        }
        if (whole.isEmpty()) {
            fail("no path to the goal from " + start + " now");
            return;
        }
        count(Event.REROUTED, "from " + start + ", " + (whole.size() - 1) + " steps");
        follow(whole);
    }

    /**
     * A short path from the player back onto the route, spliced onto the rest of it: to a
     * step a few past the nearest one (past the blocked one when the way is blocked), whose
     * way on from there is still open.
     */
    private List<PathStep> rejoin(BlockPoint start, Cause cause) {
        int near = nearest(start);
        int from = cause == Cause.BLOCKED ? Math.max(near, blockedAt) : near;
        int last = path.size() - 1;
        for (int k = 1; k <= 3; k++) {
            int target = Math.min(last, from + settings.rejoinAhead() * k);
            if (router.firstBlocked(path, target,
                    Math.min(last, target + settings.watchAhead())) >= 0) {
                continue;
            }
            List<PathStep> way = router.find(start, path.get(target).pos(), avoid,
                    settings.rejoinBudget());
            if (!way.isEmpty()) {
                rejoinLength = way.size() - 1;
                List<PathStep> spliced = new ArrayList<>(way);
                spliced.addAll(path.subList(target + 1, path.size()));
                return spliced;
            }
            if (target == last) {
                break;
            }
        }
        return List.of();
    }

    /** The step of the current path nearest a cell, looking around where the executor is. */
    private int nearest(BlockPoint c) {
        int node = executor.follower().node();
        int best = node;
        double bestD = Double.MAX_VALUE;
        for (int i = Math.max(0, node - 16); i < Math.min(path.size(), node + 48); i++) {
            BlockPoint q = path.get(i).pos();
            double d = sq(q.x() - c.x()) + sq(q.z() - c.z()) + 4 * sq(q.y() - c.y());
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    /**
     * A path made ready to walk: laid along lines and planned, which takes a while on a long
     * route. It only reads the router's world, so it can be made off the game thread ahead of
     * the journey ({@link #Journey(Router, PlayerController, BlockPoint, Prepared, Settings)}).
     */
    public record Prepared(List<PathStep> path, ExecutionPlan plan) {}

    /**
     * Lays the steps along lines and plans them.
     *
     * @param speed how many times a normal player's ground speed the player moves
     */
    public static Prepared prepare(Router router, List<PathStep> steps, Settings settings,
            double speed) {
        // Walk the smoothed lines where the router can lay them: straight at any angle, not
        // the grid's zigzag.
        StraightRoute lines = settings.straightLines() ? router.straighten(steps) : null;
        List<PathStep> path = List.copyOf(lines != null ? lines.steps() : steps);
        PlanBuilder builder = new PlanBuilder(router.world(), speed);
        return new Prepared(path, lines != null ? builder.build(lines) : builder.build(path));
    }

    private void follow(List<PathStep> steps) {
        follow(prepare(router, steps, settings, speed()));
    }

    private void follow(Prepared prepared) {
        path = prepared.path();
        plan = prepared.plan();
        Executor was = executor;
        executor = new Executor(recorder, plan, 0, settings.executor());
        if (was != null) {
            executor.carryCamera(was);
        }
        predicted = null;
        bestLeft = Math.min(bestLeft, path.size() - 1);
    }

    /** The player's attributes, with its movement speed as it is now (Speed, Slowness). */
    private SimulatedPlayer.Attributes attributes() {
        SimulatedPlayer.Attributes a = SimulatedPlayer.Attributes.PLAYER;
        double speed = player.movementSpeed();
        return speed == a.movementSpeed() ? a : new SimulatedPlayer.Attributes(speed,
                a.jumpStrength(), a.stepHeight(), a.gravity(), a.sneakingSpeed(),
                a.frictionModifier(), a.airDragModifier());
    }

    /** How many times a normal player's ground speed the player moves now. */
    private double speed() {
        return player.movementSpeed() / SimulatedPlayer.Attributes.PLAYER.movementSpeed();
    }

    private void count(Event e, String what) {
        events.merge(e, 1, Integer::sum);
        log.add("tick " + ticks + ": " + e.name().toLowerCase().replace('_', ' ') + ", "
                + what);
    }

    private void fail(String why) {
        status = Status.FAILED;
        reason = why;
        log.add("tick " + ticks + ": gave up, " + why);
    }

    private static double sq(double v) {
        return v * v;
    }

    private static String fmt(double v) {
        return String.format("%.2f", v);
    }

    /** Passes ticks on to the player, remembering what was played to predict it. */
    private static final class Recorder implements PlayerController {
        private final PlayerController player;
        private Keys keys;
        private float yaw;
        private float pitch;
        private boolean lastSneak;

        Recorder(PlayerController player) {
            this.player = player;
        }

        @Override
        public Observation observe() {
            return player.observe();
        }

        @Override
        public void tick(Keys keys, float yaw, float pitch) {
            lastSneak = this.keys != null && this.keys.sneak();
            this.keys = keys;
            this.yaw = yaw;
            this.pitch = pitch;
            player.tick(keys, yaw, pitch);
        }
    }
}
