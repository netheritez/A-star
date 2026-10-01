package astar.viz;

import astar.core.BlockPoint;
import astar.pathing.ArrayBlockView;
import java.util.function.Supplier;

/**
 * A world the editor can load: a name, a way to make a fresh copy (so Reset discards edits),
 * default start and goal, and where local (0, 0, 0) sits in the game, for imported maps.
 */
public record WorldSource(String name, Supplier<ArrayBlockView> factory, BlockPoint start,
        BlockPoint goal, BlockPoint origin) {

    public static final BlockPoint NO_OFFSET = new BlockPoint(0, 0, 0);

    public boolean imported() {
        return !origin.equals(NO_OFFSET);
    }

    /** Local coordinates to in-game coordinates. */
    public BlockPoint toWorld(BlockPoint local) {
        return local.offset(origin.x(), origin.y(), origin.z());
    }

    @Override
    public String toString() {
        return name;
    }
}
