package astar.mcworld;

import astar.core.BlockPoint;
import astar.pathing.ArrayBlockView;

/**
 * An island copied into an {@link ArrayBlockView}. Local (0, 0, 0) is world {@code origin};
 * add {@code origin} to a local position to get its in-game coordinates. {@code shapes} holds
 * the real collision shapes of its stairs, slabs, fences and other odd-shaped blocks.
 */
public record ImportedWorld(String name, ArrayBlockView world, BlockPoint origin, Island island,
        ImportedShapes shapes) {

    public BlockPoint toWorld(BlockPoint local) {
        return new BlockPoint(local.x() + origin.x(), local.y() + origin.y(), local.z() + origin.z());
    }
}
