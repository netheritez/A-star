package astar.movement.exec;

import astar.core.BlockPoint;
import astar.core.PathStep;
import astar.movement.plan.StraightRoute;
import astar.movement.sim.SimWorld;
import java.util.List;
import java.util.Set;

/**
 * What a {@link Journey} needs from the world to recover: the blocks as they are now, paths
 * through them, and whether a path it's following can still be walked. {@link GridRouter}
 * does this on a pathfinder's block grid; the game client re-reads the blocks it copied.
 */
public interface Router {

    /** The blocks as they are now, to plan on and to predict the player with. */
    SimWorld world();

    /** The cell a player standing at this position is in, or null if it isn't standing. */
    BlockPoint standingAt(double x, double y, double z);

    /**
     * A path between two cells.
     *
     * @param avoid cells to treat as walls
     * @param budget the most nodes to expand, or 0 for no limit
     * @return the steps from {@code from} to {@code to}, or an empty list if there's no path
     *     (within the budget)
     */
    List<PathStep> find(BlockPoint from, BlockPoint to, Set<BlockPoint> avoid, int budget);

    /**
     * The first step from {@code from + 1} to {@code to} that can't be reached from the step
     * before it the way the path says any more (a block was placed, a door closed), or -1.
     */
    int firstBlocked(List<PathStep> path, int from, int to);

    /**
     * A path laid along its smoothed lines ({@link StraightRoute}), or null to walk its grid
     * steps as they are (the default).
     */
    default StraightRoute straighten(List<PathStep> path) {
        return null;
    }

    /** Re-reads what may have changed before a new path is found; nothing by default. */
    default void refresh() {}
}
