package astar.pathing;

/** Read-only access to the world. The only way the pathfinder looks at blocks. */
@FunctionalInterface
public interface BlockView {
    BlockType blockAt(int x, int y, int z);
}
