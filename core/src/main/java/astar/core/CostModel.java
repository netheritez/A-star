package astar.core;

/**
 * What a single move costs.
 *
 * <p>The standard heuristics ({@link Heuristics#OCTILE_XZ} and the others) assume every move
 * costs at least its horizontal length (1 straight, sqrt(2) diagonal); then A* returns the
 * cheapest path. For cheaper moves, use a heuristic scaled to them, such as
 * {@link DefaultCostModel#heuristic(int)}.
 */
@FunctionalInterface
public interface CostModel {
    double cost(long from, long to, MoveType type);
}
