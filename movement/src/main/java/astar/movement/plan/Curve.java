package astar.movement.plan;

/**
 * A corner of the route rounded off: a clothoid pair (an Euler spiral in and out), the curve
 * roads and railways turn with. Its curvature grows steadily from nothing where it leaves the
 * straight to the sharpest at the middle and back, so the turn builds up and eases off instead
 * of snapping, and the player can keep its speed through it.
 *
 * <p>It starts {@code trim} before the corner's node, along the way in, and ends {@code trim}
 * past it along the way out, both measured across the ground along the route's straight
 * lines; in between, a fraction of that way is the same fraction of the curve.
 *
 * @param node the node at the corner
 * @param trim how far before and after the corner the curve leaves the straight lines
 * @param radius the curve's tightest radius (at its middle), in blocks
 * @param inset how far inside the corner the middle of the curve passes
 * @param x points along the curve, evenly spaced by length, from start to end
 * @param z the same
 */
public record Curve(int node, double trim, double radius, double inset, double[] x,
        double[] z) {

    /** Turns gentler than this, in degrees, stay corners: the follower rounds them itself. */
    static final double MIN_TURN = 3;
    /** Turns sharper than this, in degrees, aren't rounded: nearly doubling back. */
    static final double MAX_TURN = 150;
    /** Curves shorter than this each side, in blocks, aren't worth it. */
    static final double MIN_TRIM = 0.15;
    private static final int SAMPLES = 32;
    private static final int STEPS = 256;

    /** The points of a unit pair ({@link #unitPair}) for a turn, never changed once made. */
    private record Unit(double theta, double[][] points) {}

    /** The unit pair {@link #fit} last worked out. */
    private static volatile Unit lastUnit;

    /** The point a fraction {@code f} (0 to 1) of the way along the curve, {x, z}. */
    public double[] at(double f) {
        double t = Math.max(0, Math.min(1, f)) * (x.length - 1);
        int i = Math.min((int) t, x.length - 2);
        double u = t - i;
        return new double[] {x[i] + u * (x[i + 1] - x[i]), z[i] + u * (z[i + 1] - z[i])};
    }

    /**
     * The biggest curve that fits a corner.
     *
     * @param vx the corner's point
     * @param vz the corner's point
     * @param in the direction the route comes in, a unit vector {x, z}
     * @param out the direction it leaves in
     * @param maxTrim the furthest the curve may start before the corner (and end after it)
     * @param maxInset the furthest inside the corner its middle may pass
     * @return the curve, or null if the turn is too gentle or too sharp or there's no room
     */
    static Curve fit(int node, double vx, double vz, double[] in, double[] out, double maxTrim,
            double maxInset) {
        double cross = in[0] * out[1] - in[1] * out[0];
        double dot = in[0] * out[0] + in[1] * out[1];
        double theta = Math.atan2(Math.abs(cross), dot);
        if (Math.toDegrees(theta) < MIN_TURN || Math.toDegrees(theta) > MAX_TURN) {
            return null;
        }
        // The pair for a length of 1, heading along +x and turning toward +y. A corner is
        // fitted a few times over, with the same turn, so the last one is kept.
        Unit last = lastUnit;
        if (last == null || Double.compare(last.theta(), theta) != 0) {
            last = new Unit(theta, unitPair(theta));
            lastUnit = last;
        }
        double[][] unit = last.points();
        double[] end = unit[STEPS];
        double trim1 = end[1] / Math.sin(theta);
        double[] mid = unit[STEPS / 2];
        double inset1 = Math.hypot(trim1 - mid[0], mid[1]);
        double length = Math.min(maxTrim / trim1, maxInset / inset1);
        double trim = length * trim1;
        if (!(trim >= MIN_TRIM)) {
            return null;
        }
        // Across the way in, toward the inside of the turn.
        double sx = out[0] - dot * in[0];
        double sz = out[1] - dot * in[1];
        double sl = Math.hypot(sx, sz);
        sx /= sl;
        sz /= sl;
        double ax = vx - trim * in[0];
        double az = vz - trim * in[1];
        double[] x = new double[SAMPLES + 1];
        double[] z = new double[SAMPLES + 1];
        for (int k = 0; k <= SAMPLES; k++) {
            double[] p = unit[k * STEPS / SAMPLES];
            x[k] = ax + length * (p[0] * in[0] + p[1] * sx);
            z[k] = az + length * (p[0] * in[1] + p[1] * sz);
        }
        // The tightest curvature, at the middle, is twice the turn over the length.
        return new Curve(node, trim, length / (2 * theta), length * inset1, x, z);
    }

    /**
     * Points along a clothoid pair of length 1 turning through {@code theta}: the curvature
     * rises in a straight line from 0 to 2 theta at the middle, and falls back.
     */
    private static double[][] unitPair(double theta) {
        double[][] p = new double[STEPS + 1][];
        double ds = 1.0 / STEPS;
        double x = 0;
        double y = 0;
        p[0] = new double[] {0, 0};
        for (int i = 0; i < STEPS; i++) {
            // Heading at the middle of the step, from the curvature's integral.
            double s = (i + 0.5) * ds;
            double heading = s <= 0.5 ? 2 * theta * s * s
                    : theta - 2 * theta * (1 - s) * (1 - s);
            x += Math.cos(heading) * ds;
            y += Math.sin(heading) * ds;
            p[i + 1] = new double[] {x, y};
        }
        return p;
    }
}
