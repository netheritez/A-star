package astar.viz;

import astar.core.BlockPoint;
import astar.core.NodeView;
import astar.core.SearchListener;
import astar.core.SearchResult;
import java.util.ArrayList;
import java.util.List;

/** A listener that stores every callback as a {@link SearchEvent}, for replay. */
public final class SearchRecorder implements SearchListener {
    private final List<SearchEvent> events = new ArrayList<>();

    @Override
    public void onStart(NodeView start, BlockPoint goal) {
        events.add(new SearchEvent.Started(start, goal));
    }

    @Override
    public void onExpand(NodeView node) {
        events.add(new SearchEvent.Expanded(node));
    }

    @Override
    public void onOpen(NodeView node, double stepCost) {
        events.add(new SearchEvent.Opened(node, stepCost));
    }

    @Override
    public void onImprove(NodeView node, double oldG) {
        events.add(new SearchEvent.Improved(node, oldG));
    }

    @Override
    public void onFinish(SearchResult result) {
        events.add(new SearchEvent.Finished(result));
    }

    /**
     * True if no search ran, e.g. because {@link astar.pathing.WorldPathfinder} refused a start
     * or goal that can't be stood on.
     */
    public boolean isEmpty() {
        return events.isEmpty();
    }

    /** @throws IllegalStateException if nothing was recorded (see {@link #isEmpty()}) */
    public Recording recording() {
        if (events.isEmpty()) {
            throw new IllegalStateException("No search was recorded");
        }
        return new Recording(events);
    }
}
