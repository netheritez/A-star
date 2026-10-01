package astar.movement.exec;

import astar.movement.Keys;
import astar.movement.plan.ExecutionPlan;
import astar.movement.plan.ExecutionPlan.Kind;
import astar.movement.plan.ExecutionPlan.Segment;
import java.util.SplittableRandom;

/**
 * Walks a player along an {@link ExecutionPlan}, one game tick at a time.
 *
 * <p>Each tick it reads the player, lets the {@link Follower} pick a yaw and keys, turns the
 * camera toward that yaw over the tick's frames with the {@link AimController}, and plays the
 * tick. It walks, jumps up and drops; it stops short of the first ladder or water, which come
 * in a later milestone, and reports {@link Status#UNSUPPORTED} there.
 */
public final class Executor {

    public enum Status {
        /** Still going. */
        RUNNING,
        /** Within {@link Follower#GOAL_RADIUS} of the end and all but stopped. */
        ARRIVED,
        /** Stopped at the start of a move this executor can't do yet. */
        UNSUPPORTED,
        /** Further from the route than the room beside it allows. */
        OFF_COURSE,
        /** No progress for {@link #STUCK_TICKS} ticks. */
        STUCK
    }

    /**
     * How it runs.
     *
     * @param framesPerTick rendered frames per game tick (3 at 60 frames a second)
     * @param pitch the camera pitch it holds, in degrees (positive looks down), when it
     *     doesn't look ahead
     * @param lookAhead whether the camera looks down the route at the {@link #gaze} point,
     *     further ahead the faster it goes, and moves unevenly the way a hand does
     * @param hand which hand moves the mouse, which shapes its big turns
     */
    public record Settings(Follower.Settings follower, AimController.Settings aim,
            int framesPerTick, float pitch, boolean lookAhead, AimController.Hand hand) {

        public static final Settings DEFAULT = new Settings(Follower.Settings.DEFAULT,
                AimController.Settings.DEFAULT, 3, 10, true);

        /** Moved with the right hand. */
        public Settings(Follower.Settings follower, AimController.Settings aim,
                int framesPerTick, float pitch, boolean lookAhead) {
            this(follower, aim, framesPerTick, pitch, lookAhead, AimController.Hand.RIGHT);
        }

        /** The same, moved with this hand. */
        public Settings withHand(AimController.Hand hand) {
            return new Settings(follower, aim, framesPerTick, pitch, lookAhead, hand);
        }

        /** A fixed pitch, no drift. */
        public Settings(Follower.Settings follower, AimController.Settings aim,
                int framesPerTick, float pitch) {
            this(follower, aim, framesPerTick, pitch, false);
        }
    }

    /** How far ahead the camera looks when standing, in blocks across the ground. */
    static final double GAZE_BASE = 2.5;
    /** And further per block a tick of speed: 4 blocks walking, 7 sprinting with Speed VII. */
    static final double GAZE_PER_SPEED = 7;
    /**
     * Whether the keys steer the body through turns while the camera looks further ahead;
     * {@code -Dastar.nostrafe=true} turns it off to compare.
     */
    static final boolean STRAFING = !Boolean.getBoolean("astar.nostrafe");
    /** How far off the camera a forward-and-side push goes, in degrees. */
    static final double STRAFE = 45;
    /** Strafing only this close to the route, in blocks. */
    static final double STRAFE_OFFSET = 0.6;
    /** Headings closer to the camera than this, in degrees, get no side key. */
    static final double STRAFE_DEAD = 10;
    /** How far ahead the camera looks while strafing, standing, in blocks. */
    static final double LOOK_BASE = 3;
    /** And further per block a tick of speed. */
    static final double LOOK_PER_SPEED = 6;
    /** How softly the camera follows its target while strafing, in seconds. */
    static final double SOFT_TAU = 0.3;
    /** The furthest the camera may be off the heading while strafing, in degrees. */
    static final double SOFT_REACH = 60;
    /** How long the camera takes to ease into looking further ahead, in seconds. */
    static final double LOOK_EASE = 0.25;
    /** The furthest the camera leads the heading while strafing, in degrees. */
    static final double STRAFE_LEAD = 40;
    /** The height of the eyes above the feet. */
    static final double EYE = 1.62;
    /** Where the gaze point sits above the floor (the middle of the lead marker). */
    static final double GAZE_LIFT = 0.08;
    /** How quickly the gaze point glides to where the route puts it, in seconds. */
    static final double GAZE_TAU = 0.12;
    /** Further than this from where it was, the gaze point jumps there (a new stretch). */
    static final double GAZE_JUMP = 3;
    static final double MIN_PITCH = -10;
    static final double MAX_PITCH = 45;
    /**
     * How stiffly the pitch follows where it looks, in radians a second: a calm, critically
     * damped follow that settles in about {@code 4 / PITCH_OMEGA} seconds.
     */
    static final double PITCH_OMEGA = 5;
    /** The fastest the pitch turns, in degrees a second. */
    static final double PITCH_RATE = 90;
    /** How quickly the pitch's turn can speed up or slow down, in degrees a second squared. */
    static final double PITCH_ACCEL = 400;
    /**
     * How far the camera drifts, in degrees (the spread): a hand's small, uneven wander, too
     * small to steer by.
     */
    static final double DRIFT_YAW = 0.35;
    static final double DRIFT_PITCH = 0.4;
    /** How long a drift lasts before it wanders elsewhere, in seconds. */
    static final double DRIFT_HOLD = 1.5;
    /** How smoothly the drift moves, in seconds: no jitter from tick to tick. */
    static final double DRIFT_SMOOTH = 0.25;
    /** Below this speed across the ground, in blocks a tick, a big turn may start late. */
    static final double PAUSE_SPEED = 0.06;

    static final int STUCK_TICKS = 60;
    private static final double TICK = 0.05;

    private final PlayerController player;
    private final ExecutionPlan plan;
    private final Settings settings;
    private final Follower follower;
    private final AimController aim;
    private final int end;
    private final Kind blockedBy;
    private Status status = Status.RUNNING;
    private double lastTarget = Double.NaN;
    private double best;
    private int sinceProgress;
    private int ticks;
    private Follower.Intent last;
    private double[] gaze;
    /** Where the player was when the gaze point was last placed. */
    private double[] gazeFrom;
    /** The camera's target while strafing, followed softly. */
    private double softTarget = Double.NaN;
    /** How much the camera looks further ahead than the heading, 0 to 1, eased. */
    private double lookWeight;
    /** How far the side-key pushes fell short of the heading wanted, carried to the next tick. */
    private double strafeCarry;
    /** How fast the pitch is turning, in degrees a second. */
    private double pitchRate;
    /** Where the camera's unevenness comes from, carried over from executor to executor. */
    private SplittableRandom hand;
    /** The drift's wander, yaw and pitch, each spread 1, and those smoothed. */
    private double wanderYaw;
    private double wanderPitch;
    private double driftYaw;
    private double driftPitch;
    /** How much of the drift is on: none in the air or on a jump, back over half a second. */
    private double drift;

    /** Follows the plan from its first node. */
    public Executor(PlayerController player, ExecutionPlan plan, Settings settings) {
        this(player, plan, 0, settings);
    }

    /** Follows the plan from node {@code start}, as far as it can go. */
    public Executor(PlayerController player, ExecutionPlan plan, int start, Settings settings) {
        this.player = player;
        this.plan = plan;
        this.settings = settings;
        int stop = plan.nodes().size() - 1;
        Kind kind = null;
        for (Segment s : plan.segments()) {
            if (s.to() > start && !supported(plan, s)) {
                stop = Math.max(start, s.from());
                kind = s.kind();
                break;
            }
        }
        end = stop;
        blockedBy = kind;
        follower = new Follower(plan, start, end, settings.follower());
        aim = new AimController(settings.aim());
        if (settings.lookAhead()) {
            // Different from trip to trip, the same every time for the same trip.
            ExecutionPlan.Node from = plan.nodes().get(Math.min(start, plan.nodes().size() - 1));
            hand = new SplittableRandom(31L * (31L * Double.hashCode(from.x())
                    + Double.hashCode(from.y())) + Double.hashCode(from.z()));
            aim.hand(hand);
        }
        aim.handed(settings.hand());
    }

    /**
     * Whether the executor does a segment: walks, jumps up and drops, from a floor to a floor.
     * Climbing and swimming come later, and so do getting on and off a ladder and into and out
     * of water, which start or end where there's no floor.
     */
    static boolean supported(ExecutionPlan plan, Segment s) {
        return s.kind() != Kind.CLIMB && s.kind() != Kind.SWIM
                && plan.nodes().get(s.from()).floor() && plan.nodes().get(s.to()).floor();
    }

    public Status status() {
        return status;
    }

    /** The last node this executor goes to: the plan's end, or where an unsupported move starts. */
    public int end() {
        return end;
    }

    /** The kind of move it stopped in front of, or null if it goes to the plan's end. */
    public Kind blockedBy() {
        return blockedBy;
    }

    public Follower follower() {
        return follower;
    }

    /** What the follower wanted on the last tick, or null before the first. */
    public Follower.Intent lastIntent() {
        return last;
    }

    public int ticks() {
        return ticks;
    }

    /**
     * Where the camera looks, {x, y, z} on the route with y on its floor (it looks a hair
     * above, at the middle of the lead marker), set each tick when
     * it looks ahead; null before the first tick or when it holds a fixed pitch.
     */
    public double[] gaze() {
        return gaze;
    }

    /**
     * Takes over the camera's motion from the executor this one replaces, turn and drift and
     * all, so a new plan partway (a recovery, the next stretch) doesn't jolt the view.
     */
    public void carryCamera(Executor from) {
        aim.carry(from.aim);
        pitchRate = from.pitchRate;
        lookWeight = from.lookWeight;
        softTarget = from.softTarget;
        strafeCarry = from.strafeCarry;
        drift = from.drift;
        if (from.hand != null) {
            hand = from.hand;
            aim.hand(hand);
        }
        wanderYaw = from.wanderYaw;
        wanderPitch = from.wanderPitch;
        driftYaw = from.driftYaw;
        driftPitch = from.driftPitch;
        gaze = from.gaze;
        gazeFrom = from.gazeFrom;
        lastTarget = from.lastTarget;
    }

    /**
     * Plays one tick, unless it has already finished.
     *
     * @return the status after the tick
     */
    public Status tick() {
        if (status != Status.RUNNING) {
            return status;
        }
        PlayerController.Observation p = player.observe();
        follower.locate(p);
        double room = follower.narrowest(follower.progress(), follower.progress());
        if (follower.offset() > room + 1.0) {
            return status = Status.OFF_COURSE;
        }
        // Slow enough to be sure of stopping within a few hundredths of a block.
        if (follower.toEnd(p) <= Follower.GOAL_RADIUS && p.groundSpeed() < 0.02
                && follower.length() - follower.progress() <= 1) {
            return status = done();
        }
        // On the ground with no jump or drop close ahead, the camera needn't point where the
        // player goes: forward with a side key pushes 45 degrees off it.
        // It eases into it (LOOK_EASE), so the camera doesn't jump from where the player goes
        // to further ahead, and stops at once: getting back after a push, the keys go where
        // the player goes, and the camera's own sweep keeps the turn back smooth.
        boolean free = settings.lookAhead() && STRAFING && p.onGround()
                && follower.upcoming(p) == null && follower.offset() < STRAFE_OFFSET;
        lookWeight = free ? Math.min(1, lookWeight + TICK / LOOK_EASE) : 0;
        boolean strafe = lookWeight > 0;
        follower.strafe(strafe);
        Follower.Intent intent = follower.decide(p);
        last = intent;
        double heading = p.yaw() + AimController.wrap(intent.yaw() - p.yaw());
        double target = heading;
        strafe &= intent.keys().forward() && !intent.keys().back() && !intent.keys().jump();
        if (strafe) {
            // The camera looks further down the route, so it turns early and gently through
            // a tight turn while the keys carry the body round it, as a player strafes.
            double move = p.groundSpeed() / Follower.SLIDE;
            double[] ahead = follower.placeAhead(LOOK_BASE + LOOK_PER_SPEED * move);
            if (Math.hypot(ahead[0] - p.x(), ahead[2] - p.z()) > 1) {
                double lead = AimController.wrap(Follower.yawToward(p.x(), p.z(), ahead[0],
                        ahead[2]) - heading);
                target = heading + lookWeight * Math.max(-STRAFE_LEAD, Math.min(STRAFE_LEAD, lead));
            }
        }
        // While the keys steer, the camera can follow its target softly: it only has to stay
        // within reach of the heading.
        if (strafe && !Double.isNaN(softTarget)) {
            softTarget += AimController.wrap(target - softTarget)
                    * (1 - Math.exp(-TICK / SOFT_TAU));
            double off = AimController.wrap(softTarget - heading);
            softTarget = heading + Math.max(-SOFT_REACH, Math.min(SOFT_REACH, off));
            target = softTarget;
        } else {
            softTarget = target;
        }
        double targetRate = Double.isNaN(lastTarget) ? 0
                : AimController.wrap(target - lastTarget) / TICK;
        // A target that jumps (a new lookahead segment) isn't a turn to lead.
        if (Math.abs(targetRate) > settings.aim().maxRate()) {
            targetRate = 0;
        }
        lastTarget = target;
        Keys keys = intent.keys();
        float pitch = settings.pitch();
        if (settings.lookAhead()) {
            boolean calm = p.onGround() && !keys.jump();
            drift = calm ? Math.min(1, drift + TICK / 0.5) : 0;
            wander();
            target += drift * DRIFT_YAW * driftYaw;
            pitch = (float) lookPitch(p, drift * DRIFT_PITCH * driftPitch + aim.tilt());
            // A big turn may start a moment late, as a person takes to react, but only when
            // nearly still on the ground, where starting late costs nothing.
            aim.mayPause(p.onGround() && p.groundSpeed() < PAUSE_SPEED && !keys.jump());
        }
        float yaw = p.yaw();
        double dt = TICK / settings.framesPerTick();
        for (int f = 0; f < settings.framesPerTick(); f++) {
            yaw = aim.frame(yaw, target + targetRate * dt * f, targetRate, dt);
        }
        if (strafe) {
            keys = strafeKeys(keys, heading - yaw);
        } else {
            strafeCarry = 0;
        }
        player.tick(keys, yaw, pitch);
        ticks++;
        double progress = follower.progress();
        if (progress > best + 0.05) {
            best = progress;
            sinceProgress = 0;
        } else if (++sinceProgress > STUCK_TICKS) {
            status = Status.STUCK;
        }
        return status;
    }

    /**
     * The keys that push the player {@code off} degrees from where the camera points, on
     * average: forward alone, or with A (45 to the left) or D (45 to the right), whichever is
     * nearest what's wanted plus what earlier ticks fell short by, so that over a few ticks the
     * pushes add up to the heading wanted and the player's momentum smooths them out.
     */
    private Keys strafeKeys(Keys keys, double off) {
        if (Math.abs(AimController.wrap(off)) < STRAFE_DEAD) {
            // Near enough: the camera's own turn closes it, with no side key to jostle.
            strafeCarry = 0;
            return keys;
        }
        double want = AimController.wrap(off) + strafeCarry;
        double side = want < -STRAFE / 2 ? -STRAFE : want > STRAFE / 2 ? STRAFE : 0;
        strafeCarry = Math.max(-STRAFE, Math.min(STRAFE, want - side));
        return new Keys(keys.forward(), side < 0, keys.back(), side > 0, keys.jump(),
                keys.sneak(), keys.sprint());
    }

    /**
     * Moves the drift on a tick: a wander that holds for a while and then goes elsewhere, at
     * random (spread 1), smoothed so the camera doesn't jitter.
     */
    private void wander() {
        if (hand == null) {
            return;
        }
        double keep = Math.exp(-TICK / DRIFT_HOLD);
        double kick = Math.sqrt(1 - keep * keep);
        wanderYaw = keep * wanderYaw + kick * gaussian();
        wanderPitch = keep * wanderPitch + kick * gaussian();
        double k = 1 - Math.exp(-TICK / DRIFT_SMOOTH);
        driftYaw += k * (wanderYaw - driftYaw);
        driftPitch += k * (wanderPitch - driftPitch);
    }

    /** A normally spread number, spread 1. */
    private double gaussian() {
        double u = Math.max(1e-12, hand.nextDouble());
        return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * hand.nextDouble());
    }

    /**
     * The pitch to hold this tick: toward the gaze point down the route, followed like a
     * steady hand, critically damped with its speed and how fast that changes both capped, so
     * slopes and stairs move the view calmly instead of snapping it.
     */
    private double lookPitch(PlayerController.Observation p, double wobble) {
        double move = p.groundSpeed() / Follower.SLIDE;
        double[] ahead = follower.placeAhead(GAZE_BASE + GAZE_PER_SPEED * move);
        // Glide there rather than snap: where the route bends down a drop, the place ahead
        // hops as the player passes a node, and the view and the lead marker shouldn't.
        if (gaze == null || Math.hypot(Math.hypot(ahead[0] - gaze[0], ahead[1] - gaze[1]),
                ahead[2] - gaze[2]) > GAZE_JUMP) {
            gaze = ahead;
        } else {
            // Carried along with the player first, so it doesn't trail behind on a straight.
            double k = 1 - Math.exp(-TICK / GAZE_TAU);
            for (int i = 0; i < 3; i++) {
                double carried = gaze[i] + (i == 0 ? p.x() : i == 1 ? p.y() : p.z()) - gazeFrom[i];
                gaze[i] = carried + k * (ahead[i] - carried);
            }
        }
        gazeFrom = new double[] {p.x(), p.y(), p.z()};
        double d = Math.hypot(gaze[0] - p.x(), gaze[2] - p.z());
        double want = Math.toDegrees(Math.atan2(p.y() + EYE - gaze[1] - GAZE_LIFT,
                Math.max(d, 1)));
        want = Math.max(MIN_PITCH, Math.min(MAX_PITCH, want)) + wobble;
        if (Float.isNaN(p.pitch())) {
            return want;
        }
        double pitch = p.pitch();
        int steps = settings.framesPerTick();
        double dt = TICK / steps;
        for (int i = 0; i < steps; i++) {
            double a = PITCH_OMEGA * PITCH_OMEGA * (want - pitch) - 2 * PITCH_OMEGA * pitchRate;
            pitchRate += Math.max(-PITCH_ACCEL, Math.min(PITCH_ACCEL, a)) * dt;
            pitchRate = Math.max(-PITCH_RATE, Math.min(PITCH_RATE, pitchRate));
            pitch += pitchRate * dt;
        }
        return pitch;
    }

    private Status done() {
        return blockedBy == null ? Status.ARRIVED : Status.UNSUPPORTED;
    }

    /** Ticks until it finishes or {@code maxTicks} pass. */
    public Status run(int maxTicks) {
        for (int i = 0; i < maxTicks && status == Status.RUNNING; i++) {
            tick();
        }
        return status;
    }
}
