package astar.viz;

import astar.core.BlockPoint;
import astar.core.SearchResult;
import astar.pathing.ArrayBlockView;
import astar.pathing.EntityProfile;
import astar.pathing.Waypoint;
import astar.pathing.WorldPathfinder;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Renders the demo searches to PNGs, and opens a window for each when a display is available.
 *
 * <p>Usage: {@code VizMain [output-dir]} (default {@code build/viz}).
 */
public final class VizMain {

    public static void main(String[] args) throws IOException {
        Path outDir = Path.of(args.length > 0 ? args[0] : "build/viz");
        Files.createDirectories(outDir);

        int offset = 0;
        offset = run("Flat, 4-way, Manhattan", "astar-4way.png", DemoWorlds.flat(), false,
                false, DemoWorlds.FLAT_START, DemoWorlds.FLAT_GOAL, 40, outDir, offset);
        offset = run("Flat, 8-way, Octile, smoothed", "astar-8way.png", DemoWorlds.flat(), true,
                true, DemoWorlds.FLAT_START, DemoWorlds.FLAT_GOAL, 40, outDir, offset);
        offset = run("Terrain, 8-way, smoothed: up the stairs, down via the ledge, around the lava",
                "astar-3d.png", DemoWorlds.terrain(), true, true, DemoWorlds.TERRAIN_START,
                DemoWorlds.TERRAIN_GOAL, 30, outDir, offset);
        run("Heights, 8-way, smoothed: over carpet, round the fence, up the stairs, no jumps",
                "astar-heights.png", DemoWorlds.heights(), true, true, DemoWorlds.HEIGHTS_START,
                DemoWorlds.HEIGHTS_GOAL, 30, outDir, offset);
        writeReplayFrames(outDir.resolve("frames"));

        if (!VisualizerWindow.canShow()) {
            System.out.println("No display available; wrote PNGs only.");
        }
    }

    /** Records the terrain search and writes five frames of its replay, start to finish. */
    private static void writeReplayFrames(Path dir) throws IOException {
        Files.createDirectories(dir);
        ArrayBlockView world = DemoWorlds.terrain();
        SearchRecorder recorder = new SearchRecorder();
        new WorldPathfinder(world, EntityProfile.DEFAULT, true)
                .find(DemoWorlds.TERRAIN_START, DemoWorlds.TERRAIN_GOAL, recorder);
        Recording recording = recorder.recording();
        List<Integer> layers = GridRenderer.layers(recording);

        int last = recording.lastStep();
        for (int step : new int[] {0, last / 4, last / 2, 3 * last / 4, last}) {
            FrameState frame = recording.frame(step);
            BufferedImage img = GridRenderer.render(world, frame, layers, 30,
                    "Terrain replay, step " + step + " of " + last);
            Path out = dir.resolve(String.format("terrain-step-%03d.png", step));
            ImageIO.write(img, "png", out.toFile());
            System.out.println("wrote " + out);
        }
    }

    private static int run(String label, String file, ArrayBlockView world, boolean diagonal,
            boolean smooth, BlockPoint start, BlockPoint goal, int cellPx, Path outDir,
            int offset) throws IOException {
        WorldPathfinder finder = new WorldPathfinder(world, EntityProfile.DEFAULT, diagonal);
        SearchResult result = finder.find(start, goal);
        List<Waypoint> waypoints = smooth && result.found()
                ? finder.smoother().smooth(result.path()) : List.of();
        BufferedImage img = GridRenderer.render(world, result, waypoints, start, goal, cellPx,
                label);

        Path out = outDir.resolve(file);
        ImageIO.write(img, "png", out.toFile());
        System.out.println("wrote " + out);

        if (VisualizerWindow.canShow()) {
            VisualizerWindow.show("A* — " + label, img, offset);
        }
        return offset + 40;
    }
}
