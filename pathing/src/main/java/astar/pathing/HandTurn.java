package astar.pathing;

/**
 * How long a hand on a mouse takes to turn the view onto a cast, as the old pathfinder's
 * HumanRotation times it: a minimum-jerk turn lasting {@code 2 + 2.6 log2(1 + degrees / 5)}
 * ticks (Fitts's law); mid-air, where the fall doesn't wait, a quick flick timed from the
 * user's own casts. Casts are planned
 * with these times (a player in the air falls while the view turns) and the client turns with
 * them.
 */
public final class HandTurn {
    private HandTurn() {}

    static final double BASE_TICKS = 2.0;
    static final double TICKS_PER_BIT = 2.6;
    static final double TARGET_WIDTH = 5.0;
    /**
     * Mid-air turns (between casts of an air chain) as the user makes them: a flick of a tick,
     * and a tick more per this many degrees (fitted to 72 of their chained casts on the Mines,
     * 2026-09-29: about 2 ticks from a teleport showing to the next click for turns under 10
     * degrees, 3 under 20, 5 at 25 to 30).
     */
    static final double AIR_DEGREES_PER_TICK = 9;

    /** Nominal ticks a turn through {@code degrees} takes, quicker mid-air ({@code fast}). */
    public static double turnTicks(double degrees, boolean fast) {
        if (fast) {
            return 1 + Math.abs(degrees) / AIR_DEGREES_PER_TICK;
        }
        double t = BASE_TICKS + TICKS_PER_BIT * Math.log(1 + Math.abs(degrees) / TARGET_WIDTH)
                / Math.log(2);
        return t;
    }

    /** The turn between two views, in degrees: the bigger of the yaw and pitch turns. */
    public static double angle(double yaw0, double pitch0, double yaw1, double pitch1) {
        return Math.max(Math.abs(wrap(yaw1 - yaw0)), Math.abs(pitch1 - pitch0));
    }

    /**
     * Game ticks from a teleport showing to the next click of an air chain, when the next
     * cast's view is {@code degrees} from the last: the turn, and a tick for the view to reach
     * the server. Two with no turn: the user's own rhythm spamming the click.
     */
    public static int chainWait(double degrees) {
        return 1 + (int) Math.floor(turnTicks(degrees, true));
    }

    /** How far a player falls in {@code ticks} ticks from standing still in the air, in blocks. */
    public static double fall(int ticks) {
        double v = 0, fallen = 0;
        for (int t = 0; t < ticks; t++) {
            v = (v - 0.08) * 0.98;
            fallen -= v;
        }
        return fallen;
    }

    static double wrap(double degrees) {
        double d = degrees % 360;
        if (d >= 180) {
            d -= 360;
        } else if (d < -180) {
            d += 360;
        }
        return d;
    }
}
