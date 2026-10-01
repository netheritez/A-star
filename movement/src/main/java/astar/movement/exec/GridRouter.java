package astar.movement.exec;

import astar.core.AStarSearch;
import astar.core.BlockPoint;
import astar.core.MoveSource;
import astar.core.PathStep;
import astar.core.SearchResult;
import astar.movement.plan.StraightRoute;
import astar.movement.sim.SimWorld;
import astar.pathing.BlockView;
import astar.pathing.WorldPathfinder;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A {@link Router} on a pathfinder's block grid, with the simulator's view of the same blocks.
 * Both must show the same world; edits to it show up in both.
 */
public final class GridRouter implements Router {

    private final WorldPathfinder finder;
    private final SimWorld world;

    public GridRouter(WorldPathfinder finder, SimWorld world) {
        this.finder = finder;
        this.world = world;
    }

    @Override
    public SimWorld world() {
        return world;
    }

    /**
     * The cell whose floor the feet are on: the block the feet are in, or the one below when
     * they rest on its top (a full block's floor is its top, a slab's its middle).
     */
    @Override
    public BlockPoint standingAt(double x, double y, double z) {
        int cy = (int) Math.floor(y + 1e-3);
        // The column the feet are in first, then the ones the body overlaps.
        double[][] columns = {{x, z}, {x - 0.3, z - 0.3}, {x + 0.3, z - 0.3},
                {x - 0.3, z + 0.3}, {x + 0.3, z + 0.3}};
        for (double[] c : columns) {
            int cx = (int) Math.floor(c[0]);
            int cz = (int) Math.floor(c[1]);
            for (int dy : new int[] {0, -1, 1}) {
                BlockPoint p = new BlockPoint(cx, cy + dy, cz);
                if (finder.canStand(p) && Math.abs(finder.validator().elevation(p) - y) < 0.6) {
                    return p;
                }
            }
        }
        // On something the grid doesn't stand on (a fence top, the lip of an edge): the
        // nearest cell it does, a step away, to walk back from.
        BlockPoint best = null;
        double bestD = 2.25;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -2; dy <= 1; dy++) {
                    BlockPoint p = new BlockPoint((int) Math.floor(x) + dx, cy + dy,
                            (int) Math.floor(z) + dz);
                    if (!finder.canStand(p)) {
                        continue;
                    }
                    double e = finder.validator().elevation(p);
                    double d = sq(p.x() + 0.5 - x) + sq(p.z() + 0.5 - z) + sq(e - y);
                    if (d < bestD && e <= y + 0.6) {
                        bestD = d;
                        best = p;
                    }
                }
            }
        }
        return best;
    }

    private static double sq(double v) {
        return v * v;
    }

    @Override
    public List<PathStep> find(BlockPoint from, BlockPoint to, Set<BlockPoint> avoid,
            int budget) {
        return find(finder, from, to, avoid, budget);
    }

    private static List<PathStep> find(WorldPathfinder finder, BlockPoint from, BlockPoint to,
            Set<BlockPoint> avoid, int budget) {
        if (!finder.canStand(from) || !finder.canStand(to)) {
            return List.of();
        }
        Set<Long> walls = new HashSet<>();
        for (BlockPoint p : avoid) {
            if (!p.equals(from) && !p.equals(to)) {
                walls.add(p.pack());
            }
        }
        MoveSource moves = finder.moves();
        MoveSource around = walls.isEmpty() ? moves : (pos, sink) -> moves.moves(pos,
                (next, type) -> {
                    if (!walls.contains(next)) {
                        sink.accept(next, type);
                    }
                });
        AStarSearch search = new AStarSearch(from, to, around, finder.costs(),
                finder.heuristic());
        if (budget > 0) {
            search.run(budget);
        } else {
            search.runToEnd();
        }
        SearchResult r = search.result();
        return r.found() ? r.path() : List.of();
    }

    @Override
    public StraightRoute straighten(List<PathStep> path) {
        return StraightRoute.of(path, StraightRoute.smoother(finder).smooth(path),
                finder.moves());
    }


    @Override
    public int firstBlocked(List<PathStep> path, int from, int to) {
        MoveSource moves = finder.moves();
        for (int i = Math.max(1, from + 1); i <= Math.min(to, path.size() - 1); i++) {
            long a = path.get(i - 1).pos().pack();
            long b = path.get(i).pos().pack();
            boolean[] found = {false};
            moves.moves(a, (next, type) -> found[0] |= next == b);
            if (!found[0]) {
                return i;
            }
        }
        return -1;
    }

    /** The blocks the pathfinder sees. */
    public BlockView blocks() {
        return finder.validator().world();
    }
}
