package astar.core;

/** Standard heuristics. Each ignores height, which keeps them admissible (see {@link CostModel}). */
public final class Heuristics {
    public static final double SQRT2 = Math.sqrt(2);

    private Heuristics() {}

    /**
     * Cheapest horizontal distance with 8-way moves: min(dx, dz) diagonal steps, then the rest
     * straight. {@code (dx + dz) + (sqrt(2) - 2) * min(dx, dz)}
     */
    public static final Heuristic OCTILE_XZ =
            (dx, dy, dz) -> (dx + dz) + (SQRT2 - 2) * Math.min(dx, dz);

    public static final double SQRT5 = Math.sqrt(5);

    /**
     * Cheapest horizontal distance with 16-way moves: straight, diagonal, and one across and two
     * along (length sqrt(5)). With a the longer side and b the shorter: when b is at most half of
     * a, b of the long moves then the rest straight; otherwise a - b long moves and 2b - a
     * diagonals.
     */
    public static final Heuristic SIXTEEN_XZ = (dx, dy, dz) -> {
        double a = Math.max(dx, dz);
        double b = Math.min(dx, dz);
        return 2 * b <= a ? b * SQRT5 + (a - 2 * b) : (a - b) * SQRT5 + (2 * b - a) * SQRT2;
    };

    /**
     * Octile distance for any straight step cost {@code s} and diagonal step cost {@code d}:
     * the cheapest way across with 8-way moves. With m = min(s, d) and d' = min(d, 2s), it is
     * {@code m * (a - b) + d' * b}, a the longer side and b the shorter: when diagonals are
     * cheaper than straight steps, zigzagging diagonals cover the long side too. It is a norm, so
     * it is consistent as long as no straight move costs less than {@code s} and no diagonal one
     * less than {@code d}. {@code octile(1, sqrt(2))} is {@link #OCTILE_XZ} itself.
     */
    public static Heuristic octile(double s, double d) {
        if (s == 1 && d == SQRT2) {
            return OCTILE_XZ;
        }
        double m = Math.min(s, d);
        double dd = Math.min(d, 2 * s);
        return (dx, dy, dz) -> {
            int a = Math.max(dx, dz);
            int b = Math.min(dx, dz);
            return m * (a - b) + dd * b;
        };
    }

    /** {@code h} times {@code k}; {@code h} itself when k is 1. */
    public static Heuristic scaled(Heuristic h, double k) {
        if (k == 1) {
            return h;
        }
        return new Heuristic() {
            @Override
            public double estimate(int dx, int dy, int dz) {
                return k * h.estimate(dx, dy, dz);
            }

            @Override
            public double between(long a, long b) {
                return k * h.between(a, b); // keeps a height-aware h's sense of up and down
            }

            @Override
            public double between(long a, int heading, long b) {
                return k * h.between(a, heading, b);
            }

            @Override
            public double between(MoveGraph graph, int cell, long a, long b) {
                return k * h.between(graph, cell, a, b);
            }

            @Override
            public void prepare(long start, long goal) {
                h.prepare(start, goal);
            }
        };
    }

    /**
     * A flat heuristic {@code h} made to count height: the best, over each {@code i}, of
     * {@code shares[i] * h + up[i] * rise + down[i] * fall}, where rise and fall are how far the
     * goal is above or below. It is consistent when, for every i, no move costs less than
     * {@code shares[i]} times its h, plus {@code up[i]} per block it rises and {@code down[i]}
     * per block it falls: then each term is, and so is their maximum. Only
     * {@link Heuristic#between} knows which way is up; {@link Heuristic#estimate}, given a
     * plain height difference, charges the lower of the two rates.
     */
    public static Heuristic withHeight(Heuristic h, double[] shares, double[] up, double[] down) {
        double[] a = shares.clone();
        double[] u = up.clone();
        double[] d = down.clone();
        return new Heuristic() {
            @Override
            public double estimate(int dx, int dy, int dz) {
                double flat = h.estimate(dx, 0, dz);
                double best = 0;
                for (int i = 0; i < a.length; i++) {
                    best = Math.max(best, a[i] * flat + Math.min(u[i], d[i]) * dy);
                }
                return best;
            }

            @Override
            public double between(long from, long goal) {
                double flat = h.estimate(Math.abs(Pos.x(from) - Pos.x(goal)), 0,
                        Math.abs(Pos.z(from) - Pos.z(goal)));
                int dy = Pos.y(goal) - Pos.y(from);
                double best = 0;
                for (int i = 0; i < a.length; i++) {
                    best = Math.max(best, a[i] * flat + (dy > 0 ? u[i] * dy : -d[i] * dy));
                }
                return best;
            }
        };
    }

    /** dx + dz. Only admissible when diagonal moves are off. */
    public static final Heuristic MANHATTAN_XZ = (dx, dy, dz) -> dx + dz;

    /** Always 0: A* becomes Dijkstra. Useful as a reference in tests. */
    public static final Heuristic ZERO = (dx, dy, dz) -> 0;
}
