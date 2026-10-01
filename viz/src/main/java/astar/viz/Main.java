package astar.viz;

import astar.core.BlockPoint;
import astar.core.PathStep;
import astar.core.SearchResult;
import astar.pathing.ArrayBlockView;
import astar.pathing.EntityProfile;
import astar.pathing.PathAnalysis;
import astar.pathing.PathSmoother;
import astar.pathing.Waypoint;
import astar.pathing.WorldPathfinder;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Console demo: hardcoded worlds, start and goal; paths printed to the console. */
public final class Main {

    public static void main(String[] args) {
        runFlat("Flat, 4-way, Manhattan", false);
        runFlat("Flat, 8-way, Octile", true);
        runTerrain();
    }

    private static void runFlat(String label, boolean diagonal) {
        ArrayBlockView world = DemoWorlds.flat();
        SearchResult r = new WorldPathfinder(world, EntityProfile.DEFAULT, diagonal)
                .find(DemoWorlds.FLAT_START, DemoWorlds.FLAT_GOAL);
        header(label, r);
        if (r.found()) {
            System.out.println(renderFlat(world, r.positions()));
            System.out.println();
        }
    }

    private static void runTerrain() {
        WorldPathfinder finder = new WorldPathfinder(DemoWorlds.terrain(), EntityProfile.DEFAULT,
                true);
        SearchResult r = finder.find(DemoWorlds.TERRAIN_START, DemoWorlds.TERRAIN_GOAL);
        header("Terrain (3D), 8-way: stairs, a cliff, a ledge and lava", r);
        if (!r.found()) {
            return;
        }
        for (PathStep s : r.path()) {
            System.out.printf("  %-14s %s%n", s.pos(), s.via() == null ? "start" : s.via());
        }
        System.out.println("key nodes: " + PathAnalysis.keyNodes(r.path()).stream()
                .map(PathStep::pos).toList());

        List<Waypoint> waypoints = finder.smoother().smooth(r.path());
        System.out.printf("smoothed: %d waypoints (from %d steps), %.3f blocks long%n",
                waypoints.size(), r.path().size(), PathSmoother.length(PathSmoother.positions(waypoints)));
        for (Waypoint w : waypoints) {
            System.out.printf("  %-14s %s%n", w.pos(), w.kind());
        }
    }

    private static void header(String label, SearchResult r) {
        System.out.println("== " + label + " ==");
        if (!r.found()) {
            System.out.println("No path found.");
            return;
        }
        System.out.println("steps:    " + (r.path().size() - 1));
        System.out.printf("cost:     %.3f%n", r.cost());
        System.out.println("expanded: " + r.expanded());
    }

    /** The feet level (y = 1) of a flat world as ASCII. */
    private static String renderFlat(ArrayBlockView world, List<BlockPoint> path) {
        Set<BlockPoint> onPath = new HashSet<>(path);
        BlockPoint start = path.get(0);
        BlockPoint goal = path.get(path.size() - 1);
        StringBuilder sb = new StringBuilder();
        for (int z = 0; z < world.sizeZ(); z++) {
            for (int x = 0; x < world.sizeX(); x++) {
                BlockPoint p = new BlockPoint(x, 1, z);
                if (p.equals(start)) {
                    sb.append('S');
                } else if (p.equals(goal)) {
                    sb.append('G');
                } else if (!world.blockAt(x, 1, z).passable()) {
                    sb.append('#');
                } else if (onPath.contains(p)) {
                    sb.append('*');
                } else {
                    sb.append('.');
                }
            }
            if (z + 1 < world.sizeZ()) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }
}
