package astar.viz;

import astar.core.BlockPoint;
import astar.core.NodeView;
import astar.core.SearchResult;

/** One recorded {@link astar.core.SearchListener} callback. */
public sealed interface SearchEvent {

    record Started(NodeView start, BlockPoint goal) implements SearchEvent {}

    record Expanded(NodeView node) implements SearchEvent {}

    record Opened(NodeView node, double stepCost) implements SearchEvent {}

    record Improved(NodeView node, double oldG) implements SearchEvent {}

    record Finished(SearchResult result) implements SearchEvent {}
}
