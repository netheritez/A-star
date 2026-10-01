package astar.client;

import astar.pathing.HandTurn;
import java.util.Random;

/**
 * Turns the view onto a cast a tick at a time like a hand on a mouse, ported from the old
 * pathfinder's HumanRotation: every turn is a minimum-jerk move (eases in, peaks halfway, eases
 * out) lasting as long as Fitts's law says ({@link HandTurn#turnTicks}), a little different each
 * time, quicker mid-air. Each tick the move is planned again from the turn's current speed and
 * acceleration toward the latest target over the time left, so a target that shifts bends the
 * turn instead of restarting it, and one that jumps starts a new, properly timed turn.
 */
final class HandAim {
    /** Each new turn's time is varied by up to this share. */
    private static final double DURATION_VARIATION = 0.1;
    /** A target that moves further than this from the one being turned toward starts a new turn. */
    private static final float RETARGET_DEG = 10F;
    /** A target moving less than this a tick counts as still: the turn runs out its time. */
    private static final float STILL_TARGET_DEG = 0.5F;
    /** Following a moving target, never plan to arrive sooner than this (ticks). */
    private static final double MIN_TRACK_TICKS = 3.0;
    private static final double FAST_MIN_TRACK_TICKS = 2.0;
    private static final Random RANDOM = new Random();

    private final Axis yaw = new Axis(true);
    private final Axis pitch = new Axis(false);
    private float left;

    /** The view one tick on from (nowYaw, nowPitch) toward (wantYaw, wantPitch). */
    float[] step(float nowYaw, float nowPitch, float wantYaw, float wantPitch, boolean fast) {
        float y = nowYaw + yaw.step(wrap(wantYaw - nowYaw), wantYaw, fast);
        float want = Math.max(-90F, Math.min(90F, wantPitch));
        float p = Math.max(-90F, Math.min(90F, nowPitch + pitch.step(want - nowPitch, want, fast)));
        left = Math.max(Math.abs(wrap(wantYaw - y)), Math.abs(want - p));
        return new float[] {y, p};
    }

    /** How far the view was still off the target after the last step, in degrees. */
    float left() {
        return left;
    }

    /** Forgets the turn in progress. */
    void reset() {
        yaw.reset();
        pitch.reset();
    }

    private static final class Axis {
        private final boolean wraps;
        private double velocity;
        private double acceleration;
        private double ticksLeft;
        private float target = Float.NaN;

        Axis(boolean wraps) {
            this.wraps = wraps;
        }

        void reset() {
            velocity = 0;
            acceleration = 0;
            ticksLeft = 0;
            target = Float.NaN;
        }

        /** The turn to make this tick toward a target {@code error} degrees away. */
        float step(float error, float newTarget, boolean fast) {
            float moved = Float.isNaN(target) ? Float.MAX_VALUE
                    : Math.abs(wraps ? wrap(newTarget - target) : newTarget - target);
            target = newTarget;
            double distance = Math.abs(error);
            if (moved > RETARGET_DEG || ticksLeft <= 1.0 && distance > 1.0) {
                double variation = 1 + DURATION_VARIATION * (2 * RANDOM.nextDouble() - 1);
                ticksLeft = HandTurn.turnTicks(distance, fast) * variation;
            }
            if (ticksLeft <= 1.0) {
                // Arriving: close the last fraction of a degree.
                velocity = 0;
                acceleration = 0;
                ticksLeft = 0;
                return error;
            }
            // The minimum-jerk (quintic) move from the current angle, speed and acceleration to
            // the target at rest, over the time left; its first tick.
            double t = ticksLeft, t2 = t * t, t3 = t2 * t, t4 = t3 * t, t5 = t4 * t;
            double v0 = velocity, a0 = acceleration;
            double c3 = (20 * error - 12 * v0 * t - 3 * a0 * t2) / (2 * t3);
            double c4 = (-30 * error + 16 * v0 * t + 3 * a0 * t2) / (2 * t4);
            double c5 = (12 * error - 6 * v0 * t - a0 * t2) / (2 * t5);
            double now = v0 + a0 / 2 + c3 + c4 + c5;
            velocity = v0 + a0 + 3 * c3 + 4 * c4 + 5 * c5;
            acceleration = a0 + 6 * c3 + 12 * c4 + 20 * c5;
            double least = fast ? FAST_MIN_TRACK_TICKS : MIN_TRACK_TICKS;
            ticksLeft = moved < STILL_TARGET_DEG ? ticksLeft - 1 : Math.max(least, ticksLeft - 1);
            return (float) now;
        }
    }

    private static float wrap(float degrees) {
        float d = degrees % 360;
        if (d >= 180) {
            d -= 360;
        } else if (d < -180) {
            d += 360;
        }
        return d;
    }
}
