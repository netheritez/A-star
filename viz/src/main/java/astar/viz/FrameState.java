package astar.viz;

import astar.core.BlockPoint;
import astar.core.NodeView;
import astar.core.PathStep;
import astar.core.SearchResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The search as it stood after {@code step} expansions.
 *
 * @param nodes every node reached so far, with its values at this step
 * @param current the node expanded in this step, or {@code null} at step 0
 * @param result the finished result, or {@code null} while the search is still running
 */
public record FrameState(
        int step,
        BlockPoint start,
        BlockPoint goal,
        Map<BlockPoint, NodeView> nodes,
        Set<BlockPoint> open,
        Set<BlockPoint> closed,
        BlockPoint current,
        SearchResult result) {

    /** A frame for a search that never ran, e.g. because the start was inside a wall. */
    public static FrameState empty(BlockPoint start, BlockPoint goal) {
        return new FrameState(0, start, goal, Map.of(), Set.of(), Set.of(), null,
                SearchResult.noPath());
    }

    public boolean finished() {
        return result != null;
    }

    /**
     * The path to show: the final path once finished, otherwise the best path found so far to
     * the node being expanded.
     */
    public List<PathStep> path() {
        if (result != null) {
            return result.path();
        }
        return current == null ? List.of() : pathTo(current);
    }

    /** Follows parent links, as they stood at this step, back from {@code p} to the start. */
    public List<PathStep> pathTo(BlockPoint p) {
        List<PathStep> path = new ArrayList<>();
        for (NodeView n = nodes.get(p); n != null; n = n.parent() == null ? null : nodes.get(n.parent())) {
            path.add(new PathStep(n.pos(), n.via()));
            if (path.size() > nodes.size()) {
                throw new IllegalStateException("Parent links form a cycle at " + n.pos());
            }
        }
        Collections.reverse(path);
        return path;
    }

    /** The node's values at this step, or {@code null} if the search hasn't reached it yet. */
    public NodeView inspect(BlockPoint p) {
        return nodes.get(p);
    }
}
