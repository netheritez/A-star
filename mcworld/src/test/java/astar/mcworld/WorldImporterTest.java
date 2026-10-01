package astar.mcworld;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import astar.core.BlockPoint;
import astar.core.SearchResult;
import astar.pathing.ArrayBlockView;
import astar.pathing.BlockType;
import astar.pathing.EntityProfile;
import astar.pathing.Reachability;
import astar.pathing.WorldPathfinder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Checks the reader against a world written by tools/make_test_world.py, which uses nbtlib, an
 * NBT implementation independent of ours.
 */
class WorldImporterTest {
    private static final Path WORLD = Path.of("src/test/resources/skyblock-test");

    private static String describe(int x, int y, int z, BlockState b) {
        String props = new TreeMap<>(b.properties()).entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining(","));
        return x + " " + y + " " + z + " " + b.name() + (props.isEmpty() ? "" : "[" + props + "]");
    }

    @Test
    void decodesEveryBlockExactlyAsWritten() throws IOException {
        Set<String> expected = new HashSet<>(Files.readAllLines(WORLD.resolve("expected-blocks.txt")));
        Set<String> actual = new HashSet<>();
        try (var files = Files.list(WorldImporter.regionDir(WORLD))) {
            for (Path file : files.filter(RegionFile::isRegionFile).toList()) {
                RegionFile region = new RegionFile(file);
                for (int lz = 0; lz < 32; lz++) {
                    for (int lx = 0; lx < 32; lx++) {
                        Map<String, Object> root = region.readChunk(lx, lz);
                        if (root != null) {
                            Chunk.decode(root).forEachNonAir(
                                    (x, y, z, b) -> assertTrue(actual.add(describe(x, y, z, b))));
                        }
                    }
                }
            }
        }
        Set<String> missing = new HashSet<>(expected);
        missing.removeAll(actual);
        Set<String> extra = new HashSet<>(actual);
        extra.removeAll(expected);
        assertEquals(Set.of(), missing, "blocks not decoded");
        assertEquals(Set.of(), extra, "blocks decoded that weren't written");
        assertEquals(5057, actual.size());
    }

    @Test
    void findsEachIslandWithItsBounds() throws IOException {
        List<Island> islands = WorldImporter.scan(WORLD);
        assertEquals(3, islands.size(), islands.toString());

        Island cube = islands.get(0); // largest first
        assertEquals(List.of(-640, 64, -640, -625, 79, -625, 4096L),
                List.of(cube.minX(), cube.minY(), cube.minZ(), cube.maxX(), cube.maxY(), cube.maxZ(),
                        cube.blocks()));

        Island main = islands.get(1);
        assertEquals(List.of(-12, 57, -6, 9, 64, 8),
                List.of(main.minX(), main.minY(), main.minZ(), main.maxX(), main.maxY(), main.maxZ()));
        assertEquals(4, main.chunks(), "spans chunks -1..0 on both axes");

        Island deep = islands.get(2);
        assertEquals(List.of(300, -30, 300, 309, -29, 305),
                List.of(deep.minX(), deep.minY(), deep.minZ(), deep.maxX(), deep.maxY(), deep.maxZ()));
        assertEquals(List.of(1, 2, 3), islands.stream().map(Island::index).toList());
    }

    @Test
    void acceptsTheWorldFolderTheRegionFolderOrNeither() throws IOException {
        assertEquals(WorldImporter.regionDir(WORLD), WorldImporter.regionDir(WORLD.resolve("region")));
        Path empty = Files.createTempDirectory("no-world");
        IOException e = assertThrows(IOException.class, () -> WorldImporter.regionDir(empty));
        assertTrue(e.getMessage().contains("level.dat"), e.getMessage());
    }

    private static ImportedWorld mainIsland() throws IOException {
        return WorldImporter.load(WORLD, WorldImporter.scan(WORLD).get(1));
    }

    private static BlockType at(ImportedWorld w, int x, int y, int z) {
        BlockPoint o = w.origin();
        return w.world().blockAt(x - o.x(), y - o.y(), z - o.z());
    }

    @Test
    void importsTheIslandWithMarginsAndMapsBlocks() throws IOException {
        ImportedWorld w = mainIsland();
        ArrayBlockView world = w.world();
        assertEquals(new BlockPoint(-12 - WorldImporter.MARGIN, 56, -6 - WorldImporter.MARGIN), w.origin());
        assertEquals(22 + 2 * WorldImporter.MARGIN, world.sizeX());
        assertEquals(8 + 1 + WorldImporter.HEADROOM, world.sizeY());
        assertEquals(15 + 2 * WorldImporter.MARGIN, world.sizeZ());
        assertEquals(new BlockPoint(0, 60, 0), w.toWorld(new BlockPoint(14, 4, 8)));

        assertEquals(BlockType.SOLID, at(w, 3, 60, 3), "grass block");
        assertEquals(BlockType.HAZARD, at(w, 0, 60, 0), "lava");
        assertEquals(BlockType.WATER, at(w, 5, 60, 5), "water");
        assertEquals(BlockType.TALL, at(w, -4, 61, 0), "fence: 1.5 tall");
        assertEquals(BlockType.AIR, at(w, -4, 62, 0), "above a fence (its top half is part of the fence)");
        assertEquals(BlockType.DOOR, at(w, -1, 61, 6), "closed door");
        assertEquals(BlockType.AIR, at(w, 3, 60, 7), "open trapdoor leaves a hole");
        assertEquals(BlockType.AIR, at(w, 2, 61, 2), "dandelion");
        assertEquals(BlockType.AIR, at(w, -2, 61, 4), "torch");
        assertEquals(BlockType.SOLID, at(w, 5, 60, 8), "wool");
        assertEquals(BlockType.AIR, at(w, 11, 61, 0), "the margin beyond the island is air");
        assertEquals(BlockType.VOID, at(w, 20, 61, 0), "beyond the imported box is void");
    }

    @Test
    void notesTheRealShapesOfOddShapedBlocks() throws IOException {
        ImportedWorld w = mainIsland();
        BlockPoint fence = new BlockPoint(-4, 61, 0).offset(-w.origin().x(), -w.origin().y(),
                -w.origin().z());
        BlockShapes.Shape shape = w.shapes().at(fence.x(), fence.y(), fence.z(), BlockType.TALL);
        assertNotNull(shape);
        assertEquals("fence", shape.kind());
        // A post and its arms north and south, 1.5 high and a quarter of a block across.
        assertEquals(1.5, shape.boxes().get(0)[4]);
        assertEquals(0.375, shape.boxes().get(0)[0]);
        assertNull(w.shapes().at(fence.x(), fence.y(), fence.z(), BlockType.SOLID),
                "a cell edited since the import falls back to its type");
        BlockPoint grass = new BlockPoint(3, 60, 3).offset(-w.origin().x(), -w.origin().y(),
                -w.origin().z());
        assertNull(w.shapes().at(grass.x(), grass.y(), grass.z(), BlockType.SOLID),
                "a full cube needs no shape");
    }

    @Test
    void pathsOnTheImportedIslandRespectTheBlocks() throws IOException {
        ImportedWorld w = mainIsland();
        BlockPoint o = w.origin();
        WorldPathfinder finder = new WorldPathfinder(w.world(), EntityProfile.DEFAULT, true);
        // From west of the fence line to east of it: the fence (x = -4, z -6..2) can't be jumped,
        // so the path must go around its south end at z >= 3.
        BlockPoint from = new BlockPoint(-8 - o.x(), 61 - o.y(), -5 - o.z());
        BlockPoint to = new BlockPoint(0 - o.x(), 61 - o.y(), -5 - o.z());
        SearchResult r = finder.find(from, to);
        assertTrue(r.found());
        assertTrue(r.positions().stream().anyMatch(p -> p.z() + o.z() >= 3), "goes round the fence");
        assertTrue(r.positions().stream().noneMatch(p -> p.x() + o.x() == -4 && p.z() + o.z() <= 2));

        // Up the stair and onto the brick platform.
        BlockPoint platform = new BlockPoint(8 - o.x(), 63 - o.y(), -5 - o.z());
        assertTrue(finder.find(from, platform).found(), "reaches the raised platform");

        Reachability.Route route = Reachability.longestRoute(w.world(), EntityProfile.DEFAULT, true)
                .orElseThrow();
        assertTrue(route.cost() > 15, "a long route across the island: " + route);
        assertTrue(finder.find(route.start(), route.goal()).found());
    }

    @Test
    void theDeepIslandAndTheCubeImportToo() throws IOException {
        List<Island> islands = WorldImporter.scan(WORLD);
        ImportedWorld deep = WorldImporter.load(WORLD, islands.get(2));
        assertEquals(BlockType.SOLID, at(deep, 300, -30, 300));
        assertEquals(BlockType.TALL, at(deep, 306, -29, 303), "the cobblestone wall");
        assertEquals(BlockType.AIR, at(deep, 306, -28, 303), "above the cobblestone wall");
        ImportedWorld cube = WorldImporter.load(WORLD, islands.get(0));
        assertEquals(BlockType.SOLID, at(cube, -632, 70, -632));
    }

    @Test
    void rejectsLz4AndPre118Chunks() throws IOException {
        Path dir = Files.createTempDirectory("lz4-world").resolve("region");
        Files.createDirectories(dir);
        byte[] region = new byte[3 * 4096];
        region[2] = 2;  // chunk (0, 0) at sector 2
        region[3] = 1;  // one sector long
        region[2 * 4096 + 3] = 2; // length 2
        region[2 * 4096 + 4] = 4; // compression 4: LZ4
        Path file = dir.resolve("r.0.0.mca");
        Files.write(file, region);
        IOException e = assertThrows(IOException.class, () -> new RegionFile(file).readChunk(0, 0));
        assertTrue(e.getMessage().contains("LZ4"), e.getMessage());

        IllegalArgumentException old = assertThrows(IllegalArgumentException.class,
                () -> Chunk.decode(Map.of("Level", Map.of())));
        assertTrue(old.getMessage().contains("1.18"));
    }

    @Test
    void sealsTheRoofOnlyOnCappedIslands() throws IOException {
        List<Island> islands = WorldImporter.scan(WORLD);
        ImportedWorld cube = WorldImporter.load(WORLD, islands.get(0)); // a solid 16^3 box
        assertTrue(WorldImporter.sealRoofIfCapped(cube));
        EntityProfile p = EntityProfile.DEFAULT;
        var v = new astar.pathing.MoveValidator(cube.world(), p);
        for (int y = 0; y < cube.world().sizeY(); y++) {
            assertFalse(v.canStand(8, y, 8), "nowhere to stand on a sealed box, y " + y);
        }

        ImportedWorld open = mainIsland(); // a skyblock-style island, open to the sky
        assertFalse(WorldImporter.sealRoofIfCapped(open));
        assertEquals(BlockType.AIR, at(open, 3, 61, 3), "left alone");
    }
}
