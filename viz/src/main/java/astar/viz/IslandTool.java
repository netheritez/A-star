package astar.viz;

import astar.core.SearchResult;
import astar.mcworld.Island;
import astar.mcworld.WorldImporter;
import astar.pathing.EntityProfile;
import astar.pathing.PathSmoother;
import astar.pathing.Waypoint;
import astar.pathing.WorldPathfinder;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Lists the islands in a Minecraft 1.21 world save (a folder or a {@code .zip}), and renders each one's longest route
 * (see {@link astar.pathing.Reachability#longestRoute}) to a PNG.
 *
 * <p>Usage: {@code IslandTool [world folder or .zip] [output-dir]} (default
 * {@code build/viz/islands}). With no world, the saved one, or else the bundled Dwarven Mines
 * (see {@link SavedMap}).
 */
public final class IslandTool {
    private IslandTool() {}

    public static void main(String[] args) throws IOException {
        Path world = SavedMap.resolve(args.length > 0 ? args[0] : null,
                "Usage: ./gradlew islands --args=\"<world folder or .zip> [output dir]\"")
                .orElse(null);
        if (world == null) {
            return;
        }
        Path out = Path.of(args.length > 1 ? args[1] : "build/viz/islands");
        Files.createDirectories(out);

        List<Island> islands;
        try {
            islands = WorldImporter.scan(world);
        } catch (IOException e) {
            System.out.println(e.getMessage());
            return;
        }
        if (args.length > 0 && !args[0].isBlank()) {
            SavedMap.save(world);
        }
        System.out.println(islands.size() + " island(s) in " + world.toAbsolutePath());
        for (Island island : islands) {
            System.out.println("  " + island);
            WorldSource source = IslandSources.load(world, island, true);
            var blocks = source.factory().get();
            WorldPathfinder finder = new WorldPathfinder(blocks, EntityProfile.DEFAULT, true);
            SearchResult result = finder.find(source.start(), source.goal());
            if (!result.found()) {
                System.out.println("    no standable route found");
                continue;
            }
            List<Waypoint> waypoints = finder.smoother().smooth(result.path());
            System.out.printf("    longest route: %s -> %s (in game), %d steps, cost %.1f,"
                            + " %d waypoints, %.1f blocks smoothed%n",
                    source.toWorld(source.start()), source.toWorld(source.goal()),
                    result.path().size() - 1, result.cost(), waypoints.size(),
                    PathSmoother.length(PathSmoother.positions(waypoints)));

            // The editor's fit for these levels, without loading the island into an editor
            // (which would copy the world and search it again, recording every step).
            int levels = GridRenderer.layers(result, source.start(), source.goal()).size();
            double zoom = EditorModel.fitLevelZoom(blocks.sizeX(), levels, 1150);
            BufferedImage img = GridRenderer.render(blocks, result, waypoints, source.start(),
                    source.goal(), Math.max(4, (int) zoom), source.name() + ": longest route");
            Path file = out.resolve("island-" + island.index() + ".png");
            ImageIO.write(img, "png", file.toFile());
            System.out.println("    wrote " + file);
        }
    }
}
