package astar.movement.sim;

/**
 * The game's sine and cosine ({@code Mth.sin} and {@code cos}), which read a table of
 * 65536 values instead of calling {@link Math#sin}. Using them keeps the simulator's movement
 * identical to the game's to the last bit.
 */
public final class GameMath {

    private static final float[] SINE_TABLE = new float[65536];

    static {
        for (int i = 0; i < SINE_TABLE.length; i++) {
            SINE_TABLE[i] = (float) Math.sin(i / 10430.378350470453);
        }
    }

    private GameMath() {}

    /**
     * The sine of an angle in radians. The game passes float angles, widened to double: 26.3
     * looks them up in double precision, where 1.21 used a float scale factor.
     */
    public static float sin(double radians) {
        return SINE_TABLE[(int) ((long) (radians * 10430.378350470453) & 65535L)];
    }

    /** The cosine of an angle in radians. */
    public static float cos(double radians) {
        return SINE_TABLE[(int) ((long) (radians * 10430.378350470453 + 16384.0) & 65535L)];
    }
}
