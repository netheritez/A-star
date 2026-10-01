package astar.client;

import astar.core.BlockPoint;
import astar.movement.exec.GotoPathfinder;
import astar.movement.exec.GridRouter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import astar.pathing.AutoLandmarks;
import astar.pathing.KeptPathfinder;
import java.lang.ref.WeakReference;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * The copy of the world /goto plans on, kept from one trip to the next while they stay inside
 * it, with its pathfinder ({@link KeptPathfinder}): the move graph is built once and patched
 * for the blocks that changed, and landmarks are built once in the background and speed up
 * every later trip. Places visited again and again, like the Mines, plan fastest.
 *
 * <p>A trip that doesn't fit, or one in another world or dimension, makes a new copy, a little
 * bigger than the trip needs so the next ones nearby fit too.
 */
final class LiveMap {

    /** Blocks the copy reaches past what a trip needs, across, when the loaded area is too big. */
    private static final int EXTRA = 32;
    /** Blocks the copy reaches above and below what a trip needs, when it fits. */
    private static final int EXTRA_Y = 24;
    /** The most blocks it reaches above and below, when the size limit leaves room. */
    private static final int MAX_EXTRA_Y = 64;

    private static volatile LiveMap kept;

    /**
     * Blocks around the player a warmed-up copy reaches at least, across and up and down, so
     * trips from here fit in it.
     */
    private static final int WARM_MARGIN = 64;
    private static final int WARM_MARGIN_Y = 24;
    /**
     * The copy of the whole map the player is on ({@link Places#current}), with its move graph
     * and landmarks kept in a file next to the map's saved chunks; null until made.
     */
    private static volatile LiveMap placeMap;
    private static Places.Place placeMapFor;
    /** Whether the whole-map copy for {@link #placeMapFor} can't be made (too big, say). */
    private static volatile boolean placeFailed;
    private static CompletableFuture<LiveMap> placing;
    /** The most blocks a whole-map copy holds (about 200 MB with its graph and landmarks). */
    static final long PLACE_VOLUME = 64_000_000;
    /**
     * What a saved graph depends on besides the blocks: /goto's costs. Change it when they
     * change, so old files are made again.
     */
    private static final String NAV_KEY = "goto 1; 16-way; avoid " + GotoPathfinder.AVOID
            + "; wall " + astar.pathing.TerrainCosts.WALL + "; turn "
            + astar.core.TurnPenalty.DEFAULT_PER_TURN + "; landmarks "
            + AutoLandmarks.DEFAULT_COUNT + "; drop " + GotoPathfinder.DROP_BASE + "+"
            + GotoPathfinder.DROP_PER_BLOCK;
    /** Whether {@link #warmWalks} has run this game. */
    private static boolean walksWarmed;
    /** Whether {@link #warmWhole} has run this game. */
    private static boolean wholeWarmed;
    /** The copy being made ahead of time, or null. */
    private static CompletableFuture<LiveMap> warming;
    private static final ExecutorService WARMER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "astar warm-up");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    /** Weakly, so a world left behind isn't kept in memory. */
    private final WeakReference<Level> level;
    final LiveWorld world;
    final KeptPathfinder finder;
    /** How many trips have planned on this copy, this one included. */
    int trips;
    /** Whether it was made ahead of time ({@link #warmUp}), its graph and landmarks built. */
    boolean warmed;
    /** Whether it's the copy of a whole map ({@link #placeUp}). */
    boolean whole;

    private LiveMap(Level level, LiveWorld world) {
        this.level = new WeakReference<>(level);
        this.world = world;
        this.finder = new KeptPathfinder(GotoPathfinder.create(world.blockView()),
                AutoLandmarks.DEFAULT_COUNT);
    }

    /**
     * The copy a trip plans on: the whole map's or the kept one if it holds the box from
     * {@code needMin} to {@code needMax}, with the box from {@code min} to {@code max} read
     * again where the game has it loaded (only changed blocks touch the pathfinder); else a
     * new copy of the box and every loaded chunk around {@code center} (within {@code view}
     * chunks), so the next trips nearby fit in it too. On the game thread, which only takes a
     * quick snapshot of the loaded chunks: {@link Cover#get} reads them, off it.
     *
     * @throws IllegalArgumentException if even the box alone is too big to copy
     */
    static Cover covering(Level level, BlockPos needMin, BlockPos needMax, BlockPos min,
            BlockPos max, BlockPos center, int view) {
        LiveMap p = placeMap;
        if (p != null && p.level.get() == level && p.world.holds(level, needMin, needMax)) {
            return new Cover(level, p, snapshot(level, min, max), min, max);
        }
        LiveMap m = kept;
        if (m != null && m.level.get() == level && m.world.holds(level, needMin, needMax)) {
            return new Cover(level, m, snapshot(level, min, max), min, max);
        }
        BlockPos[] box = box(min, max, center, view);
        LiveWorld.checkSize(box[0], box[1], level.getMinY(), level.getMaxY(),
                LiveWorld.MAX_VOLUME);
        return new Cover(level, null, snapshot(level, box[0], box[1]), box[0], box[1]);
    }

    private static LiveWorld.Snapshot snapshot(Level level, BlockPos min, BlockPos max) {
        return LiveWorld.Snapshot.of(level, min.getX() >> 4, min.getZ() >> 4, max.getX() >> 4,
                max.getZ() >> 4);
    }

    /**
     * The copy a trip will plan on ({@link #covering}), made ready by {@link #get} off the
     * game thread: a kept copy read again from the snapshot, or a new copy of it.
     */
    static final class Cover {
        private final Level level;
        private final LiveMap existing;
        private final LiveWorld.Snapshot snap;
        private final BlockPos min;
        private final BlockPos max;

        private Cover(Level level, LiveMap existing, LiveWorld.Snapshot snap, BlockPos min,
                BlockPos max) {
            this.level = level;
            this.existing = existing;
            this.snap = snap;
            this.min = min;
            this.max = max;
        }

        /** Whether the copy will hold the box from {@code a} to {@code b}. */
        boolean holds(BlockPos a, BlockPos b) {
            if (existing != null) {
                return existing.world.holds(level, a, b);
            }
            int y0 = Math.max(min.getY(), snap.minY());
            int y1 = Math.min(max.getY(), snap.maxY());
            return a.getX() >= min.getX() && a.getZ() >= min.getZ() && b.getX() <= max.getX()
                    && b.getZ() <= max.getZ() && Math.max(a.getY(), snap.minY()) >= y0
                    && Math.min(b.getY(), snap.maxY()) <= y1;
        }

        /** The copy, read up to date from the snapshot; on any thread. */
        LiveMap get() {
            if (existing != null) {
                synchronized (existing) {
                    existing.world.resync(snap, min, max);
                    existing.trips++;
                }
                return existing;
            }
            LiveMap m = new LiveMap(level, LiveWorld.copy(snap, min, max));
            m.trips = 1;
            kept = m;
            return m;
        }
    }

    /**
     * The box to copy for a trip from {@code min} to {@code max}: everything loaded in view,
     * else a little past the trip, else just the trip; up and down as far as the size limit
     * leaves room for.
     */
    private static BlockPos[] box(BlockPos min, BlockPos max, BlockPos center, int view) {
        int cx = center.getX() >> 4;
        int cz = center.getZ() >> 4;
        int[][] across = {
            {Math.min(min.getX(), (cx - view + 1) << 4), Math.min(min.getZ(), (cz - view + 1) << 4),
                Math.max(max.getX(), ((cx + view) << 4) - 1),
                Math.max(max.getZ(), ((cz + view) << 4) - 1)},
            {min.getX() - EXTRA, min.getZ() - EXTRA, max.getX() + EXTRA, max.getZ() + EXTRA},
            {min.getX(), min.getZ(), max.getX(), max.getZ()}};
        for (int i = 0; i < across.length; i++) {
            int[] b = across[i];
            // Up and down as far as the size limit leaves room for (at most MAX_EXTRA_Y), as
            // routes can dip well under both ends; the first box that leaves EXTRA_Y wins.
            long area = (long) (b[2] - b[0] + 1) * (b[3] - b[1] + 1);
            long room = LiveWorld.MAX_VOLUME / area - (max.getY() - min.getY() + 1);
            int extraY = (int) Math.max(0, Math.min(MAX_EXTRA_Y, room / 2));
            if (extraY >= EXTRA_Y || i == across.length - 1) {
                return new BlockPos[] {new BlockPos(b[0], min.getY() - extraY, b[1]),
                    new BlockPos(b[2], max.getY() + extraY, b[3])};
            }
        }
        throw new AssertionError();
    }

    /**
     * Makes the copy around the player ahead of time, with its move graph and landmarks, so
     * the next /goto plans at once: when there's no copy yet, or the player has gone near its
     * edge or to another world. The chunks are copied quickly here; the rest happens on a
     * background thread. Call now and then on the game thread while no trip is planning.
     */
    static void warmUp(Level level, BlockPos player, int view) {
        placeUp(level, player);
        if (!walksWarmed) {
            warmWalks(level, player, view);
        }
        if (!wholeWarmed) {
            warmWhole(level, player, view);
        }
        LiveMap p = placeMap;
        if (p != null && p.level.get() == level && p.world.holds(level,
                player.offset(-WARM_MARGIN, -WARM_MARGIN_Y, -WARM_MARGIN),
                player.offset(WARM_MARGIN, WARM_MARGIN_Y, WARM_MARGIN))) {
            return; // the whole map is ready
        }
        if (warming != null) {
            if (warming.isDone()) {
                LiveMap m = warming.exceptionally(e -> {
                    System.out.println("[astar] warm-up failed: " + e);
                    return null;
                }).join();
                warming = null;
                if (m != null && m.level.get() == level) {
                    kept = m;
                }
            }
            return;
        }
        BlockPos min = player.offset(-WARM_MARGIN, -WARM_MARGIN_Y, -WARM_MARGIN);
        BlockPos max = player.offset(WARM_MARGIN, WARM_MARGIN_Y, WARM_MARGIN);
        LiveMap k = kept;
        if (k != null && k.level.get() == level && k.world.holds(level, min, max)) {
            return;
        }
        if (LiveWorld.unloadedChunks(level, min, max, player, view) > 0) {
            return; // still loading: next time
        }
        BlockPos[] box = box(min, max, player, view);
        LiveWorld.Snapshot snap = LiveWorld.Snapshot.of(level, box[0].getX() >> 4,
                box[0].getZ() >> 4, box[1].getX() >> 4, box[1].getZ() >> 4);
        warming = CompletableFuture.supplyAsync(() -> {
            LiveMap m = new LiveMap(level, LiveWorld.copy(snap, box[0], box[1]));
            BlockPos o = m.world.origin();
            BlockPoint from = new GridRouter(m.finder.base(), m.world).standingAt(
                    player.getX() + 0.5 - o.getX(), player.getY() - o.getY(),
                    player.getZ() + 0.5 - o.getZ());
            if (from == null) {
                return null; // in the air, say: next time
            }
            m.finder.warm(from.pack());
            m.warmed = true;
            return m;
        }, WARMER);
    }

    /**
     * Once a game, when the chunks around the player have loaded: lays out a few walks on
     * them in the background ({@link WalkWarmUp#nearby}), so the first trip's walk is laid
     * out by code already compiled for blocks like these.
     */
    private static void warmWalks(Level level, BlockPos player, int view) {
        BlockPos min = player.offset(-WalkWarmUp.NEARBY, -WalkWarmUp.NEARBY_Y,
                -WalkWarmUp.NEARBY);
        BlockPos max = player.offset(WalkWarmUp.NEARBY, WalkWarmUp.NEARBY_Y, WalkWarmUp.NEARBY);
        if (LiveWorld.unloadedChunks(level, min, max, player, view) > 0) {
            return; // still loading: next time
        }
        walksWarmed = true;
        WalkWarmUp.nearby(snapshot(level, min, max), min, max, player);
    }

    /**
     * Once a game, when the whole-map copy is ready and the chunks around the player have
     * loaded: plans a few trips on it in the background ({@link WalkWarmUp#whole}), so the
     * first real trip on it runs compiled code.
     */
    private static void warmWhole(Level level, BlockPos player, int view) {
        LiveMap p = placeMap;
        BlockPos min = player.offset(-WalkWarmUp.NEARBY, -WalkWarmUp.NEARBY_Y,
                -WalkWarmUp.NEARBY);
        BlockPos max = player.offset(WalkWarmUp.NEARBY, WalkWarmUp.NEARBY_Y, WalkWarmUp.NEARBY);
        if (p == null || p.level.get() != level || !p.world.holds(level, min, max)
                || LiveWorld.unloadedChunks(level, min, max, player, view) > 0) {
            return; // no copy yet, or still loading: next time
        }
        wholeWarmed = true;
        WalkWarmUp.whole(p, snapshot(level, min, max), min, max, player);
    }

    /**
     * Makes the whole-map copy for the map the player is on, once it's known: reads its graph
     * and landmarks from the map's file if they still fit its blocks, else builds them and
     * writes the file. On a background thread; the game thread only starts it.
     */
    /**
     * Whether the whole-map copy is ready and holds the box from {@code min} to {@code max}:
     * a trip there needn't wait for chunks to load, as what isn't loaded was copied from the
     * saved map.
     */
    static boolean wholeHolds(Level level, BlockPos player, BlockPos min, BlockPos max) {
        placeUp(level, player);
        LiveMap p = placeMap;
        return p != null && p.level.get() == level && p.world.holds(level, min, max);
    }

    /** Where the whole-map copy for the map the player is on stands. */
    enum Whole {
        /** There's none, and none coming (no map known, or it can't be copied). */
        NONE,
        /** It's being made. */
        COMING,
        /** It's ready: trips that fit in it plan on it. */
        READY
    }

    /**
     * The whole-map copy's state for this world, starting it if the map is known and it
     * isn't made yet. On the game thread.
     */
    static Whole whole(Level level, BlockPos player) {
        placeUp(level, player);
        if (placing != null) {
            return Whole.COMING;
        }
        LiveMap p = placeMap;
        return p != null && p.level.get() == level ? Whole.READY : Whole.NONE;
    }

    private static void placeUp(Level level, BlockPos player) {
        if (placing != null) {
            if (placing.isDone()) {
                LiveMap m = placing.exceptionally(e -> {
                    System.out.println("[astar] couldn't make the map's copy: " + e);
                    return null;
                }).join();
                placing = null;
                if (m != null && m.level.get() == level) {
                    placeMap = m;
                }
            }
            return;
        }
        Places.Place place = Places.current();
        if (place == null || place == placeMapFor && (placeFailed || placeMap != null
                && placeMap.level.get() == level)) {
            return;
        }
        placeFailed = false;
        if (place != placeMapFor) {
            placeMap = null;
        }
        placeMapFor = place;
        int minY = level.getMinY();
        int maxY = level.getMaxY();
        placing = CompletableFuture.supplyAsync(() -> placeMap(level, place, player, minY, maxY),
                WARMER);
    }

    private static LiveMap placeMap(Level level, Places.Place place, BlockPos player, int minY,
            int maxY) {
        long t0 = System.nanoTime();
        java.nio.file.Path file = place.dir.resolve("nav.bin");
        // The graph and landmarks are read beside the blocks, on other cores, and checked
        // against the copy once it's made.
        CompletableFuture<astar.pathing.NavCache.Loaded> navRead = CompletableFuture
                .supplyAsync(() -> {
                    if (!java.nio.file.Files.exists(file)) {
                        return null;
                    }
                    try {
                        return astar.pathing.NavCache.load(file, NAV_KEY);
                    } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                });
        // Taken before any chunk is read, so one saved meanwhile is read again next time.
        java.util.Map<Long, Long> stamps = place.stamps();
        String blocksKey = BLOCKS_KEY + "; " + net.minecraft.SharedConstants.getCurrentVersion()
                .name() + "; heights " + minY + " to " + maxY + "; bundled "
                + (place.base == null ? 0 : place.base.stamp);
        java.nio.file.Path blocksFile = place.dir.resolve("blocks.bin");
        long first = SavedView.key(player.getX() >> 4, player.getZ() >> 4);
        Blocks saved = readBlocks(place, blocksFile, blocksKey, stamps, first);
        LiveWorld w;
        java.util.Set<Long> island;
        int[] ys;
        double scanMs = 0;
        if (saved != null) {
            w = saved.world;
            island = saved.island;
            ys = saved.ys;
        } else {
            Island found = island(place, stamps, first);
            island = found.chunks();
            ys = found.ys();
            int[] xz = found.xz();
            if (ys[0] > ys[1]) {
                placeFailed = true;
                return null;
            }
            BlockPos min = new BlockPos(xz[0] << 4, Math.max(minY, ys[0] - 2), xz[1] << 4);
            BlockPos max = new BlockPos((xz[2] << 4) + 15, Math.min(maxY, ys[1] + 3),
                    (xz[3] << 4) + 15);
            scanMs = (System.nanoTime() - t0) / 1e6;
            try {
                w = LiveWorld.copy(new LiveWorld.Snapshot(level, java.util.Map.of(), minY, maxY),
                        min, max, PLACE_VOLUME);
            } catch (IllegalArgumentException e) {
                System.out.println("[astar] " + place.name + " is too big to copy whole: " + e
                        .getMessage());
                placeFailed = true;
                return null;
            }
        }
        LiveMap m = new LiveMap(level, w);
        double copyMs = (System.nanoTime() - t0) / 1e6;
        long t1 = System.nanoTime();
        boolean read = false;
        try {
            astar.pathing.NavCache.Loaded loaded = navRead.join();
            astar.pathing.NavCache.Read r = loaded == null ? null
                    : loaded.restore(m.finder.base());
            if (r != null) {
                m.finder.adopt(r);
                read = true;
            }
        } catch (RuntimeException e) {
            Throwable cause = e instanceof java.util.concurrent.CompletionException
                    && e.getCause() != null ? e.getCause() : e;
            if (cause instanceof java.io.UncheckedIOException u) {
                cause = u.getCause();
            }
            System.out.println("[astar] couldn't read " + file + ": " + cause);
        }
        if (!read) {
            BlockPos o = w.origin();
            BlockPoint from = new GridRouter(m.finder.base(), w).standingAt(
                    player.getX() + 0.5 - o.getX(), player.getY() - o.getY(),
                    player.getZ() + 0.5 - o.getZ());
            if (from == null) {
                placeMapFor = null; // try again from where the player stands next time
                return null;
            }
            m.finder.warm(from.pack());
            try {
                java.nio.file.Files.createDirectories(place.dir);
                java.nio.file.Path tmp = place.dir.resolve("nav.bin.tmp");
                try (java.io.DataOutputStream out = new java.io.DataOutputStream(
                        new java.io.BufferedOutputStream(
                                java.nio.file.Files.newOutputStream(tmp), 1 << 16))) {
                    astar.pathing.NavCache.write(out, w.blockView(), NAV_KEY,
                            m.finder.base().graph(), m.finder.landmarks());
                }
                java.nio.file.Files.move(tmp, file,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.io.IOException e) {
                System.out.println("[astar] couldn't save " + file + ": " + e);
            }
        }
        m.warmed = true;
        m.whole = true;
        Warps.keepIn(place.dir, w.blockView());
        double navMs = (System.nanoTime() - t1) / 1e6;
        long t2 = System.nanoTime();
        if (saved == null || saved.reread > 0) {
            writeBlocks(place, blocksFile, blocksKey, stamps, island, ys, w);
        }
        System.out.printf("[astar] %s: whole map ready (%d x %d x %d blocks; %s in %.0f ms"
                + " (%.0f finding its chunks), graph and landmarks %s; blocks saved in"
                + " %.0f ms)%n", place.name,
                w.size().getX(), w.size().getY(), w.size().getZ(), saved == null ? "copied"
                : "read from " + blocksFile.getFileName() + " (" + saved.reread
                + " chunks read again)", copyMs, scanMs, read
                ? String.format("read from %s beside the blocks, ready %.0f ms after them",
                        file.getFileName(), navMs)
                : String.format("built and saved in %.0f ms", navMs),
                (System.nanoTime() - t2) / 1e6);
        return m;
    }

    /**
     * What a saved whole-map copy ({@code blocks.bin}) depends on besides the saved chunks and
     * the game. Change it when the copy's format or what it reads changes.
     */
    private static final String BLOCKS_KEY = "blocks 1";
    private static final int BLOCKS_MAGIC = 0x41426c6b;

    /** A whole-map copy read from its file, the chunks it spans, and how many were read again. */
    private record Blocks(LiveWorld world, java.util.Set<Long> island, int[] ys, int reread) {}

    /**
     * The chunks of a saved map joined to the player's, side by side (a saved map may hold
     * other islands), and where they can be stood on: {@code ys} the lowest and highest
     * heights with room above a block, {@code xz} the lowest and highest chunk x and z.
     */
    record Island(java.util.Set<Long> chunks, int[] ys, int[] xz) {}

    /**
     * The island of saved chunks around {@code first} ({@link Island}). Spreads out from it,
     * reading only the chunks it reaches (a saved map may hold many more), on all cores: each
     * chunk read looks at its heights and passes its sides on as it goes, so no core waits
     * for the others to finish a ring.
     */
    static Island island(Places.Place place, java.util.Map<Long, Long> stamps, long first) {
        java.util.Set<Long> island = java.util.concurrent.ConcurrentHashMap.newKeySet();
        java.util.Set<Long> seen = java.util.concurrent.ConcurrentHashMap.newKeySet();
        int[] ys = {Integer.MAX_VALUE, Integer.MIN_VALUE};
        int[] xz = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        final class Spread extends java.util.concurrent.CountedCompleter<Void> {
            private final long k;

            Spread(Spread parent, long k) {
                super(parent);
                this.k = k;
            }

            @Override
            public void compute() {
                SavedChunk c = stamps.containsKey(k) ? place.chunk((int) (k >> 32), (int) k)
                        : null;
                if (c != null && !c.empty()) {
                    island.add(k);
                    int[] at = {Integer.MAX_VALUE, Integer.MIN_VALUE};
                    c.standingHeights(at);
                    int cx = (int) (k >> 32);
                    int cz = (int) k;
                    synchronized (ys) {
                        ys[0] = Math.min(ys[0], at[0]);
                        ys[1] = Math.max(ys[1], at[1]);
                        xz[0] = Math.min(xz[0], cx);
                        xz[1] = Math.min(xz[1], cz);
                        xz[2] = Math.max(xz[2], cx);
                        xz[3] = Math.max(xz[3], cz);
                    }
                    for (long n : sides(k)) {
                        if (seen.add(n)) {
                            addToPendingCount(1);
                            new Spread(this, n).fork();
                        }
                    }
                }
                tryComplete();
            }
        }
        seen.add(first);
        new Spread(null, first).invoke();
        synchronized (ys) {
            return new Island(new java.util.HashSet<>(island), ys, xz);
        }
    }

    private static long[] sides(long k) {
        int cx = (int) (k >> 32);
        int cz = (int) k;
        return new long[] {SavedView.key(cx + 1, cz), SavedView.key(cx - 1, cz),
            SavedView.key(cx, cz + 1), SavedView.key(cx, cz - 1)};
    }

    /**
     * The whole-map copy as last saved, with the chunks saved since read again; null if there's
     * none, or the map changed more than that (new chunks, or the player on another island).
     */
    private static Blocks readBlocks(Places.Place place, java.nio.file.Path file, String key,
            java.util.Map<Long, Long> stamps, long first) {
        if (!java.nio.file.Files.exists(file)) {
            return null;
        }
        try {
            // Read in one go: the blocks are laid out straight from the file's bytes.
            byte[] data;
            try (java.io.InputStream f = java.nio.file.Files.newInputStream(file)) {
                data = f.readAllBytes();
            }
            java.io.ByteArrayInputStream bytes = new java.io.ByteArrayInputStream(data);
            java.io.DataInputStream in = new java.io.DataInputStream(bytes);
            if (in.readInt() != BLOCKS_MAGIC || !in.readUTF().equals(key)) {
                return null;
            }
            int[] ys = {in.readInt(), in.readInt()};
            int n = in.readInt();
            java.util.Map<Long, Long> was = new java.util.HashMap<>();
            java.util.Set<Long> island = new java.util.HashSet<>();
            for (int i = 0; i < n; i++) {
                long k = in.readLong();
                was.put(k, in.readLong());
                if (in.readBoolean()) {
                    island.add(k);
                }
            }
            if (!was.keySet().equals(stamps.keySet()) || !island.contains(first)) {
                return null;
            }
            java.util.List<SavedChunk> changed = new java.util.ArrayList<>();
            for (var e : stamps.entrySet()) {
                long k = e.getKey();
                if (e.getValue() != -1 && e.getValue().equals(was.get(k))) {
                    continue;
                }
                SavedChunk c = place.chunk((int) (k >> 32), (int) k);
                boolean solid = c != null && !c.empty();
                if (island.contains(k)) {
                    int[] at = {Integer.MAX_VALUE, Integer.MIN_VALUE};
                    if (!solid) {
                        return null;
                    }
                    c.standingHeights(at);
                    if (at[0] <= at[1] && (at[0] < ys[0] || at[1] > ys[1])) {
                        return null; // higher or lower than the copy reaches
                    }
                    changed.add(c);
                } else if (solid) {
                    for (long s : sides(k)) {
                        if (island.contains(s)) {
                            return null; // joined to the map now
                        }
                    }
                }
            }
            LiveWorld w = LiveWorld.read(data, data.length - bytes.available());
            if (!changed.isEmpty()) {
                w.reread(changed);
            }
            return new Blocks(w, island, ys, changed.size());
        } catch (java.io.IOException | RuntimeException e) {
            System.out.println("[astar] couldn't read " + file + ": " + e);
            return null;
        }
    }

    private static void writeBlocks(Places.Place place, java.nio.file.Path file, String key,
            java.util.Map<Long, Long> stamps, java.util.Set<Long> island, int[] ys, LiveWorld w) {
        try {
            java.nio.file.Files.createDirectories(place.dir);
            java.nio.file.Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (java.io.DataOutputStream out = new java.io.DataOutputStream(
                    new java.io.BufferedOutputStream(java.nio.file.Files.newOutputStream(tmp),
                            1 << 16))) {
                out.writeInt(BLOCKS_MAGIC);
                out.writeUTF(key);
                out.writeInt(ys[0]);
                out.writeInt(ys[1]);
                out.writeInt(stamps.size());
                for (var e : stamps.entrySet()) {
                    out.writeLong(e.getKey());
                    out.writeLong(e.getValue());
                    out.writeBoolean(island.contains(e.getKey()));
                }
                w.write(out);
            }
            java.nio.file.Files.move(tmp, file,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.io.IOException e) {
            System.out.println("[astar] couldn't save " + file + ": " + e);
        }
    }

    /** Drops the kept copy (leaving the world, say), freeing its memory. */
    static void forget() {
        kept = null;
        warming = null;
        placeMap = null;
        placeMapFor = null;
        placing = null;
    }
}
