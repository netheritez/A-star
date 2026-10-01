package astar.movement.exec;

import java.util.SplittableRandom;

/**
 * Turns the camera toward a target yaw the way a steady hand on a mouse would: a critically
 * damped turn, capped in rate, moved in whole mouse counts.
 *
 * <p>It runs once per rendered frame. Each frame it works out the turn it wants, clamps the
 * turn rate, and converts the turn into mouse counts at the game's sensitivity, carrying the
 * fraction of a count left over to the next frame, as a real mouse would. Given how fast the
 * target itself is turning (the follower's curve), it leads the target instead of trailing
 * it by the damping lag.
 *
 * <p>A big turn all at once (the target jumping by more than {@link Settings#flick}) is made
 * the way people move a hand to a target: a minimum-jerk sweep, {@code 10t³ - 15t⁴ + 6t⁵} of
 * the way at a fraction t of its time, which eases in and out; the time grows with the turn by
 * Fitts's law; and it falls a little short, leaving the rest to the steady turn after it.
 * The sweep is planned again every frame from the camera's turn rate and how fast that is
 * changing, so it starts from a turn already under way and bends to a target that moves,
 * with no jolt; and the steady turn speeds up at most {@link Settings#maxAccel}, so a target
 * that jumps a little doesn't jerk the camera either.
 */
public final class AimController {

    /**
     * Which hand moves the mouse. A hand sweeps outward (to the right with the right hand) a
     * little quicker and further than across the body, and a sweep isn't quite level: the
     * wrist and elbow swing the mouse on an arc, so an outward sweep tips the view up a little
     * and an inward one down, and the pitch then comes back.
     */
    public enum Hand {
        RIGHT(1), LEFT(-1);

        /** +1 when turning right is outward. */
        final int outward;

        Hand(int outward) {
            this.outward = outward;
        }

        /** The hand named, or null. */
        public static Hand named(String name) {
            for (Hand h : values()) {
                if (h.name().equalsIgnoreCase(name.strip())) {
                    return h;
                }
            }
            return null;
        }
    }

    /**
     * How the aim behaves.
     *
     * @param omega how stiffly it closes on the target, in radians per second; the turn
     *     settles in about {@code 4 / omega} seconds
     * @param maxRate the fastest it turns, in degrees per second
     * @param degreesPerCount how far one mouse count turns the camera: the game's {@code 0.15
     *     * 8 * (0.6 * sensitivity + 0.2)^3}, 0.15 at the default sensitivity of 0.5; 0 turns
     *     smoothly with no counts
     * @param flick turns bigger than this, in degrees, are swept
     * @param flickBase a sweep's shortest time, in seconds
     * @param flickPerBit how much longer per doubling of the turn (Fitts's law: {@code
     *     base + perBit * log2(1 + turn / FLICK_WIDTH)})
     * @param undershoot how much of the turn the sweep makes
     * @param maxAccel how quickly the steady turn can speed up or slow down, in degrees per
     *     second per second, so a target that jumps doesn't jerk the camera
     */
    public record Settings(double omega, double maxRate, double degreesPerCount,
            double flick, double flickBase, double flickPerBit, double undershoot,
            double maxAccel) {

        public static final Settings DEFAULT = new Settings(25, 540, degreesPerCount(0.5),
                30, 0.1, 0.07, 0.95, 4000);

        /** No big-turn sweeps: always the steady turn. */
        public Settings(double omega, double maxRate, double degreesPerCount) {
            this(omega, maxRate, degreesPerCount, Double.POSITIVE_INFINITY, 0, 0, 1,
                    Double.POSITIVE_INFINITY);
        }

        /** Steady turns as fast to change as the spring asks. */
        public Settings(double omega, double maxRate, double degreesPerCount, double flick,
                double flickBase, double flickPerBit, double undershoot) {
            this(omega, maxRate, degreesPerCount, flick, flickBase, flickPerBit, undershoot,
                    Double.POSITIVE_INFINITY);
        }

        /** Degrees per mouse count at a sensitivity setting (0 to 1, default 0.5). */
        public static double degreesPerCount(double sensitivity) {
            double f = sensitivity * 0.6 + 0.2;
            return f * f * f * 8.0 * 0.15;
        }
    }

    /** The target's size in Fitts's law, in degrees: how close a sweep has to land. */
    private static final double FLICK_WIDTH = 5;
    /**
     * The fastest a sweep turns, in degrees a second: a target that jumps late in a sweep
     * stretches the sweep rather than being closed in what's left of its time.
     */
    private static final double SWEEP_RATE = 600;
    /** The shortest sweep, in seconds. */
    private static final double MIN_SWEEP = 0.1;
    /** How many times quicker the steady turn can slow than speed up. */
    private static final double BRAKE = 3;

    /**
     * The share of big turns a hand carries past the target and then brings back: outward
     * sweeps more often than those across the body.
     */
    private static final double OVERSHOOT_OUT = 0.4;
    private static final double OVERSHOOT_IN = 0.2;
    /** How much quicker an outward sweep is, and slower an inward one. */
    private static final double OUTWARD_QUICKER = 0.08;
    /** How far a sweep tips the pitch, in degrees per degree turned (up outward). */
    private static final double ARC_TILT = 0.04;
    /** How quickly the pitch comes back after a sweep's tip, in seconds. */
    private static final double TILT_BACK = 0.3;
    /** How much slower it lets a paused hand's turn die away, in seconds. */
    private static final double PAUSE_EASE = 0.04;

    private final Settings settings;
    /** Where a hand's unevenness comes from; null for the same turn every time. */
    private SplittableRandom hand;
    /** Whether a big turn may wait a moment before it starts, as a person takes to react. */
    private boolean mayPause;
    /** How long the hand still waits before the sweep it's about to make, in seconds. */
    private double pause;
    private Hand handed = Hand.RIGHT;
    /** How far the sweeps have tipped the pitch, in degrees (positive looks down). */
    private double tilt;
    private double rate;
    /** How fast the rate itself is changing, in degrees per second per second. */
    private double accel;
    private double leftover;
    /** The sweep in progress: how far short of the target it stops, how long, how far in. */
    private boolean sweeping;
    private double sweepShort;
    private double sweepTime;
    private double sweepAt;

    public AimController(Settings settings) {
        this.settings = settings;
    }

    /**
     * Makes its turns uneven the way a hand's are, drawing from {@code hand}: each big turn's
     * time and reach differ, some go a little past and come back, and (when {@link
     * #mayPause}) it may wait a moment before starting one. Null makes every turn the same.
     */
    public void hand(SplittableRandom hand) {
        this.hand = hand;
    }

    /** Sets which hand moves the mouse (the right one unless told). */
    public void handed(Hand hand) {
        handed = hand;
    }

    /**
     * How far the sweeps tip the view off level, in degrees (positive looks down), to add to
     * the pitch wanted; it dies away after a sweep. Always 0 with no {@link #hand}.
     */
    public double tilt() {
        return tilt;
    }

    /** Whether the next big turn may wait a moment first: only when a late start costs nothing. */
    public void mayPause(boolean may) {
        mayPause = may;
        if (!may) {
            pause = 0;
        }
    }

    /** The current turn rate, in degrees per second. */
    public double rate() {
        return rate;
    }

    /** Stops any turn in progress, as when the player takes over. */
    public void reset() {
        rate = 0;
        accel = 0;
        leftover = 0;
        sweeping = false;
        pause = 0;
        tilt = 0;
    }

    /** Carries on the turn another controller was making, as when a new plan takes over. */
    public void carry(AimController from) {
        rate = from.rate;
        accel = from.accel;
        leftover = from.leftover;
        sweeping = from.sweeping;
        sweepShort = from.sweepShort;
        sweepTime = from.sweepTime;
        sweepAt = from.sweepAt;
        pause = from.pause;
        tilt = from.tilt;
        handed = from.handed;
        if (from.hand != null) {
            hand = from.hand;
        }
    }

    /** Whether a big turn is being swept now. */
    public boolean sweeping() {
        return sweeping;
    }

    /** The share of a minimum-jerk move made a fraction {@code t} of the way through it. */
    static double minimumJerk(double t) {
        t = Math.max(0, Math.min(1, t));
        return t * t * t * (10 - 15 * t + 6 * t * t);
    }

    /**
     * One frame: returns the camera yaw after it.
     *
     * @param yaw the camera's yaw now
     * @param target the yaw wanted (any turn: it goes the short way round)
     * @param targetRate how fast the target is turning, in degrees per second
     * @param dt the frame's length in seconds
     */
    public float frame(float yaw, double target, double targetRate, double dt) {
        double error = wrap(target - yaw);
        if (sweeping && sweepTime - sweepAt <= dt / 2) {
            // Its time is up: from here, at the rate it's turning, it sweeps again if the
            // target has moved far meanwhile, or turns steadily the rest of the way.
            sweeping = false;
        }
        if (!sweeping && Math.abs(error) > settings.flick()) {
            sweeping = true;
            sweepShort = error * (1 - settings.undershoot());
            sweepAt = 0;
            sweepTime = settings.flickBase() + settings.flickPerBit()
                    * Math.log(1 + Math.abs(error) / FLICK_WIDTH) / Math.log(2);
            if (hand != null) {
                // No two alike: a little quicker or slower, mostly short of the target, now
                // and then a few degrees past it, which the steady turn then brings back.
                boolean out = error * handed.outward > 0;
                double reach = hand.nextDouble() < (out ? OVERSHOOT_OUT : OVERSHOOT_IN)
                        ? 1.02 + 0.05 * hand.nextDouble()
                        : 0.86 + 0.11 * hand.nextDouble();
                sweepShort = error * (1 - reach);
                sweepTime *= (0.85 + 0.35 * hand.nextDouble())
                        * (out ? 1 - OUTWARD_QUICKER : 1 + OUTWARD_QUICKER);
                if (mayPause && Math.abs(rate) < 30) {
                    pause = 0.04 + 0.12 * hand.nextDouble();
                }
            }
        }
        double turn;
        if (sweeping && pause > 0) {
            // Taking it in before moving: whatever turn there was dies away.
            pause -= dt;
            rate *= Math.exp(-dt / PAUSE_EASE);
            accel = 0;
            turn = rate * dt;
        } else if (sweeping) {
            // Planned again every frame from where the camera is, how fast it turns and how
            // fast that is changing, to where the target is now: the minimum-jerk quintic with
            // those starting values, stopping still. A target that moves mid-sweep bends the
            // sweep instead of jerking it, and a sweep started mid-turn carries the turn on.
            double left = sweepTime - sweepAt;
            double to = error - sweepShort;
            if (rate * to > 0 && sweepAt == 0) {
                // Already turning that way: soon enough that the carried speed doesn't swing it
                // past (the quintic doesn't overshoot while speed x time is under 2.5 x turn).
                left = Math.max(MIN_SWEEP, Math.min(left, 2.5 * Math.abs(to / rate)));
                sweepTime = left;
            }
            // A minimum-jerk move peaks at 1.875 times its average speed.
            double least = 1.875 * Math.abs(to) / SWEEP_RATE;
            if (left < least) {
                left = least;
                sweepTime = sweepAt + left;
            }
            double t = Math.min(1, dt / left);
            double v = rate * left;
            double a = accel * left * left / 2;
            double r = to - v - a;
            double sl = -v - 2 * a;
            double q = -2 * a;
            double c3 = 10 * r - 4 * sl + q / 2;
            double c4 = -15 * r + 7 * sl - q;
            double c5 = 6 * r - 3 * sl + q / 2;
            double t2 = t * t;
            double t3 = t2 * t;
            turn = v * t + a * t2 + c3 * t3 + c4 * t3 * t + c5 * t3 * t2;
            rate = (v + 2 * a * t + 3 * c3 * t2 + 4 * c4 * t3 + 5 * c5 * t3 * t) / left;
            accel = (2 * a + 6 * c3 * t + 12 * c4 * t2 + 20 * c5 * t3) / (left * left);
            sweepAt += dt;
        } else {
            double w = settings.omega();
            double was = rate;
            double want = w * w * error + 2 * w * (targetRate - rate);
            // Slowing down may be quicker than speeding up: a hand stops sooner than it starts.
            double cap = settings.maxAccel() * (want * rate < 0 ? BRAKE : 1);
            rate += Math.max(-cap, Math.min(cap, want)) * dt;
            rate = Math.max(-settings.maxRate(), Math.min(settings.maxRate(), rate));
            accel = (rate - was) / dt;
            turn = rate * dt;
        }
        if (hand != null) {
            // The arc the mouse moves on: up a little while sweeping outward, down while
            // sweeping across the body; back to level after.
            if (sweeping) {
                tilt -= ARC_TILT * turn * handed.outward;
            }
            tilt *= Math.exp(-dt / TILT_BACK);
        }
        // Never turn past the target in one frame: that's where a stiff spring would wobble.
        if (!sweeping && Math.abs(turn) > Math.abs(error)
                && Math.signum(turn) == Math.signum(error) && Math.abs(targetRate) < 1e-9) {
            turn = error;
            rate = 0;
            accel = 0;
        }
        double step = settings.degreesPerCount();
        if (step <= 0) {
            return (float) (yaw + turn);
        }
        double wanted = turn + leftover;
        long counts = Math.round(wanted / step);
        leftover = wanted - counts * step;
        // The game adds each mouse move to the float yaw.
        return (float) (yaw + counts * step);
    }

    /** An angle in degrees brought into [-180, 180). */
    public static double wrap(double degrees) {
        double d = (degrees + 180) % 360;
        if (d < 0) {
            d += 360;
        }
        return d - 180;
    }
}
