package astar.core;

import java.util.HashMap;
import java.util.Map;

/** A {@link NodeStore} backed by a hash map, so the world can be unbounded. */
final class HashNodeStore implements NodeStore {
    private final Map<Long, PathNode> nodes = new HashMap<>();

    @Override
    public PathNode get(long pos) {
        return nodes.computeIfAbsent(pos, PathNode::new);
    }

    @Override
    public int size() {
        return nodes.size();
    }
}
