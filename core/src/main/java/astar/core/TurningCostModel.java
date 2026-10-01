package astar.core;

/**
 * A cost model that can also charge for the change of heading between the move into
 * {@code from} and the move out of it. {@link AStarSearch} calls
 * {@link #cost(long, long, long, MoveType)} for every move after the first; searches that
 * don't know where a node was entered from use the plain {@link #cost(long, long, MoveType)}.
 *
 * <p>The extra charge must never be negative, so the heuristic stays admissible. It may
 * depend only on the horizontal step into {@code from} (at most two blocks each way), not on
 * where exactly {@code before} is.
 *
 * <p>When {@link #exact()} is true, {@link AStarSearch} keeps one node per cell and heading,
 * rebuilding {@code before} from the heading, so it returns the cheapest path counting turns;
 * that searches several times as many nodes. Otherwise it keeps one node per cell and charges
 * the turn from whichever parent reached the cell most cheaply: a good path, nearly always
 * within a fraction of a percent of the cheapest, much faster.
 */
public interface TurningCostModel extends CostModel {
    /** The cost of moving {@code from} to {@code to}, having arrived at {@code from} from {@code before}. */
    double cost(long before, long from, long to, MoveType type);

    /**
     * Just the turn: what {@link #cost(long, long, long, MoveType)} adds to the plain cost.
     * Searches over moves priced ahead of time ({@link MoveGraph}) add this to the stored price.
     */
    default double turnCost(long before, long from, long to, MoveType type) {
        return cost(before, from, to, type) - cost(from, to, type);
    }

    /** Whether searches should keep a node per heading, for the cheapest path counting turns. */
    default boolean exact() {
        return false;
    }

    /**
     * The turn cost by heading codes ({@link TurnPenalty#heading}): entry
     * {@code in * 26 + out} is what {@link #turnCost} adds for a step with heading {@code out}
     * after one with heading {@code in}, for any move type. Null if it isn't that simple.
     */
    default double[] turnCostByHeading() {
        return null;
    }
}
