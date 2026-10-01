package astar.core;

/**
 * Fixed costs per move type. A drop costs {@code dropBase + dropPerBlock * fallHeight}. A walk
 * that stays in the same column (stepping between the low step and the top of stairs) costs
 * {@code stepInPlace}, since it covers no horizontal distance.
 *
 * <p>Jumps and drops are charged sqrt(2) times as much (the drop's base, not its per-block
 * part) when diagonal. Swimming and climbing are charged per block covered: {@code swim} or {@code climb} times
 * the straight-line distance between the two cells (1 along or straight up, sqrt(2) diagonally
 * or one along and one down, and so on).
 *
 * <p>Any positive costs are allowed, including a diagonal cheaper than sqrt(2) or a straight
 * step cheaper than 1. The standard heuristics assume each move costs at least its horizontal
 * length, so use {@link #heuristic(int)}, which is scaled to these costs and stays admissible
 * and consistent whatever they are.
 */
public record DefaultCostModel(
        double walk, double diagonal, double jumpUp, double dropBase, double dropPerBlock,
        double stepInPlace, double swim, double climb) implements CostModel {

    public static final double DEFAULT_SWIM = 2;
    public static final double DEFAULT_CLIMB = 1.5;
    private static final double LONG_DIAGONAL = Math.sqrt(5) / Math.sqrt(2);

    public static final DefaultCostModel DEFAULT =
            new DefaultCostModel(1, Math.sqrt(2), 2, 1, 0.5, 0.5, DEFAULT_SWIM, DEFAULT_CLIMB);

    /** The same, with the default in-place step cost of 0.5 and default swim and climb costs. */
    public DefaultCostModel(double walk, double diagonal, double jumpUp, double dropBase,
            double dropPerBlock) {
        this(walk, diagonal, jumpUp, dropBase, dropPerBlock, 0.5);
    }

    /** The same, with the default swim and climb costs. */
    public DefaultCostModel(double walk, double diagonal, double jumpUp, double dropBase,
            double dropPerBlock, double stepInPlace) {
        this(walk, diagonal, jumpUp, dropBase, dropPerBlock, stepInPlace, DEFAULT_SWIM, DEFAULT_CLIMB);
    }

    public DefaultCostModel {
        require(walk > 0, "walk must be > 0");
        require(diagonal > 0, "diagonal must be > 0");
        require(jumpUp > 0, "jumpUp must be > 0");
        require(dropBase > 0, "dropBase must be > 0");
        require(dropPerBlock >= 0, "dropPerBlock must be >= 0");
        require(stepInPlace >= 0, "stepInPlace must be >= 0");
        require(swim > 0, "swim must be > 0");
        require(climb > 0, "climb must be > 0");
    }

    /** The same costs with other straight and diagonal step costs. */
    public DefaultCostModel withSteps(double walk, double diagonal) {
        return new DefaultCostModel(walk, diagonal, jumpUp, dropBase, dropPerBlock, stepInPlace,
                swim, climb);
    }

    /**
     * The cheapest any move one block straight along x or z can cost: a walk, a jump up, a
     * drop, a swim or a climb.
     */
    public double straightFloor() {
        return Math.min(Math.min(walk, jumpUp), Math.min(dropBase, Math.min(swim, climb)));
    }

    /** The cheapest any move one block along x and z at once can cost, jumps and drops too. */
    public double diagonalFloor() {
        double leap = Math.min(jumpUp, dropBase);
        return Math.min(diagonal, Math.min(Math.min(swim, climb), leap) * Heuristics.SQRT2);
    }

    /**
     * The best admissible, consistent heuristic for these costs, moving in 4, 8 or 16
     * directions. With the default straight and diagonal costs (cheapest straight move 1, cheapest
     * diagonal one sqrt(2)) it is exactly {@link Heuristics#MANHATTAN_XZ},
     * {@link Heuristics#OCTILE_XZ} or {@link Heuristics#SIXTEEN_XZ}.
     *
     * <ul>
     *   <li>4 directions: Manhattan, times the cheapest straight move.
     *   <li>8 directions: {@link Heuristics#octile} with the cheapest straight and diagonal moves.
     *   <li>16 directions: {@link Heuristics#SIXTEEN_XZ}, scaled so that no move is cheaper
     *       than it says (a 16-way move costs the diagonal times sqrt(5) / sqrt(2)).
     * </ul>
     */
    public Heuristic heuristic(int directions) {
        double s = straightFloor();
        double d = diagonalFloor();
        return switch (directions) {
            case 4 -> Heuristics.scaled(Heuristics.MANHATTAN_XZ, s);
            case 8 -> Heuristics.octile(s, d);
            case 16 -> {
                yield Heuristics.scaled(Heuristics.SIXTEEN_XZ, Math.min(s, d / Heuristics.SQRT2));
            }
            default -> throw new IllegalArgumentException("directions must be 4, 8 or 16");
        };
    }

    /**
     * {@link #heuristic(int)} plus what it costs to climb or come down to the goal's height.
     * No move is cheaper than its horizontal part as the flat heuristic prices it, times some
     * share {@code a} of it, plus {@code up} per block it rises and {@code down} per block it
     * falls; for each share this finds the largest such {@code up} and {@code down} (see
     * {@link Heuristics#withHeight}). The estimate is the best of those, so it stays admissible
     * and consistent and is never below {@link #heuristic(int)}.
     *
     * <p>With the default costs a walk up a slab or a stair costs the same as a level one, so
     * height only raises the estimate where the goal is further up or down than it is away.
     */
    public Heuristic heightAwareHeuristic(int directions) {
        Heuristic flat = heuristic(directions);
        double hs = flat.estimate(1, 0, 0);
        double hd = directions >= 8 ? flat.estimate(1, 0, 1) : 2 * hs;
        double[] shares = {1, 0.75, 0.5, 0.25, 0};
        double[] up = new double[shares.length];
        double[] down = new double[shares.length];
        for (int i = 0; i < shares.length; i++) {
            up[i] = Math.max(0, perBlock(shares[i] * hs, shares[i] * hd, true));
            down[i] = Math.max(0, perBlock(shares[i] * hs, shares[i] * hd, false));
        }
        return Heuristics.withHeight(flat, shares, up, down);
    }

    /**
     * The most a heuristic can charge per block of rise (or fall) on top of {@code hs} per
     * straight block and {@code hd} per diagonal one, so that no move costs less than it says:
     * the smallest (cost - horizontal part) / blocks over every move that rises (or falls).
     * Falls and swims can be any height, so those are checked up to 64 blocks, and falls also
     * in the limit, where only {@code dropPerBlock} is left.
     */
    private double perBlock(double hs, double hd, boolean rising) {
        double r = Math.min(stepInPlace, Math.min(swim, climb)); // in place: stairs, straight up
        // Walks: a slab or stair step, one block up or down, along or diagonally.
        r = Math.min(r, Math.min(walk - hs, diagonal - hd));
        for (int n = 1; n <= 64; n++) {
            if (rising && n <= 2) {
                // A jump rises up to 1.25: two cells from a floor just under a block's top.
                r = Math.min(r, Math.min(jumpUp - hs, jumpUp * Heuristics.SQRT2 - hd) / n);
            }
            if (!rising) {
                r = Math.min(r, Math.min(dropBase + dropPerBlock * n - hs,
                        dropBase * Heuristics.SQRT2 + dropPerBlock * n - hd) / n);
            }
            for (double held : new double[] {swim, climb}) {
                r = Math.min(r, (held * Math.sqrt(1 + n * n) - hs) / n);
                r = Math.min(r, (held * Math.sqrt(2 + n * n) - hd) / n);
            }
        }
        return rising ? r : Math.min(r, dropPerBlock);
    }

    @Override
    public double cost(long from, long to, MoveType type) {
        return switch (type) {
            case WALK -> Pos.x(from) == Pos.x(to) && Pos.z(from) == Pos.z(to) ? stepInPlace : walk;
            // A 16-way move one across and two along covers sqrt(5) / sqrt(2) as much.
            case DIAGONAL -> Math.abs(Pos.x(to) - Pos.x(from)) + Math.abs(Pos.z(to) - Pos.z(from))
                    == 3 ? diagonal * LONG_DIAGONAL : diagonal;
            // Diagonal jumps and drops cover sqrt(2) as much ground.
            case JUMP_UP -> jumpUp * across(from, to);
            case DROP -> dropBase * across(from, to) + dropPerBlock * (Pos.y(from) - Pos.y(to));
            case SWIM -> swim * blocksCovered(from, to);
            case CLIMB -> climb * blocksCovered(from, to);
            case WARP, TRANSMIT -> throw new IllegalArgumentException(
                    "Teleports are priced by their graph");
        };
    }

    /** 1 for a move along x or z, sqrt(2) for a diagonal one. */
    private static double across(long from, long to) {
        return Pos.x(to) != Pos.x(from) && Pos.z(to) != Pos.z(from) ? Heuristics.SQRT2 : 1;
    }

    /** The straight-line distance between the two cells: never less than the horizontal one. */
    private static double blocksCovered(long from, long to) {
        int dx = Pos.x(to) - Pos.x(from);
        int dy = Pos.y(to) - Pos.y(from);
        int dz = Pos.z(to) - Pos.z(from);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new IllegalArgumentException(message);
        }
    }
}
