package astar.core;

/**
 * Adds {@code perTurn} for every 45 degrees the heading turns, seen from above, on top of
 * another cost model: a 45-degree turn costs {@code perTurn}, a right angle twice that, and a
 * 16-way heading change of about 27 degrees about 0.6 times. Diagonal runs count as
 * straight: only a change of heading costs. Moves straight up or down have no heading and
 * are never charged.
 *
 * <p>Among paths that cost about the same, this picks the one with the fewest, gentlest
 * turns: long straight runs along the axes and the diagonals instead of zigzags.
 */
public record TurnPenalty(CostModel base, double perTurn, boolean exact)
        implements TurningCostModel {

    /**
     * The turn cost the route tool, the editor and {@code /goto} use unless told otherwise:
     * enough to straighten zigzags across open ground at almost no extra length.
     */
    public static final double DEFAULT_PER_TURN = 0.1;

    // Turn size in units of 45 degrees, for headings with |dx| and |dz| up to 2.
    private static final double[] TABLE = new double[625];

    static {
        for (int a = 0; a < 625; a++) {
            int dx1 = a / 125 - 2;
            int dz1 = a / 25 % 5 - 2;
            int dx2 = a / 5 % 5 - 2;
            int dz2 = a % 5 - 2;
            TABLE[a] = turnSlow(dx1, dz1, dx2, dz2);
        }
    }

    public TurnPenalty {
        if (!(perTurn >= 0)) {
            throw new IllegalArgumentException("perTurn must be >= 0");
        }
    }

    /** Charged from each cell's cheapest parent (see {@link TurningCostModel#exact}). */
    public TurnPenalty(CostModel base, double perTurn) {
        this(base, perTurn, false);
    }

    @Override
    public double cost(long from, long to, MoveType type) {
        return base.cost(from, to, type);
    }

    @Override
    public double cost(long before, long from, long to, MoveType type) {
        return base.cost(from, to, type) + perTurn * turn(before, from, to);
    }

    @Override
    public double turnCost(long before, long from, long to, MoveType type) {
        return perTurn * turn(before, from, to);
    }

    @Override
    public double[] turnCostByHeading() {
        double[] table = new double[26 * 26];
        for (int in = 1; in < 26; in++) {
            for (int out = 1; out < 26; out++) {
                table[in * 26 + out] = perTurn * turn(headingX(in), headingZ(in),
                        headingX(out), headingZ(out));
            }
        }
        return table;
    }

    /**
     * The code for a step of (dx, dz) blocks, each -2 to 2: 0 for none (straight up or down,
     * or no step yet), else 1 to 25. Searches keep it per node to know which way it was entered.
     */
    public static int heading(int dx, int dz) {
        return dx == 0 && dz == 0 ? 0 : (dx + 2) * 5 + (dz + 2) + 1;
    }

    /** The x part of a heading code. */
    public static int headingX(int code) {
        return code == 0 ? 0 : (code - 1) / 5 - 2;
    }

    /** The z part of a heading code. */
    public static int headingZ(int code) {
        return code == 0 ? 0 : (code - 1) % 5 - 2;
    }

    /**
     * How far the heading turns from a step of (dx1, dz1) to one of (dx2, dz2), in units of 45
     * degrees; 0 if either has no heading.
     */
    public static double turn(int dx1, int dz1, int dx2, int dz2) {
        if (Math.abs(dx1) <= 2 && Math.abs(dz1) <= 2 && Math.abs(dx2) <= 2 && Math.abs(dz2) <= 2) {
            return TABLE[(dx1 + 2) * 125 + (dz1 + 2) * 25 + (dx2 + 2) * 5 + dz2 + 2];
        }
        return turnSlow(dx1, dz1, dx2, dz2);
    }

    /** How far the heading turns at {@code from}, in units of 45 degrees (0 to 4). */
    public static double turn(long before, long from, long to) {
        return turn(Pos.x(from) - Pos.x(before), Pos.z(from) - Pos.z(before),
                Pos.x(to) - Pos.x(from), Pos.z(to) - Pos.z(from));
    }

    private static double turnSlow(int dx1, int dz1, int dx2, int dz2) {
        if ((dx1 == 0 && dz1 == 0) || (dx2 == 0 && dz2 == 0)) {
            return 0;
        }
        int dot = dx1 * dx2 + dz1 * dz2;
        if (dx1 * dz2 == dz1 * dx2) { // parallel: straight on, or straight back
            return dot > 0 ? 0 : 4;
        }
        double cos = dot
                / (Math.hypot(dx1, dz1) * Math.hypot(dx2, dz2));
        double degrees = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cos))));
        return degrees / 45;
    }
}
