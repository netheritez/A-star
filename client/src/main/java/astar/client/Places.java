package astar.client;

import astar.mcworld.Chunk;
import astar.mcworld.RegionFile;
import astar.mcworld.WorldImporter;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * The maps {@code /goto} knows beyond what the game has loaded, so it can plan past the render
 * distance: chunks saved while exploring ({@code /goto cache}, on by default) and the Dwarven
 * Mines map that comes with the mod.
 *
 * <p>A server can put different maps at the same coordinates (Hypixel's islands all live in
 * one dimension), so saved chunks are kept per <i>place</i>, and which place the player is in
 * is worked out from the chunks the game loads: the first place whose saved chunks match what
 * the game sends (at least {@link #MATCH} of their blocks, over {@link #MATCHES_NEEDED}
 * chunks) is it. When none does, a new place starts. Chunks seen are saved when they load and
 * again when they unload (so changes are kept), under
 * {@code .minecraft/astar/places/<server>/<dimension>/<place>/}.
 *
 * <p>The game thread only copies each chunk's blocks (quick); matching places, saving and
 * writing files happen on a thread of their own, so walking into new chunks doesn't stall the
 * game (it did, and the executor's predictions then missed).
 */
final class Places {
    /** How alike a loaded chunk and a saved one must be to count as the same place. */
    static final double MATCH = 0.75;
    /** How many chunks must match before settling on a place. */
    static final int MATCHES_NEEDED = 4;
    /** Chunks loaded with no place matching before starting a new one. */
    static final int NEW_AFTER = 30;
    private static final String BUNDLED = "dwarven-mines";
    private static final String BUNDLED_RESOURCE = "/astar/maps/dwarven-mines.zip";

    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "astar chunk cache");
        t.setDaemon(true);
        return t;
    });

    private static volatile boolean recording = true;
    private static volatile boolean settingsRead;
    private static volatile ClientLevel level;
    private static List<Place> candidates = List.of();
    private static volatile Place current;
    private static final List<SavedChunk> pending = new ArrayList<>();
    private static final Map<Place, double[]> scores = new HashMap<>();
    private static BundledMap bundled;

    private Places() {}

    /** A map: saved chunks, and underneath them, those of a map that came with the mod. */
    static final class Place {
        final String name;
        final Path dir;
        final BundledMap base;
        private final Map<Long, Optional<SavedChunk>> chunks = new ConcurrentHashMap<>();

        Place(String name, Path dir, BundledMap base) {
            this.name = name;
            this.dir = dir;
            this.base = base;
        }

        /** The saved chunk, or null if this place has none there. */
        SavedChunk chunk(int cx, int cz) {
            // Read outside the map's lock, so many chunks can be read at once; one read twice
            // meanwhile is kept once.
            long k = key(cx, cz);
            Optional<SavedChunk> c = chunks.get(k);
            if (c == null) {
                Optional<SavedChunk> read = Optional.ofNullable(load(cx, cz));
                c = chunks.putIfAbsent(k, read);
                c = c == null ? read : c;
            }
            return c.orElse(null);
        }

        private SavedChunk load(int cx, int cz) {
            Path file = dir.resolve("c." + cx + "." + cz + ".gz");
            if (Files.exists(file)) {
                try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                        new GZIPInputStream(Files.newInputStream(file))))) {
                    return SavedChunk.read(in);
                } catch (IOException | RuntimeException e) {
                    System.out.println("[astar] couldn't read " + file + ": " + e);
                }
            }
            return base == null ? null : base.chunk(cx, cz);
        }

        /** Keeps a chunk as seen now, and writes it, unless it's what was saved already. */
        void put(SavedChunk c) {
            SavedChunk old = chunk(c.cx, c.cz);
            if (old != null && old.likeness(c) == 1) {
                return;
            }
            chunks.put(key(c.cx, c.cz), Optional.of(c));
            Path file = dir.resolve("c." + c.cx + "." + c.cz + ".gz");
            WRITER.execute(() -> {
                try {
                    Files.createDirectories(dir);
                    Path tmp = dir.resolve(file.getFileName() + ".tmp");
                    try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                            new GZIPOutputStream(Files.newOutputStream(tmp))))) {
                        c.write(out);
                    }
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    System.out.println("[astar] couldn't save " + file + ": " + e);
                }
            });
        }

        /** How many chunks are saved on disk for it (not counting a bundled map's). */
        /** Every chunk this place has (x, z), saved on disk, seen now, or in its bundled map. */
        java.util.Set<Long> chunkKeys() {
            java.util.Set<Long> keys = new java.util.HashSet<>();
            if (base != null) {
                keys.addAll(base.chunkKeys());
            }
            if (Files.isDirectory(dir)) {
                try (Stream<Path> files = Files.list(dir)) {
                    files.map(p -> p.getFileName().toString())
                            .filter(n -> n.startsWith("c.") && n.endsWith(".gz"))
                            .forEach(n -> {
                                String[] xz = n.substring(2, n.length() - 3).split("\\.");
                                keys.add(key(Integer.parseInt(xz[0]), Integer.parseInt(xz[1])));
                            });
                } catch (IOException | RuntimeException e) {
                    System.out.println("[astar] couldn't list " + dir + ": " + e);
                }
            }
            chunks.forEach((k, c) -> {
                if (c.isPresent()) {
                    keys.add(k);
                }
            });
            return keys;
        }

        /**
         * Every chunk this place has ({@link #chunkKeys}), with a stamp that changes when its
         * saved file does: the file's size and time, or 0 for one only in the bundled map.
         */
        java.util.Map<Long, Long> stamps() {
            java.util.Map<Long, Long> stamps = new java.util.HashMap<>();
            for (long k : chunkKeys()) {
                stamps.put(k, 0L);
            }
            if (Files.isDirectory(dir)) {
                try (Stream<Path> files = Files.list(dir)) {
                    for (Path f : (Iterable<Path>) files::iterator) {
                        String n = f.getFileName().toString();
                        if (!n.startsWith("c.") || !n.endsWith(".gz")) {
                            continue;
                        }
                        String[] xz = n.substring(2, n.length() - 3).split("\\.");
                        long stamp;
                        try {
                            stamp = Files.size(f) * 31 + Files.getLastModifiedTime(f).toMillis();
                        } catch (IOException e) {
                            stamp = -1; // being written: read again
                        }
                        stamps.put(key(Integer.parseInt(xz[0]), Integer.parseInt(xz[1])), stamp);
                    }
                } catch (IOException | RuntimeException e) {
                    System.out.println("[astar] couldn't list " + dir + ": " + e);
                }
            }
            return stamps;
        }

        long savedCount() {
            if (!Files.isDirectory(dir)) {
                return 0;
            }
            try (Stream<Path> files = Files.list(dir)) {
                return files.filter(p -> p.getFileName().toString().endsWith(".gz")).count();
            } catch (IOException e) {
                return 0;
            }
        }
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    /** Whether chunks seen are saved ({@code /goto cache on}). */
    static boolean recording() {
        readSettings();
        return recording;
    }

    static void setRecording(boolean on) {
        readSettings();
        recording = on;
        Properties p = new Properties();
        p.setProperty("record", Boolean.toString(on));
        try {
            Files.createDirectories(root());
            try (var out = Files.newOutputStream(root().resolve("settings.properties"))) {
                p.store(out, "astar /goto chunk cache");
            }
        } catch (IOException e) {
            System.out.println("[astar] couldn't save the cache setting: " + e);
        }
    }

    private static void readSettings() {
        if (settingsRead) {
            return;
        }
        settingsRead = true;
        Path file = root().resolve("settings.properties");
        if (Files.exists(file)) {
            Properties p = new Properties();
            try (var in = Files.newInputStream(file)) {
                p.load(in);
                recording = Boolean.parseBoolean(p.getProperty("record", "true"));
            } catch (IOException e) {
                // keep the default
            }
        }
    }

    private static Path root() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("astar").resolve("places");
    }

    /** The place the player is in, or null while it isn't known yet. */
    static Place current() {
        return current;
    }

    /** What's known of a chunk the game hasn't loaded: from the current place, or null. */
    static SavedChunk saved(Level l, int cx, int cz) {
        return current == null || l != level ? null : current.chunk(cx, cz);
    }

    /** A chunk the game just loaded: copied now, looked at on the places thread. */
    static void loaded(ClientLevel l, LevelChunk chunk) {
        if (l != level) {
            level = l;
            current = null;
            Path dir = dimensionDir(l);
            boolean overworld = l.dimension() == Level.OVERWORLD;
            recording();
            WRITER.execute(() -> enter(dir, overworld));
        }
        SavedChunk.Copied copy = SavedChunk.copy(chunk);
        WRITER.execute(() -> loaded(l, copy.build()));
    }

    private static void loaded(ClientLevel l, SavedChunk c) {
        if (l != level) {
            return;
        }
        if (current != null) {
            if (recording()) {
                current.put(c);
            }
            return;
        }
        pending.add(c);
        if (pending.size() > 4 * NEW_AFTER) {
            pending.remove(0); // not saving, and no place matches: keep only the latest
        }
        for (Place p : candidates) {
            SavedChunk known = p.chunk(c.cx, c.cz);
            if (known == null) {
                continue;
            }
            double[] s = scores.computeIfAbsent(p, k -> new double[2]);
            s[0] += known.likeness(c);
            s[1]++;
            if (s[1] >= MATCHES_NEEDED && s[0] / s[1] >= MATCH) {
                settle(p);
                return;
            }
        }
        if (pending.size() >= NEW_AFTER && recording()) {
            settle(newPlace());
        }
    }

    /** A chunk the game is about to unload: saved as it is now, with any changes. */
    static void unloading(ClientLevel l, LevelChunk chunk) {
        if (l == level && current != null && recording()) {
            SavedChunk.Copied copy = SavedChunk.copy(chunk);
            Place p = current;
            WRITER.execute(() -> p.put(copy.build()));
        }
    }

    private static void settle(Place p) {
        current = p;
        System.out.println("[astar] this is " + p.name + " (" + p.dir + ")");
        if (recording()) {
            for (SavedChunk c : pending) {
                p.put(c);
            }
        }
        pending.clear();
        scores.clear();
    }

    /** A new level (joined a server, changed dimension or island): which place is it? */
    private static void enter(Path dir, boolean overworld) {
        current = null;
        pending.clear();
        scores.clear();
        List<Place> found = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> places = Files.list(dir)) {
                places.filter(Files::isDirectory).sorted().forEach(p -> found.add(
                        new Place(p.getFileName().toString(), p,
                                p.getFileName().toString().equals(BUNDLED) ? bundled() : null)));
            } catch (IOException e) {
                System.out.println("[astar] couldn't list " + dir + ": " + e);
            }
        }
        if (found.stream().noneMatch(p -> p.name.equals(BUNDLED))
                && overworld && bundled() != null) {
            found.add(new Place(BUNDLED, dir.resolve(BUNDLED), bundled()));
        }
        candidates = found;
        placesDir = dir;
    }

    private static Path placesDir;

    private static Place newPlace() {
        Path dir = placesDir;
        int n = 1;
        while (Files.exists(dir.resolve("place-" + n))) {
            n++;
        }
        for (Place p : candidates) {
            if (p.name.equals("place-" + n)) {
                n++;
            }
        }
        Place p = new Place("place-" + n, dir.resolve("place-" + n), null);
        candidates = new ArrayList<>(candidates);
        candidates.add(p);
        return p;
    }

    /**
     * Says which map this is, by name (from a mod or script that knows, say), instead of
     * telling by its blocks: its saved chunks, and its whole-map file, are used from now on.
     * {@value #BUNDLED} is the Mines map that comes with the mod.
     */
    static String choose(String name) {
        if (!name.matches("[A-Za-z0-9_.-]{1,64}") || name.startsWith(".")) {
            return "A map's name is letters, digits, _, - and . (up to 64).";
        }
        if (level == null) {
            return "Join a world first.";
        }
        ClientLevel l = level;
        WRITER.execute(() -> {
            if (l != level || placesDir == null) {
                return;
            }
            Place p = candidates.stream().filter(c -> c.name.equals(name)).findFirst()
                    .orElse(null);
            if (p == null) {
                p = new Place(name, placesDir.resolve(name),
                        name.equals(BUNDLED) ? bundled() : null);
                candidates = new ArrayList<>(candidates);
                candidates.add(p);
            }
            settle(p);
        });
        return "This is " + name + " from now on" + (recording() ? "; chunks you see are saved"
                + " to it." : ".");
    }

    /** Forgets the chunks saved for the current place (the bundled map stays). */
    static String forget() {
        if (current == null) {
            return "Not sure which place this is yet, so nothing was forgotten.";
        }
        Place p = current;
        Place fresh = new Place(p.name, p.dir, p.base);
        current = fresh;
        WRITER.execute(() -> {
            if (Files.isDirectory(p.dir)) {
                try (Stream<Path> files = Files.list(p.dir)) {
                    for (Path f : files.toList()) {
                        Files.deleteIfExists(f);
                    }
                    Files.deleteIfExists(p.dir);
                } catch (IOException e) {
                    System.out.println("[astar] couldn't delete " + p.dir + ": " + e);
                }
            }
            candidates = new ArrayList<>(candidates);
            candidates.replaceAll(c -> c == p ? fresh : c);
        });
        return "Forgot the chunks saved for " + p.name + ".";
    }

    /** For {@code /goto cache}: what it's doing. */
    static String describe() {
        String rec = recording() ? "Saving chunks as you explore (/goto cache off stops it)."
                : "Not saving chunks (/goto cache on saves them).";
        if (current == null) {
            return rec + " Not sure which place this is yet.";
        }
        return rec + " This is " + current.name + ": " + current.savedCount() + " chunks saved"
                + (current.base != null ? ", on top of the Dwarven Mines map" : "") + ".";
    }

    private static Path dimensionDir(ClientLevel l) {
        Minecraft mc = Minecraft.getInstance();
        ServerData server = mc.getCurrentServer();
        String name = server != null ? server.ip
                : mc.getSingleplayerServer() != null
                        ? "singleplayer-" + mc.getSingleplayerServer().getWorldData().getLevelName()
                        : "unknown";
        return root().resolve(clean(name)).resolve(clean(l.dimension().identifier().toString()));
    }

    private static String clean(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9._-]", "_");
    }

    private static BundledMap bundled() {
        if (bundled == null) {
            try {
                bundled = BundledMap.open(root().resolveSibling("maps"), BUNDLED_RESOURCE);
            } catch (IOException | UncheckedIOException e) {
                System.out.println("[astar] no bundled map: " + e);
            }
        }
        return bundled;
    }

    /** A map shipped in the mod: a world save's region files, read a chunk at a time. */
    static final class BundledMap {
        private final Path regions;
        /** Changes when the map does (its zip's size and time). */
        final long stamp;
        /** The region files opened, by path; each is opened once, by whoever needs it first. */
        private final Map<Path, Optional<RegionFile>> files = new ConcurrentHashMap<>(64);
        private final Map<Long, Optional<SavedChunk>> chunks = new ConcurrentHashMap<>();

        private BundledMap(Path regions, long stamp) {
            this.regions = regions;
            this.stamp = stamp;
        }

        /** Copies the zip out of the mod once, then reads it in place. */
        static BundledMap open(Path dir, String resource) throws IOException {
            Path zip = dir.resolve(resource.substring(resource.lastIndexOf('/') + 1));
            if (!Files.exists(zip)) {
                try (InputStream in = Places.class.getResourceAsStream(resource)) {
                    if (in == null) {
                        return null;
                    }
                    Files.createDirectories(dir);
                    Path tmp = zip.resolveSibling(zip.getFileName() + ".tmp");
                    Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                    Files.move(tmp, zip, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            FileSystem fs = FileSystems.newFileSystem(zip);
            return new BundledMap(WorldImporter.regionDir(fs.getRootDirectories().iterator()
                    .next()), Files.size(zip) * 31 + Files.getLastModifiedTime(zip).toMillis());
        }

        SavedChunk chunk(int cx, int cz) {
            long k = key(cx, cz);
            Optional<SavedChunk> c = chunks.get(k);
            if (c == null) {
                Optional<SavedChunk> read = Optional.ofNullable(read(cx, cz));
                c = chunks.putIfAbsent(k, read);
                c = c == null ? read : c;
            }
            return c.orElse(null);
        }

        /** Every chunk in the map's region files, opening them all at once. */
        java.util.Set<Long> chunkKeys() {
            java.util.Set<Long> keys = new java.util.HashSet<>();
            List<Path> list;
            try (Stream<Path> paths = Files.list(regions)) {
                list = paths.filter(RegionFile::isRegionFile).toList();
            } catch (IOException e) {
                System.out.println("[astar] couldn't list the bundled map: " + e);
                return keys;
            }
            for (RegionFile region : list.parallelStream().map(this::open).toList()) {
                if (region == null) {
                    continue;
                }
                for (int x = 0; x < 32; x++) {
                    for (int z = 0; z < 32; z++) {
                        if (region.hasChunk(x, z)) {
                            keys.add(key(region.regionX() * 32 + x, region.regionZ() * 32 + z));
                        }
                    }
                }
            }
            return keys;
        }

        /** The region file holding a chunk, opened once; null if there's none. */
        private RegionFile region(int cx, int cz) {
            return open(regions.resolve("r." + Math.floorDiv(cx, 32) + "."
                    + Math.floorDiv(cz, 32) + ".mca"));
        }

        /**
         * A region file, opened once (read whole out of the zip); null if there's none. Others
         * wanting the same file meanwhile wait for it, others can be opened at the same time.
         */
        private RegionFile open(Path file) {
            return files.computeIfAbsent(file, f -> {
                try {
                    return Files.exists(f) ? Optional.of(new RegionFile(f)) : Optional.empty();
                } catch (IOException e) {
                    return Optional.empty();
                }
            }).orElse(null);
        }

        /** Reads a chunk; on any thread, as a region file is only read once it's open. */
        private SavedChunk read(int cx, int cz) {
            RegionFile region = region(cx, cz);
            if (region == null) {
                return null;
            }
            try {
                Map<String, Object> nbt = region.readChunk(cx & 31, cz & 31);
                if (nbt == null) {
                    return null;
                }
                Chunk chunk = Chunk.decode(nbt);
                if (!chunk.generated() || chunk.sections().isEmpty()) {
                    return null;
                }
                return convert(chunk);
            } catch (IOException | RuntimeException e) {
                System.out.println("[astar] couldn't read chunk " + cx + ", " + cz
                        + " of the bundled map: " + e);
                return null;
            }
        }

        private static SavedChunk convert(Chunk chunk) {
            int lo = Integer.MAX_VALUE;
            int hi = Integer.MIN_VALUE;
            for (Chunk.Section s : chunk.sections()) {
                lo = Math.min(lo, s.y());
                hi = Math.max(hi, s.y());
            }
            int[][] palettes = new int[hi - lo + 1][];
            Object[] indices = new Object[hi - lo + 1];
            SavedChunk.Builder b = new SavedChunk.Builder();
            int[] entries = new int[4096];
            for (Chunk.Section s : chunk.sections()) {
                int[] ids = new int[s.palette().size()];
                boolean solid = false;
                for (int k = 0; k < ids.length; k++) {
                    astar.mcworld.BlockState st = s.palette().get(k);
                    ids[k] = st.isAir() ? 0 : SavedChunk.stateId(text(st));
                    solid |= ids[k] != 0;
                }
                if (!solid) {
                    continue;
                }
                b.start();
                s.indices(entries);
                b.putAll(entries, ids);
                palettes[s.y() - lo] = b.palette();
                indices[s.y() - lo] = b.indices();
            }
            return SavedChunk.of(chunk.chunkX(), chunk.chunkZ(), lo, palettes, indices);
        }

        private static String text(astar.mcworld.BlockState st) {
            if (st.properties().isEmpty()) {
                return st.name();
            }
            StringBuilder sb = new StringBuilder(st.name()).append('[');
            st.properties().forEach((k, v) -> sb.append(k).append('=').append(v).append(','));
            sb.setCharAt(sb.length() - 1, ']');
            return sb.toString();
        }
    }
}
