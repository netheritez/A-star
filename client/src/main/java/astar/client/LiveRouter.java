package astar.client;

import astar.core.BlockPoint;
import astar.core.PathStep;
import astar.movement.exec.GridRouter;
import astar.movement.exec.Router;
import astar.movement.sim.SimWorld;
import astar.movement.plan.StraightRoute;
import astar.pathing.WorldPathfinder;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.world.level.Level;

/**
 * The recovery's view of the game: a {@link GridRouter} on a {@link LiveWorld} copy that
 * re-reads blocks from the game where they matter, the steps ahead before it checks them and
 * the blocks around the player before it plans again, so blocks placed or broken, and doors
 * shut, since the copy was taken are seen.
 */
final class LiveRouter implements Router {

    /** How far around the player {@link #refresh} re-reads, across and up and down. */
    private static final int AROUND = 8;

    private final LiveWorld world;
    private final GridRouter grid;
    private final Supplier<Level> level;
    private final Supplier<double[]> player;

    /**
     * @param player where the player is now, in the copy's coordinates
     */
    LiveRouter(LiveWorld world, WorldPathfinder finder, Supplier<Level> level,
            Supplier<double[]> player) {
        this.world = world;
        this.grid = new GridRouter(finder, world);
        this.level = level;
        this.player = player;
    }

    @Override
    public SimWorld world() {
        return world;
    }

    @Override
    public BlockPoint standingAt(double x, double y, double z) {
        return grid.standingAt(x, y, z);
    }

    @Override
    public List<PathStep> find(BlockPoint from, BlockPoint to, Set<BlockPoint> avoid,
            int budget) {
        return grid.find(from, to, avoid, budget);
    }

    @Override
    public StraightRoute straighten(List<PathStep> path) {
        return grid.straighten(path);
    }

    @Override
    public int firstBlocked(List<PathStep> path, int from, int to) {
        Level l = level.get();
        if (l != null) {
            for (int i = Math.max(0, from); i <= Math.min(to, path.size() - 1); i++) {
                BlockPoint c = path.get(i).pos();
                // The floor, the feet and the head.
                for (int dy = -1; dy <= 2; dy++) {
                    world.sync(l, c.x(), c.y() + dy, c.z());
                }
            }
        }
        return grid.firstBlocked(path, from, to);
    }

    @Override
    public void refresh() {
        Level l = level.get();
        if (l == null) {
            return;
        }
        double[] p = player.get();
        int px = (int) Math.floor(p[0]);
        int py = (int) Math.floor(p[1]);
        int pz = (int) Math.floor(p[2]);
        for (int x = px - AROUND; x <= px + AROUND; x++) {
            for (int y = py - AROUND; y <= py + AROUND; y++) {
                for (int z = pz - AROUND; z <= pz + AROUND; z++) {
                    world.sync(l, x, y, z);
                }
            }
        }
    }
}
