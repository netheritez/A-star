package astar.core;

/**
 * Watches a search as it runs, for diagnostics. All methods default to doing nothing.
 *
 * <p>Events carry {@link NodeView} copies, never live nodes. When the listener is {@link #NONE}
 * the search skips building them, so an unobserved search costs nothing extra.
 */
public interface SearchListener {
    SearchListener NONE = new SearchListener() {};

    default void onStart(NodeView start, BlockPoint goal) {}

    /** A node was taken from the open set and moved to the closed set. */
    default void onExpand(NodeView node) {}

    /** A node was added to the open set for the first time. */
    default void onOpen(NodeView node, double stepCost) {}

    /** A cheaper path was found to a node already in the open set. */
    default void onImprove(NodeView node, double oldG) {}

    default void onFinish(SearchResult result) {}
}
