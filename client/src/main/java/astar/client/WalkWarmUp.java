package astar.client;

import astar.core.BlockPoint;
import astar.core.SearchResult;
import astar.movement.exec.GotoPathfinder;
import astar.movement.exec.GridRouter;
import astar.movement.exec.Journey;
import astar.movement.sim.Aabb;
import astar.movement.sim.SimBlock;
import astar.movement.sim.SimWorld;
import astar.pathing.ArrayBlockView;
import astar.pathing.BlockType;
import astar.pathing.WorldPathfinder;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.core.BlockPos;

/**
 * Lays out walks ahead of the first /goto, on a background thread, so its walk is laid out by
 * code the JVM has already compiled rather than interpreted: the layout is several times
 * slower the first few times it runs. First on a small made-up course when the game starts,
 * then on the blocks around the player once a world is loaded ({@link #nearby}): the made-up
 * course leaves out stairs, fences, ladders and floors over floors, and the code the JVM
 * compiled for it is thrown away and compiled again when a real trip meets them (a first trip
 * laid out in about twice the time of later ones).
 */
final class WalkWarmUp {

    private static final int SIZE = 64;
    private static final int HEIGHT = 8;
    private static final int ROUTES = 40;
    /** How far from the player, across, the walks around it go. */
    static final int NEARBY = 64;
    /** And up and down. */
    static final int NEARBY_Y = 12;
    /** How many walks around the player are laid out: enough to compile what a trip meets. */
    private static final int NEARBY_WALKS = 8;
    /** How many trips on the whole-map copy are planned ahead of the first real one. */
    private static final int WHOLE_TRIPS = 5;
    /** Set when a trip starts planning: the walks around the player then stop. */
    private static volatile boolean stop;

    private WalkWarmUp() {}

    /** Starts it on a thread of its own; returns at once. */
    static void start() {
        Thread t = new Thread(WalkWarmUp::run, "astar walk warm-up");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    static void run() {
        try {
            long t0 = System.nanoTime();
            // A floor with pillars, one-block steps and slabs to walk round, up and over.
            Random random = new Random(7);
            byte[] kinds = new byte[SIZE * HEIGHT * SIZE];
            SimBlock[] blocks = new SimBlock[kinds.length];
            SimBlock solid = SimBlock.solid(new Aabb(0, 0, 0, 1, 1, 1));
            SimBlock slab = SimBlock.solid(new Aabb(0, 0, 0, 1, 0.5, 1));
            for (int z = 0; z < SIZE; z++) {
                for (int x = 0; x < SIZE; x++) {
                    set(kinds, blocks, x, 0, z, BlockType.SOLID, solid);
                    double r = random.nextDouble();
                    if (r < 0.06) {
                        for (int y = 1; y < 4; y++) {
                            set(kinds, blocks, x, y, z, BlockType.SOLID, solid);
                        }
                    } else if (r < 0.10) {
                        set(kinds, blocks, x, 1, z, BlockType.SOLID, solid);
                    } else if (r < 0.14) {
                        set(kinds, blocks, x, 1, z, BlockType.PARTIAL_8, slab);
                    }
                }
            }
            ArrayBlockView view = ArrayBlockView.of(SIZE, HEIGHT, SIZE, kinds);
            SimWorld world = (x, y, z) -> x < 0 || y < 0 || z < 0 || x >= SIZE || y >= HEIGHT
                    || z >= SIZE || blocks[(y * SIZE + z) * SIZE + x] == null ? SimBlock.AIR
                    : blocks[(y * SIZE + z) * SIZE + x];
            WorldPathfinder finder = GotoPathfinder.create(view);
            GridRouter router = new GridRouter(finder, world);
            int walks = 0;
            for (int i = 0; i < ROUTES * 4 && walks < ROUTES; i++) {
                BlockPoint a = new BlockPoint(1 + random.nextInt(SIZE / 4), 1,
                        1 + random.nextInt(SIZE / 4));
                BlockPoint b = new BlockPoint(SIZE - 2 - random.nextInt(SIZE / 4), 1,
                        SIZE - 2 - random.nextInt(SIZE / 4));
                if (!finder.canStand(a) || !finder.canStand(b)) {
                    continue;
                }
                SearchResult r = finder.find(a, b);
                if (r.found()) {
                    Journey.prepare(router, r.path(), Journey.Settings.DEFAULT, 1);
                    walks++;
                }
            }
            System.out.printf("[astar] walk layout warmed up on a made-up course (%d walks in"
                    + " %.0f ms)%n", walks, (System.nanoTime() - t0) / 1e6);
        } catch (RuntimeException e) {
            System.out.println("[astar] walk warm-up failed: " + e);
        }
    }

    /**
     * Lays out a few short walks on a copy of the blocks around the player, from the chunks
     * the game had loaded ({@code snap}), on a thread of its own; returns at once. The copy is
     * its own, so no trip's copy is touched. Stops when a trip starts planning ({@link
     * #tripPlanning}).
     */
    static void nearby(LiveWorld.Snapshot snap, BlockPos min, BlockPos max, BlockPos player) {
        Thread t = new Thread(() -> nearbyWalks(snap, min, max, player), "astar walk warm-up");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /** A trip is planning: the walk warm-up around the player needn't go on. */
    static void tripPlanning() {
        stop = true;
    }

    private static void nearbyWalks(LiveWorld.Snapshot snap, BlockPos min, BlockPos max,
            BlockPos player) {
        try {
            long t0 = System.nanoTime();
            LiveWorld world = LiveWorld.copy(snap, min, max);
            BlockPos o = world.origin();
            BlockPos size = world.size();
            WorldPathfinder finder = GotoPathfinder.create(world.blockView());
            GridRouter router = new GridRouter(finder, world);
            BlockPoint from = router.standingAt(player.getX() + 0.5 - o.getX(),
                    player.getY() - o.getY(), player.getZ() + 0.5 - o.getZ());
            // Walks between places to stand 30 to 60 blocks apart, each from where the last
            // one ended.
            List<BlockPoint> places = new ArrayList<>();
            for (int y = 0; y < size.getY(); y++) {
                for (int z = 0; z < size.getZ(); z++) {
                    for (int x = 0; x < size.getX(); x++) {
                        BlockPoint p = new BlockPoint(x, y, z);
                        if (finder.canStand(p)) {
                            places.add(p);
                        }
                    }
                }
            }
            Random random = new Random(7);
            int walks = 0;
            for (int i = 0; i < NEARBY_WALKS * 4 && walks < NEARBY_WALKS && from != null
                    && !places.isEmpty() && !stop; i++) {
                BlockPoint to = null;
                for (int tries = 0; tries < 50 && to == null; tries++) {
                    BlockPoint p = places.get(random.nextInt(places.size()));
                    int d = Math.abs(p.x() - from.x()) + Math.abs(p.z() - from.z());
                    if (d >= 30 && d <= 60) {
                        to = p;
                    }
                }
                if (to == null) {
                    continue;
                }
                SearchResult r = finder.find(from, to);
                if (r.found() && r.path().size() > 1 && !stop) {
                    Journey.prepare(router, r.path(), Journey.Settings.DEFAULT, 1);
                    walks++;
                    from = to;
                }
            }
            // And reads the blocks again, as a trip does before it plans.
            for (int i = 0; i < 3 && !stop; i++) {
                world.resync(snap, min, max);
            }
            System.out.printf("[astar] walk layout warmed up around the player (%d walks in"
                    + " %.0f ms)%n", walks, (System.nanoTime() - t0) / 1e6);
        } catch (RuntimeException e) {
            System.out.println("[astar] walk warm-up failed: " + e);
        }
    }

    /**
     * Plans a few trips on the whole-map copy around the player, as a trip does (the blocks
     * read again, a search with the kept graph and landmarks, the walk laid out), on a thread
     * of its own; returns at once. The first trip on it otherwise runs code the JVM hasn't
     * compiled yet for the whole map's blocks, graph and landmarks: about three times slower
     * than later ones. Each step holds the copy's lock and stops when a trip starts planning
     * ({@link #tripPlanning}), so a trip waits at most one step.
     */
    static void whole(LiveMap map, LiveWorld.Snapshot snap, BlockPos min, BlockPos max,
            BlockPos player) {
        Thread t = new Thread(() -> wholeTrips(map, snap, min, max, player),
                "astar walk warm-up");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static void wholeTrips(LiveMap map, LiveWorld.Snapshot snap, BlockPos min,
            BlockPos max, BlockPos player) {
        try {
            long t0 = System.nanoTime();
            LiveWorld world = map.world;
            BlockPos o = world.origin();
            WorldPathfinder base = map.finder.base();
            GridRouter router = new GridRouter(base, world);
            BlockPoint from;
            synchronized (map) {
                if (stop) {
                    return;
                }
                world.resync(snap, min, max);
                from = router.standingAt(player.getX() + 0.5 - o.getX(),
                        player.getY() - o.getY(), player.getZ() + 0.5 - o.getZ());
            }
            // Between places to stand 30 to 60 blocks apart, each from where the last ended.
            List<BlockPoint> places = new ArrayList<>();
            synchronized (map) {
                for (int y = min.getY() - o.getY(); y <= max.getY() - o.getY(); y++) {
                    for (int z = min.getZ() - o.getZ(); z <= max.getZ() - o.getZ(); z++) {
                        for (int x = min.getX() - o.getX(); x <= max.getX() - o.getX(); x++) {
                            BlockPoint p = new BlockPoint(x, y, z);
                            if (world.contains(new BlockPos(x + o.getX(), y + o.getY(),
                                    z + o.getZ())) && base.canStand(p)) {
                                places.add(p);
                            }
                        }
                    }
                }
            }
            Random random = new Random(7);
            int walks = 0;
            for (int i = 0; i < WHOLE_TRIPS * 4 && walks < WHOLE_TRIPS && from != null
                    && !places.isEmpty() && !stop; i++) {
                BlockPoint to = null;
                for (int tries = 0; tries < 50 && to == null; tries++) {
                    BlockPoint p = places.get(random.nextInt(places.size()));
                    int d = Math.abs(p.x() - from.x()) + Math.abs(p.z() - from.z());
                    if (d >= 30 && d <= 60) {
                        to = p;
                    }
                }
                if (to == null) {
                    continue;
                }
                synchronized (map) {
                    if (stop) {
                        break;
                    }
                    world.resync(snap, min, max);
                    SearchResult r = map.finder.forStart(from.pack()).find(from, to);
                    if (r.found() && r.path().size() > 1) {
                        Journey.prepare(router, r.path(), Journey.Settings.DEFAULT, 1);
                        walks++;
                        from = to;
                    }
                }
            }
            System.out.printf("[astar] trips warmed up on the whole-map copy (%d walks in"
                    + " %.0f ms)%n", walks, (System.nanoTime() - t0) / 1e6);
        } catch (RuntimeException e) {
            System.out.println("[astar] whole-map warm-up failed: " + e);
        }
    }

    private static void set(byte[] kinds, SimBlock[] blocks, int x, int y, int z, BlockType t,
            SimBlock b) {
        int i = (y * SIZE + z) * SIZE + x;
        kinds[i] = (byte) t.ordinal();
        blocks[i] = b;
    }
}
