package astar.mcworld;

import astar.core.BlockPoint;
import astar.pathing.ArrayBlockView;
import astar.pathing.BlockType;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Finds the islands in a Minecraft world save and copies one into an {@link ArrayBlockView}.
 *
 * <p>An island is a group of non-empty chunks that touch, including diagonally. Skyblock worlds
 * are mostly void, so each island ends up as its own group.
 */
public final class WorldImporter {
    /** Air around the island, in blocks, so paths can reach its edges. */
    public static final int MARGIN = 2;
    /** Air above the island's top block, for headroom and jumps. */
    public static final int HEADROOM = 4;
    /**
     * Refuses imports bigger than this many cells (one byte each), to keep memory sensible:
     * 200 million cells is 200 MB, enough for a 576 x 256 x 502 map such as the Dwarven Mines.
     */
    public static final long MAX_CELLS = 200_000_000L;

    private WorldImporter() {}

    /** Per-chunk extent of non-air blocks, gathered while scanning. */
    private record ChunkExtent(int cx, int cz, int minX, int minY, int minZ, int maxX, int maxY,
            int maxZ, long blocks) {}

    /** Opens {@code path} (a folder, or a {@code .zip} of one) and gives the folder to use. */
    @FunctionalInterface
    private interface WithRoot<T> {
        T apply(Path root) throws IOException;
    }

    private static <T> T open(Path path, WithRoot<T> body) throws IOException {
        if (path.toString().isBlank()) {
            throw new IOException("No world given: the path is empty");
        }
        if (!Files.exists(path)) {
            throw new IOException("Not found: " + path.toAbsolutePath()
                    + ". Check the path; if it contains spaces, put it in single quotes inside"
                    + " --args, e.g. --args=\"'C:\\My Maps\\world.zip'\"");
        }
        if (Files.isRegularFile(path) && path.getFileName().toString().toLowerCase().endsWith(".zip")) {
            try (FileSystem zip = FileSystems.newFileSystem(path)) {
                return body.apply(zip.getRootDirectories().iterator().next());
            }
        }
        if (!Files.isDirectory(path)) {
            throw new IOException(path + " isn't a folder or a .zip file");
        }
        return body.apply(path);
    }

    /**
     * The overworld region folders under {@code root}: {@code root/region}, {@code root} itself
     * if it holds region files, or any {@code region} folder a few levels down (for a zip with
     * the world inside a folder, or several worlds). Nether and End folders are skipped.
     */
    static List<Path> regionDirs(Path root) throws IOException {
        if (hasRegionFiles(root.resolve("region"))) {
            return List.of(root.resolve("region"));
        }
        if (hasRegionFiles(root)) {
            return List.of(root);
        }
        List<Path> found = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root, 4)) {
            for (Path dir : walk.filter(Files::isDirectory).toList()) {
                if (dir.getFileName() != null && dir.getFileName().toString().equals("region")
                        && !isOtherDimension(root.relativize(dir)) && hasRegionFiles(dir)) {
                    found.add(dir);
                }
            }
        }
        if (found.isEmpty()) {
            throw new IOException("No Minecraft world found in " + root
                    + ". Pick the world folder (the one with level.dat) or a .zip of it.");
        }
        found.sort(Comparator.comparing(Path::toString));
        return found;
    }

    private static boolean isOtherDimension(Path relative) {
        for (Path part : relative) {
            String name = part.toString();
            if (name.equals("DIM-1") || name.equals("DIM1") || name.equals("dimensions")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasRegionFiles(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.anyMatch(RegionFile::isRegionFile);
        }
    }

    /** The first overworld region folder, for callers that only want to read files directly. */
    public static Path regionDir(Path path) throws IOException {
        return regionDirs(path).get(0);
    }

    /**
     * Lists the islands in a world folder or a {@code .zip} (which may hold several worlds),
     * largest first.
     */
    public static List<Island> scan(Path worldPath) throws IOException {
        return open(worldPath, root -> {
            List<Island> all = new ArrayList<>();
            for (Path dir : regionDirs(root)) {
                all.addAll(cluster(extents(dir), relative(root, dir)));
            }
            all.sort(Comparator.comparingLong(Island::blocks).reversed()
                    .thenComparing(Island::location)
                    .thenComparingInt(Island::minX).thenComparingInt(Island::minZ));
            List<Island> numbered = new ArrayList<>();
            for (int i = 0; i < all.size(); i++) {
                Island x = all.get(i);
                numbered.add(new Island(i + 1, x.minX(), x.minY(), x.minZ(), x.maxX(), x.maxY(),
                        x.maxZ(), x.blocks(), x.chunks(), x.location()));
            }
            return numbered;
        });
    }

    private static String relative(Path root, Path dir) {
        String r = root.relativize(dir).toString().replace('\\', '/');
        return r.isEmpty() ? "." : r;
    }

    private static Map<Long, ChunkExtent> extents(Path regionDir) throws IOException {
        Map<Long, ChunkExtent> extents = new HashMap<>();
        for (Path file : regionFiles(regionDir)) {
            RegionFile region = new RegionFile(file);
            for (int lz = 0; lz < 32; lz++) {
                for (int lx = 0; lx < 32; lx++) {
                    Map<String, Object> root = region.readChunk(lx, lz);
                    if (root == null) {
                        continue;
                    }
                    Chunk chunk = Chunk.decode(root);
                    if (!chunk.generated()) {
                        continue;
                    }
                    int[] box = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
                        Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
                    long[] count = {0};
                    chunk.forEachNonAir((x, y, z, b) -> {
                        count[0]++;
                        box[0] = Math.min(box[0], x);
                        box[1] = Math.min(box[1], y);
                        box[2] = Math.min(box[2], z);
                        box[3] = Math.max(box[3], x);
                        box[4] = Math.max(box[4], y);
                        box[5] = Math.max(box[5], z);
                    });
                    if (count[0] > 0) {
                        extents.put(key(chunk.chunkX(), chunk.chunkZ()), new ChunkExtent(
                                chunk.chunkX(), chunk.chunkZ(), box[0], box[1], box[2],
                                box[3], box[4], box[5], count[0]));
                    }
                }
            }
        }
        return extents;
    }

    private static List<Island> cluster(Map<Long, ChunkExtent> extents, String location) {
        record Group(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, long blocks,
                int chunks) {}
        List<Group> groups = new ArrayList<>();
        Map<Long, Boolean> seen = new HashMap<>();
        for (ChunkExtent start : extents.values()) {
            if (seen.containsKey(key(start.cx(), start.cz()))) {
                continue;
            }
            int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
                Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
            long blocks = 0;
            int chunks = 0;
            ArrayDeque<ChunkExtent> queue = new ArrayDeque<>();
            queue.add(start);
            seen.put(key(start.cx(), start.cz()), true);
            while (!queue.isEmpty()) {
                ChunkExtent e = queue.poll();
                chunks++;
                blocks += e.blocks();
                b[0] = Math.min(b[0], e.minX());
                b[1] = Math.min(b[1], e.minY());
                b[2] = Math.min(b[2], e.minZ());
                b[3] = Math.max(b[3], e.maxX());
                b[4] = Math.max(b[4], e.maxY());
                b[5] = Math.max(b[5], e.maxZ());
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        long k = key(e.cx() + dx, e.cz() + dz);
                        ChunkExtent n = extents.get(k);
                        if (n != null && !seen.containsKey(k)) {
                            seen.put(k, true);
                            queue.add(n);
                        }
                    }
                }
            }
            groups.add(new Group(b[0], b[1], b[2], b[3], b[4], b[5], blocks, chunks));
        }
        groups.sort(Comparator.comparingLong(Group::blocks).reversed()
                .thenComparingInt(Group::minX).thenComparingInt(Group::minZ));
        List<Island> islands = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++) {
            Group g = groups.get(i);
            islands.add(new Island(i + 1, g.minX(), g.minY(), g.minZ(), g.maxX(), g.maxY(),
                    g.maxZ(), g.blocks(), g.chunks(), location));
        }
        return islands;
    }

    /**
     * Copies one island into a block world, with {@link #MARGIN} blocks of air around it and
     * {@link #HEADROOM} above. Local y = 0 is one block below the island's lowest block.
     */
    public static ImportedWorld load(Path worldPath, Island island) throws IOException {
        return open(worldPath, root -> load(worldPath, root, island));
    }

    private static ImportedWorld load(Path worldPath, Path root, Island island) throws IOException {
        int ox = island.minX() - MARGIN;
        int oy = island.minY() - 1;
        int oz = island.minZ() - MARGIN;
        int sx = island.sizeX() + 2 * MARGIN;
        int sy = island.sizeY() + 1 + HEADROOM;
        int sz = island.sizeZ() + 2 * MARGIN;
        long cells = (long) sx * sy * sz;
        if (cells > MAX_CELLS) {
            throw new IOException(String.format("%s is too big to import (%,d cells; the limit is %,d)",
                    island, cells, MAX_CELLS));
        }
        ArrayBlockView world = new ArrayBlockView(sx, sy, sz);
        ImportedShapes shapes = new ImportedShapes();

        Path regions = island.location().equals(".") ? root : root.resolve(island.location());
        // Each region file is read once, not once per chunk: reading one from a zip inflates
        // the whole file (several MB), which made importing the Mines take tens of seconds.
        Map<Path, RegionFile> opened = new HashMap<>();
        for (int cx = Math.floorDiv(island.minX(), 16); cx <= Math.floorDiv(island.maxX(), 16); cx++) {
            for (int cz = Math.floorDiv(island.minZ(), 16); cz <= Math.floorDiv(island.maxZ(), 16); cz++) {
                Path file = regions.resolve("r." + Math.floorDiv(cx, 32) + "." + Math.floorDiv(cz, 32) + ".mca");
                RegionFile region = opened.get(file);
                if (region == null && !opened.containsKey(file)) {
                    region = Files.exists(file) ? new RegionFile(file) : null;
                    opened.put(file, region);
                }
                if (region == null) {
                    continue;
                }
                Map<String, Object> chunkNbt = region.readChunk(cx & 31, cz & 31);
                if (chunkNbt == null) {
                    continue;
                }
                Chunk chunk = Chunk.decode(chunkNbt);
                if (chunk.generated()) {
                    copy(chunk, world, shapes, ox, oy, oz);
                }
            }
        }
        String container = worldPath.getFileName().toString().replaceFirst("(?i)\\.zip$", "");
        String name = container + (island.worldLabel().isEmpty() ? "" : " / " + island.worldLabel())
                + " / island " + island.index();
        return new ImportedWorld(name, world, new BlockPoint(ox, oy, oz), island, shapes);
    }

    /**
     * Copies a chunk's blocks into the world at the given offset. Each palette entry is mapped
     * once per section, not once per block, and sections that map to nothing but air are
     * skipped without unpacking. Blocks with a real shape in {@link BlockShapes} have it noted
     * in {@code shapes}.
     */
    private static void copy(Chunk chunk, ArrayBlockView world, ImportedShapes shapes, int ox,
            int oy, int oz) {
        int x0 = chunk.chunkX() * 16 - ox;
        int z0 = chunk.chunkZ() * 16 - oz;
        for (Chunk.Section s : chunk.sections()) {
            int y0 = s.y() * 16 - oy;
            if (y0 + 15 < 0 || y0 >= world.sizeY()) {
                continue;
            }
            List<BlockState> palette = s.palette();
            BlockType[] types = new BlockType[palette.size()];
            BlockShapes.Shape[] real = new BlockShapes.Shape[palette.size()];
            boolean any = false;
            for (int i = 0; i < types.length; i++) {
                real[i] = BlockShapes.builtIn().shape(palette.get(i));
                types[i] = BlockMapping.refine(BlockMapping.map(palette.get(i)),
                        real[i] == null ? null : real[i].boxes());
                any |= types[i] != BlockType.AIR;
            }
            if (!any) {
                continue;
            }
            int[] indices = s.indices();
            for (int i = 0; i < 4096; i++) {
                BlockType type = types[indices[i]];
                if (type == BlockType.AIR) {
                    continue;
                }
                int lx = x0 + (i & 15);
                int ly = y0 + (i >> 8);
                int lz = z0 + ((i >> 4) & 15);
                if (world.inBounds(lx, ly, lz)) {
                    world.set(lx, ly, lz, type);
                    if (real[indices[i]] != null) {
                        shapes.put(lx, ly, lz, type, real[indices[i]]);
                    }
                }
            }
        }
    }

    /**
     * Some maps are closed boxes: a solid layer caps the whole island at its top height (the
     * Dwarven Mines, for example, is solid stone up to y = 255 with the caves carved inside).
     * Players can't get on top of that cap, but a pathfinder happily walks across it.
     *
     * <p>If at least {@code 80%} of the island's columns have a block in the top layer, this
     * fills the air above those columns with solid blocks, so routes stay inside. Skyblock
     * islands open to the sky are left alone.
     *
     * @return whether the roof was sealed
     */
    public static boolean sealRoofIfCapped(ImportedWorld imported) {
        ArrayBlockView w = imported.world();
        int topLayer = imported.island().maxY() - imported.origin().y();
        long columns = 0;
        long capped = 0;
        for (int x = 0; x < w.sizeX(); x++) {
            for (int z = 0; z < w.sizeZ(); z++) {
                boolean any = false;
                for (int y = topLayer; y >= 0 && !any; y--) {
                    any = w.blockAt(x, y, z) != BlockType.AIR;
                }
                if (any) {
                    columns++;
                    if (w.blockAt(x, topLayer, z) != BlockType.AIR) {
                        capped++;
                    }
                }
            }
        }
        if (columns == 0 || capped < 0.8 * columns) {
            return false;
        }
        for (int x = 0; x < w.sizeX(); x++) {
            for (int z = 0; z < w.sizeZ(); z++) {
                if (w.blockAt(x, topLayer, z) != BlockType.AIR) {
                    w.fill(x, topLayer + 1, z, x, w.sizeY() - 1, z, BlockType.SOLID);
                }
            }
        }
        return true;
    }

    private static List<Path> regionFiles(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(RegionFile::isRegionFile).sorted().toList();
        }
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
    }
}
