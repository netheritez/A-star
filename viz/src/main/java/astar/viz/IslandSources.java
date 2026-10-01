package astar.viz;

import astar.core.BlockPoint;
import astar.mcworld.ImportedWorld;
import astar.mcworld.Island;
import astar.mcworld.WorldImporter;
import astar.pathing.EntityProfile;
import astar.pathing.Reachability;
import java.io.IOException;
import java.nio.file.Path;

/** Turns an island from a Minecraft world save into something the editor can load. */
public final class IslandSources {
    private IslandSources() {}

    /**
     * Imports the island and picks a start and goal far apart (see
     * {@link Reachability#longestRoute}), so there's a long route to look at straight away.
     */
    public static WorldSource load(Path worldDir, Island island, boolean diagonal) throws IOException {
        ImportedWorld imported = WorldImporter.load(worldDir, island);
        WorldImporter.sealRoofIfCapped(imported); // keep routes inside closed maps
        var route = Reachability.longestRoute(imported.world(), EntityProfile.DEFAULT, diagonal);
        BlockPoint start;
        BlockPoint goal;
        if (route.isPresent()) {
            start = route.get().start();
            goal = route.get().goal();
        } else {
            // Nowhere to stand: put both markers in the middle; the editor will say so.
            start = new BlockPoint(imported.world().sizeX() / 2, imported.world().sizeY() - 2,
                    imported.world().sizeZ() / 2);
            goal = start;
        }
        return new WorldSource(imported.name(), imported.world()::copy, start, goal,
                imported.origin());
    }
}
