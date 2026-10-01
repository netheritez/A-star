package astar.core;

/**
 * Estimates the remaining cost between two positions from the absolute differences
 * {@code dx, dy, dz}. Must never overestimate, and must be consistent: for every move from a
 * to b, {@code h(a) <= cost(a, b) + h(b)}. The search never reopens a node it has closed, so
 * a heuristic that is admissible but not consistent can make it return a costlier path. The
 * built-in ones are consistent with {@link DefaultCostModel}.
 */
@FunctionalInterface
public interface Heuristic {
    double estimate(int dx, int dy, int dz);

    /**
     * Called once as a search starts, before any estimate, with its start and goal (packed). A
     * heuristic that keeps something per search (see the landmark ones) sets it up here, and
     * keeps it for the whole search, so the estimates it gives that search stay consistent.
     */
    default void prepare(long start, long goal) {}

    default double between(long a, long b) {
        return estimate(
                Math.abs(Pos.x(a) - Pos.x(b)),
                Math.abs(Pos.y(a) - Pos.y(b)),
                Math.abs(Pos.z(a) - Pos.z(b)));
    }

    /**
     * The estimate for a search node that is a position together with the step that led into
     * it, as a heading code ({@link TurnPenalty#heading}; 0 for none). Searches that keep a node
     * per heading ask this, so a heuristic that knows what turns cost can count the turns still
     * to come; it must stay consistent over those nodes. By default the heading is ignored.
     */
    default double between(long a, int heading, long b) {
        return between(a, b);
    }

    /**
     * {@link #between(long, long)} for a search over a move graph, where a is the graph's cell
     * {@code cell}: a heuristic kept per cell of that graph looks it up without finding the
     * cell again. The answer must be the same.
     */
    default double between(MoveGraph graph, int cell, long a, long b) {
        return between(a, b);
    }

    default double between(BlockPoint a, BlockPoint b) {
        return between(a.pack(), b.pack());
    }
}
