package astar.core;

import java.util.List;
import java.util.Set;

/**
 * What a search produced: the path plus what it looked at, for diagnostics.
 *
 * @param path steps from start to goal inclusive; empty unless {@code status} is FOUND
 * @param cost total path cost, or {@code Double.POSITIVE_INFINITY} if there is no path
 * @param closed positions that were fully expanded
 * @param open positions still waiting in the open set
 * @param expanded number of nodes expanded
 */
public record SearchResult(
        Status status,
        List<PathStep> path,
        double cost,
        Set<BlockPoint> closed,
        Set<BlockPoint> open,
        int expanded) {

    public enum Status {
        RUNNING,
        FOUND,
        NO_PATH
    }

    public static SearchResult noPath() {
        return new SearchResult(
                Status.NO_PATH, List.of(), Double.POSITIVE_INFINITY, Set.of(), Set.of(), 0);
    }

    public boolean found() {
        return status == Status.FOUND;
    }

    public List<BlockPoint> positions() {
        return path.stream().map(PathStep::pos).toList();
    }
}
