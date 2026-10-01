package astar.core;

/** Maps positions to nodes, creating each node the first time the search reaches it. */
interface NodeStore {
    PathNode get(long pos);

    int size();
}
