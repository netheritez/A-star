package astar.core;

/** One-call entry point: runs an {@link AStarSearch} to the end. */
public final class AStar {
    private AStar() {}

    public static SearchResult findPath(BlockPoint start, BlockPoint goal, MoveSource moves,
            CostModel costs, Heuristic heuristic) {
        return findPath(start, goal, moves, costs, heuristic, SearchListener.NONE);
    }

    public static SearchResult findPath(BlockPoint start, BlockPoint goal, MoveSource moves,
            CostModel costs, Heuristic heuristic, SearchListener listener) {
        AStarSearch search = new AStarSearch(start, goal, moves, costs, heuristic, listener);
        search.runToEnd();
        return search.result();
    }

    /**
     * Like {@link #findPath(BlockPoint, BlockPoint, MoveSource, CostModel, Heuristic)}, but
     * gives up after {@code maxExpansions}: the result's status is then still {@code RUNNING},
     * with no path. Use this on worlds with no edge, where an unreachable goal would otherwise
     * make the search run until memory runs out.
     */
    public static SearchResult findPath(BlockPoint start, BlockPoint goal, MoveSource moves,
            CostModel costs, Heuristic heuristic, int maxExpansions) {
        AStarSearch search = new AStarSearch(start, goal, moves, costs, heuristic,
                SearchListener.NONE);
        search.run(maxExpansions);
        return search.result();
    }
}
