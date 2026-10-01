package astar.client;

import astar.core.BlockPoint;
import astar.core.PathStep;
import astar.core.SearchResult;
import astar.movement.Keys;
import astar.movement.exec.GridRouter;
import astar.movement.exec.Follower;
import astar.movement.exec.Journey;
import astar.movement.exec.PlayerController;
import astar.movement.plan.ExecutionPlan;
import astar.pathing.WorldPathfinder;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

/**
 * Walks the player to a block by itself: copies the loaded world around the player and the
 * goal and finds a path off the game thread, then plays a {@link Journey} live, one tick at a
 * time, through the player's own keys and camera. The journey gets the player back on track
 * when pushed, lagged back, stuck, or when a block or shut door gets in the way, re-reading
 * blocks from the game as it goes ({@link LiveRouter}).
 *
 * <p>Any movement key or mouse turn pauses it and gives the controls back ({@code /goto
 * resume} plans again from where the player is). It stops in front of ladders and water,
 * which the executor doesn't do yet.
 */
final class Navigator {

    /**
     * Blocks around the start and goal the copy reaches past them, across the ground: routes
     * often swing out past both ends. Long trips get {@link #SMALL_MARGIN} to stay within
     * {@link LiveWorld#MAX_VOLUME}.
     */
    private static final int MARGIN = 48;
    private static final int SMALL_MARGIN = 16;
    /** Blocks above and below them. */
    private static final int MARGIN_Y = 24;
    /**
     * Wider margins to read, in turn, when there's no path in the copy: a route can swing far
     * out past both ends to get round (from one corner of the Mines to another, 160 blocks).
     */
    private static final int[] WIDER = {128, 256};
    /** How far the camera may have moved since the last tick before it counts as the mouse. */
    private static final float TURNED = 1e-3F;
    /** Ticks to wait for the chunks in view to load (after a teleport, say) before planning. */
    private static final int CHUNK_WAIT = 100;
    /** Ticks to keep waiting once no more chunks arrive. */
    private static final int STALL_WAIT = 10;
    /**
     * The most blocks a stretch of a charted route may span (its box, with {@link
     * #LEG_MARGIN} around), so each stretch is read and planned quickly.
     */
    private static final long LEG_VOLUME = 8_000_000;
    private static final int LEG_MARGIN = 16;
    private static final int LEG_MARGIN_Y = 8;
    /** Blocks around a trip's ends (and charted way) a kept copy must reach to be used. */
    private static final int NEED = 8;

    enum State {
        PLANNING, DRIVING, PAUSED, DONE
    }

    /** A path, or why there isn't one, on a copy of the world. */
    private record Planned(LiveWorld world, WorldPathfinder finder, List<PathStep> path,
            String error, double ms, double copyMs, String landmarks, boolean noPath,
            Journey.Prepared prepared, double prepareMs, boolean whole, double gameMs,
            List<Journey.Prepared> legs, List<Warps.Hop> hops) {

        Planned(LiveWorld world, WorldPathfinder finder, List<PathStep> path, String error,
                double ms, double copyMs, String landmarks, boolean noPath,
                Journey.Prepared prepared, double prepareMs, boolean whole, double gameMs) {
            this(world, finder, path, error, ms, copyMs, landmarks, noPath, prepared, prepareMs,
                    whole, gameMs, null, List.of());
        }
    }

    private final BlockPos goal;
    /**
     * Which teleports routes use as well as walking ({@code /goto warp}, {@code it}, {@code
     * aotv}), or null for walking only.
     */
    private final Warps.Mode warp;
    /**
     * With hops: the walks between them, one more than there are hops ({@code null} where a
     * hop lands right where the next is cast), the hops, and which leg is being walked.
     */
    private List<Journey.Prepared> legs;
    private List<Warps.Hop> hops = List.of();
    /** The blocks the hops were planned on, to cast on the move. */
    private astar.pathing.ArrayBlockView hopBlocks;
    private int leg;
    /** The hop being cast, or null while walking. */
    private HopCast cast;
    /** Hops that failed this trip, and how many may before it gives up. */
    private int failedHops;
    private static final int MAX_FAILED_HOPS = 5;
    /**
     * Where this stretch goes: the goal, or a stop on the way when the route runs past what
     * the game has loaded ({@link #chart}).
     */
    private BlockPos target;
    /** The whole way over saved chunks, being worked out; null when not. */
    private CompletableFuture<Leg> charting;
    /** Whether this stretch was already charted over saved chunks (so it isn't twice). */
    private boolean charted;
    /**
     * Whether this stretch is waiting for the whole-map copy ({@link LiveMap#whole}) to plan
     * on, and whether it was tried, after no path on a smaller copy.
     */
    private boolean awaitingWhole;
    private boolean triedWhole;
    private long wholeSince;
    /** The whole way as charted, in game coordinates; later stops are picked along it. */
    private List<BlockPoint> chartedPath;
    /**
     * The charted way from here to {@link #target}, or null: the copy read for this stretch
     * covers all of it, however far it swings out or dips below both ends.
     */
    private List<BlockPoint> legPath;
    private final Consumer<Component> say;
    private State state = State.PLANNING;
    private CompletableFuture<Planned> planning;
    private Journey journey;
    private LiveRouter router;
    private BlockPos origin;
    private GameController controller;
    private ScriptedInput input;
    private final Map<Journey.Event, Integer> told = new EnumMap<>(Journey.Event.class);
    /** Ticks waited so far for chunks to load, or -1 when not waiting. */
    private int waiting = -1;
    /** The fewest chunks in view seen unloaded while waiting, and ticks since it fell. */
    private int leastMissing = Integer.MAX_VALUE;
    private int sinceFewer;
    /** Whether the last copy planned on stops short of the margins a new one would have. */
    private boolean partialCopy;
    /** Whether this stretch must plan on a copy with the full margins (after no path). */
    private boolean fullBox;
    /** How many of the {@link #WIDER} margins have been tried. */
    private int wider;
    /** Why the last, narrower copy had no path, while a wider one is read. */
    private String narrower;
    private String outcome = "";
    /**
     * How far ahead of the player the lead marker rides along the route, in blocks, before the
     * camera has picked where it looks (the marker then rides on that point).
     */
    private static final double LEAD_AHEAD = 4;
    private Vec3 lead;
    private Vec3 lastLead;

    /** The next stop on a route charted over saved chunks, or why there's no route. */
    private record Leg(BlockPos stop, String error, int steps, int chunks, double ms,
            List<BlockPoint> path, int last) {}

    Navigator(BlockPos goal, Consumer<Component> say) {
        this(goal, say, null);
    }

    Navigator(BlockPos goal, Consumer<Component> say, Warps.Mode warp) {
        this.goal = goal;
        this.warp = warp;
        this.target = goal;
        this.say = say;
    }

    State state() {
        return state;
    }

    BlockPos goal() {
        return goal;
    }

    /** What the journey ran into and how it recovered, counted. */
    String events() {
        return journey == null ? "{}" : journey.events().toString();
    }

    /** How it ended: arrived, stopped and why; empty while it's still going. */
    String outcome() {
        return outcome;
    }

    /** Copies the world around the player and starts planning; call on the game thread. */
    void plan(Minecraft client) {
        LocalPlayer player = client.player;
        WalkWarmUp.tripPlanning();
        BlockPos start = player.blockPosition();
        int margin = wider > 0 ? WIDER[wider - 1]
                : (long) (Math.abs(start.getX() - target.getX()) + 2 * MARGIN)
                * (Math.abs(start.getZ() - target.getZ()) + 2 * MARGIN)
                * (Math.abs(start.getY() - target.getY()) + 2 * MARGIN_Y) <= LiveWorld.MAX_VOLUME
                ? MARGIN : SMALL_MARGIN;
        BlockPos min = new BlockPos(Math.min(start.getX(), target.getX()) - margin,
                Math.min(start.getY(), target.getY()) - MARGIN_Y,
                Math.min(start.getZ(), target.getZ()) - margin);
        BlockPos max = new BlockPos(Math.max(start.getX(), target.getX()) + margin,
                Math.max(start.getY(), target.getY()) + MARGIN_Y,
                Math.max(start.getZ(), target.getZ()) + margin);
        // What the trip can't do without: its two ends, and the charted way between them. A
        // copy already holding that is used as it is (if there's no path in it, the retry
        // reads wider).
        BlockPos needMin = new BlockPos(Math.min(start.getX(), target.getX()) - NEED,
                Math.min(start.getY(), target.getY()) - NEED,
                Math.min(start.getZ(), target.getZ()) - NEED);
        BlockPos needMax = new BlockPos(Math.max(start.getX(), target.getX()) + NEED,
                Math.max(start.getY(), target.getY()) + NEED,
                Math.max(start.getZ(), target.getZ()) + NEED);
        if (legPath != null) {
            int[] b = bounds(legPath, 0, legPath.size() - 1);
            needMin = new BlockPos(Math.min(needMin.getX(), b[0] - NEED),
                    Math.min(needMin.getY(), b[1] - NEED), Math.min(needMin.getZ(), b[2] - NEED));
            needMax = new BlockPos(Math.max(needMax.getX(), b[3] + NEED),
                    Math.max(needMax.getY(), b[4] + NEED), Math.max(needMax.getZ(), b[5] + NEED));
            min = new BlockPos(Math.min(min.getX(), b[0] - LEG_MARGIN),
                    Math.min(min.getY(), b[1] - LEG_MARGIN_Y),
                    Math.min(min.getZ(), b[2] - LEG_MARGIN));
            max = new BlockPos(Math.max(max.getX(), b[3] + LEG_MARGIN),
                    Math.max(max.getY(), b[4] + LEG_MARGIN_Y),
                    Math.max(max.getZ(), b[5] + LEG_MARGIN));
        }
        boolean goalLoaded = client.level.getChunkSource().hasChunk(target.getX() >> 4,
                target.getZ() >> 4)
                || Places.saved(client.level, target.getX() >> 4, target.getZ() >> 4) != null;
        int view = client.options.getEffectiveRenderDistance();
        int missing = LiveWorld.unloadedChunks(client.level, min, max, start, view);
        if (missing < leastMissing) {
            leastMissing = missing;
            sinceFewer = 0;
        } else {
            sinceFewer++;
        }
        // Wait while chunks keep arriving; a server that sees less far than the game (or a
        // chunk that never comes) mustn't hold every trip up for the whole wait.
        // The whole map's copy, when it holds the trip, has what isn't loaded from the saved map.
        boolean whole = LiveMap.wholeHolds(client.level, start, needMin, needMax);
        if (warp != null && !whole && LiveMap.whole(client.level, start) == LiveMap.Whole.COMING) {
            // Hops are worked out over the whole map: wait for its copy rather than walk.
            awaitingWhole = true;
            triedWhole = true;
            wholeSince = System.nanoTime();
            state = State.PLANNING;
            say.accept(Component.literal("Finishing the whole map's copy to plan " + warp.plural
                    + " on...").withStyle(ChatFormatting.GRAY));
            return;
        }
        if ((missing > 0 && sinceFewer < STALL_WAIT && !whole || !goalLoaded)
                && waiting < CHUNK_WAIT) {
            // Try again next tick: the world may still be loading around the player.
            state = State.PLANNING;
            waiting = Math.max(waiting, 0);
            return;
        }
        if (waiting > 0) {
            System.out.printf("[astar] waited %d ticks for chunks (%d in view still missing)%n",
                    waiting, missing);
        }
        waiting = -1;
        leastMissing = Integer.MAX_VALUE;
        sinceFewer = 0;
        if (!goalLoaded) {
            stop("the goal isn't loaded or saved; come closer to it first");
            return;
        }
        LiveMap.Cover cover;
        long t0 = System.nanoTime();
        try {
            // The copy from the last trip if this one fits in it, read again for changes (off
            // the game thread, from a snapshot of the loaded chunks taken here).
            cover = wider > 0 || fullBox
                    ? LiveMap.covering(client.level, min, max, min, max, start, view)
                    : LiveMap.covering(client.level, needMin, needMax, min, max, start, view);
            partialCopy = !cover.holds(min, max);
        } catch (IllegalArgumentException e) {
            stop(narrower != null ? narrower + ", and further out is too much to read"
                    : e.getMessage());
            return;
        }
        double snapMs = (System.nanoTime() - t0) / 1e6;
        state = State.PLANNING;
        Vec3 at = player.position();
        double speed = speed(player);
        planning = CompletableFuture.supplyAsync(() -> plan(cover, at, target, snapMs, missing,
                margin, speed, warp));
    }

    /** Reads the copy up to date and finds the path: plain Java, off the game thread. */
    private static Planned plan(LiveMap.Cover cover, Vec3 at, BlockPos goal, double snapMs,
            int missing, int margin, double speed, Warps.Mode warp) {
        long t0 = System.nanoTime();
        LiveMap map;
        try {
            map = cover.get();
        } catch (IllegalArgumentException e) {
            return failed(e.getMessage());
        }
        double copyMs = snapMs + (System.nanoTime() - t0) / 1e6;
        t0 = System.nanoTime();
        LiveWorld world = map.world;
        BlockPos o = world.origin();
        if (!world.contains(BlockPos.containing(at)) || !world.contains(goal)) {
            return failed("the goal is outside what was read");
        }
        WorldPathfinder base = map.finder.base();
        BlockPoint from = new GridRouter(base, world).standingAt(
                at.x - o.getX(), at.y - o.getY(), at.z - o.getZ());
        BlockPoint to = standing(base, goal.getX() - o.getX(), goal.getY() - o.getY(),
                goal.getZ() - o.getZ());
        if (from == null) {
            return failed("there's nowhere to stand where you are");
        }
        if (to == null) {
            return failed("there's nowhere to stand at the goal");
        }
        if (warp != null && map.whole) {
            return planWarps(map, base, from, to, t0, copyMs, snapMs, speed, warp);
        }
        // The kept move graph and, once built, the kept landmarks (see LiveMap).
        WorldPathfinder finder = map.finder.forStart(from.pack());
        SearchResult result = finder.find(from, to);
        if (!result.found()) {
            BlockPos size = world.size();
            return new Planned(null, null, null, "no path in the " + size.getX() + " x "
                    + size.getZ() + " x " + size.getY() + " blocks read around you ("
                    + result.expanded() + " places tried"
                    + (missing == 0 ? "" : "; " + missing + " chunks in view weren't loaded")
                    + ")", 0, 0, null, true, null, 0, map.whole, 0);
        }
        // Lay it along lines and plan the walk here too, not on the game thread: on a long
        // route that takes a moment.
        long t1 = System.nanoTime();
        Journey.Prepared prepared = Journey.prepare(new GridRouter(base, world), result.path(),
                Journey.Settings.DEFAULT.withHand(MouseHand.get()), speed);
        double prepareMs = (System.nanoTime() - t1) / 1e6;
        double ms = copyMs + (System.nanoTime() - t0) / 1e6;
        String landmarks = (warp != null ? "walking only: hops need the whole map's copy, " : "")
                + (map.finder.landmarks() != null ? "with landmarks"
                : map.finder.buildingLandmarks() ? "landmarks on the way" : "no landmarks")
                + (map.whole ? ", whole-map copy" : map.trips == 1 ? (map.warmed
                        ? ", copy made ahead" : ", new copy") : ", copy's trip " + map.trips);
        return new Planned(world, finder, result.path(), null, ms, copyMs, landmarks, false,
                prepared, prepareMs, map.whole, snapMs);
    }

    /**
     * A route that etherwarps as well as walks, over the whole map's copy, split where it hops;
     * each walk between hops laid out here, off the game thread.
     */
    private static Planned planWarps(LiveMap map, WorldPathfinder base, BlockPoint from,
            BlockPoint to, long t0, double copyMs, double snapMs, double speed,
            Warps.Mode mode) {
        LiveWorld world = map.world;
        Warps.Route route = Warps.route(world.blockView(), base, from, to, mode);
        if (!route.result().found()) {
            BlockPos size = world.size();
            return new Planned(null, null, null, "no path in the " + size.getX() + " x "
                    + size.getZ() + " x " + size.getY() + " blocks of the map, even with "
                    + mode.plural, 0, 0, null, true, null, 0, true, 0);
        }
        long t1 = System.nanoTime();
        GridRouter router = new GridRouter(base, world);
        Journey.Settings settings = Journey.Settings.DEFAULT.withHand(MouseHand.get());
        List<Journey.Prepared> legs = new java.util.ArrayList<>();
        for (List<PathStep> leg : route.legs()) {
            legs.add(leg.size() < 2 ? null : Journey.prepare(router, leg, settings, speed));
        }
        double prepareMs = (System.nanoTime() - t1) / 1e6;
        double ms = copyMs + (System.nanoTime() - t0) / 1e6;
        long its = route.hops().stream().filter(Warps.Hop::transmit).count();
        String note = (route.hops().size() - its) + " etherwarps, " + its + " transmissions"
                + (route.buildMs() > 0
                ? String.format(", hops worked out in %.0f ms", route.buildMs()) : "")
                + ", whole-map copy";
        return new Planned(world, base, route.result().path(), null, ms, copyMs, note, false,
                legs.get(0), prepareMs, true, snapMs, legs, route.hops());
    }

    /** How many times a normal player's ground speed the player moves now (Speed, Slowness). */
    private static double speed(LocalPlayer player) {
        // The attribute carries sprinting's 30% while sprinting; the simulator adds its own.
        double speed = player.getAttributeValue(Attributes.MOVEMENT_SPEED);
        return (player.isSprinting() ? speed / 1.3 : speed)
                / astar.movement.sim.SimulatedPlayer.Attributes.PLAYER.movementSpeed();
    }

    private static Planned failed(String why) {
        return new Planned(null, null, null, why, 0, 0, null, false, null, 0, false, 0);
    }

    /**
     * Finds the whole way to the goal over what the game has loaded and what {@link Places}
     * saved, off the game thread, then picks the furthest point along it that's still loaded:
     * the stop the next stretch walks to. On the game thread.
     */
    private void chart(Minecraft client) {
        charted = true;
        LocalPlayer player = client.player;
        int view = client.options.getEffectiveRenderDistance();
        int pcx = player.blockPosition().getX() >> 4;
        int pcz = player.blockPosition().getZ() >> 4;
        // Quick copies here; made into saved chunks off the game thread.
        List<SavedChunk.Copied> copies = new java.util.ArrayList<>();
        for (int cx = pcx - view; cx <= pcx + view; cx++) {
            for (int cz = pcz - view; cz <= pcz + view; cz++) {
                if (client.level.getChunkSource().hasChunk(cx, cz)) {
                    copies.add(SavedChunk.copy(client.level.getChunk(cx, cz)));
                }
            }
        }
        Places.Place place = Places.current();
        int minY = client.level.getMinY();
        int maxY = client.level.getMaxY();
        Vec3 at = player.position();
        say.accept(Component.literal("No way round in what's loaded; looking over the chunks"
                + " saved for " + place.name + "...").withStyle(ChatFormatting.GRAY));
        state = State.PLANNING;
        charting = CompletableFuture.supplyAsync(() -> {
            Map<Long, SavedChunk> loaded = new java.util.HashMap<>();
            for (SavedChunk.Copied c : copies) {
                loaded.put(SavedView.key(c.cx(), c.cz()), c.build());
            }
            return chart(new SavedView(loaded, place, minY, maxY), loaded, at, goal);
        });
    }

    /** After reaching {@code reached} on the charted way: the next stop along it, or the goal. */
    private BlockPos nextStop(Minecraft client, BlockPos reached) {
        if (chartedPath == null) {
            return goal;
        }
        int at = -1;
        for (int i = 0; i < chartedPath.size(); i++) {
            BlockPoint p = chartedPath.get(i);
            if (p.x() == reached.getX() && p.y() == reached.getY() && p.z() == reached.getZ()) {
                at = i;
            }
        }
        if (at < 0) {
            return goal;
        }
        int last = furthest(chartedPath, at, (cx, cz) -> client.level.getChunkSource()
                .hasChunk(cx, cz));
        if (last == at) {
            legPath = null;
            return goal; // nothing more loaded along it: plan (or chart) afresh
        }
        legPath = chartedPath.subList(at, last + 1);
        if (last == chartedPath.size() - 1) {
            return goal;
        }
        BlockPoint p = chartedPath.get(last);
        return new BlockPos(p.x(), p.y(), p.z());
    }

    private static Leg chart(SavedView view, Map<Long, SavedChunk> loaded, Vec3 at,
            BlockPos goal) {
        long t0 = System.nanoTime();
        WorldPathfinder finder = astar.movement.exec.GotoPathfinder.across(view);
        BlockPoint from = standing(finder, (int) Math.floor(at.x), (int) Math.floor(at.y + 1e-3),
                (int) Math.floor(at.z));
        BlockPoint to = standing(finder, goal.getX(), goal.getY(), goal.getZ());
        if (from == null || to == null) {
            return new Leg(null, "no way to the goal in the saved chunks either (nowhere to"
                    + " stand " + (from == null ? "where you are" : "at the goal") + ")", 0, 0, 0, null,
                    0);
        }
        SearchResult r = finder.find(from, to);
        double ms = (System.nanoTime() - t0) / 1e6;
        if (!r.found()) {
            return new Leg(null, "no way to the goal, even over the saved chunks ("
                    + r.expanded() + " places tried)", 0, 0, ms, null, 0);
        }
        List<BlockPoint> path = r.path().stream().map(PathStep::pos).toList();
        int last = furthest(path, 0, (cx, cz) -> loaded.containsKey(SavedView.key(cx, cz)));
        if (last == 0) {
            return new Leg(null, "the way to the goal starts outside what's loaded", 0, 0, ms,
                    null, 0);
        }
        BlockPoint stop = path.get(last);
        BlockPos stopPos = last == path.size() - 1 ? goal
                : new BlockPos(stop.x(), stop.y(), stop.z());
        return new Leg(stopPos, null, path.size() - 1, loaded.size(), ms, path, last);
    }

    /** Whether the game has chunk (cx, cz) loaded. */
    private interface ChunkLoaded {
        boolean test(int cx, int cz);
    }

    /**
     * How far along a charted way the next stretch goes from {@code from}: as far as the game
     * has it loaded, and no further than keeps the stretch's box within {@link #LEG_VOLUME}.
     */
    private static int furthest(List<BlockPoint> path, int from, ChunkLoaded loaded) {
        int last = from;
        for (int i = from + 1; i < path.size(); i++) {
            BlockPoint p = path.get(i);
            if (!loaded.test(p.x() >> 4, p.z() >> 4)) {
                break;
            }
            int[] b = bounds(path, from, i);
            long volume = (long) (b[3] - b[0] + 1 + 2 * LEG_MARGIN)
                    * (b[4] - b[1] + 1 + 2 * LEG_MARGIN_Y) * (b[5] - b[2] + 1 + 2 * LEG_MARGIN);
            if (volume > LEG_VOLUME) {
                break;
            }
            last = i;
        }
        return last;
    }

    /** The box around cells {@code from} to {@code to}: min x, y, z, then max x, y, z. */
    private static int[] bounds(List<BlockPoint> path, int from, int to) {
        int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE,
            Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (int i = from; i <= to; i++) {
            BlockPoint p = path.get(i);
            b[0] = Math.min(b[0], p.x());
            b[1] = Math.min(b[1], p.y());
            b[2] = Math.min(b[2], p.z());
            b[3] = Math.max(b[3], p.x());
            b[4] = Math.max(b[4], p.y());
            b[5] = Math.max(b[5], p.z());
        }
        return b;
    }

    /** The cell to stand in at or next to a block: the block itself, or just above or below. */
    private static BlockPoint standing(WorldPathfinder finder, int x, int y, int z) {
        for (int dy : new int[] {0, 1, -1, 2}) {
            BlockPoint p = new BlockPoint(x, y + dy, z);
            if (finder.canStand(p)) {
                return p;
            }
        }
        return null;
    }

    /** One game tick; call at the start of each client tick, before the player moves. */
    void tick(Minecraft client) {
        LocalPlayer player = client.player;
        if (state == State.PLANNING) {
            if (charting != null) {
                if (charting.isDone()) {
                    Leg leg = charting.join();
                    charting = null;
                    if (leg.error() != null) {
                        stop(leg.error());
                        return;
                    }
                    target = leg.stop();
                    chartedPath = leg.path();
                    legPath = chartedPath.subList(0, leg.last() + 1);
                    say.accept(Component.literal(String.format("Found the whole way over"
                            + " saved chunks (%d steps, %d chunks known, %.0f ms); %s.",
                            leg.steps(), leg.chunks(), leg.ms(), target.equals(goal)
                                    ? "planning it" : "walking to " + target.toShortString()
                                    + " first")).withStyle(ChatFormatting.GRAY));
                    plan(client);
                }
                return;
            }
            if (awaitingWhole) {
                if (LiveMap.whole(client.level, player.blockPosition()) != LiveMap.Whole.COMING) {
                    awaitingWhole = false;
                    System.out.printf("[astar] waited %.0f ms for the whole map's copy%n",
                            (System.nanoTime() - wholeSince) / 1e6);
                    plan(client);
                }
                return;
            }
            if (planning == null && waiting >= 0) {
                waiting++;
                plan(client);
                return;
            }
            if (planning == null || !planning.isDone()) {
                return;
            }
            Planned p = planning.join();
            planning = null;
            if (p.error() != null && p.noPath() && partialCopy && !fullBox) {
                // Planned on a kept copy that doesn't reach as far round as a new one would.
                fullBox = true;
                plan(client);
                return;
            }
            if (p.error() != null && p.noPath() && !p.whole() && !triedWhole) {
                // The whole map, once copied, plans in a moment what charting over the saved
                // chunks takes seconds for: wait for it if it's on the way.
                LiveMap.Whole whole = LiveMap.whole(client.level, player.blockPosition());
                if (whole != LiveMap.Whole.NONE) {
                    triedWhole = true;
                    if (whole == LiveMap.Whole.COMING) {
                        awaitingWhole = true;
                        wholeSince = System.nanoTime();
                        say.accept(Component.literal("The way runs past what's loaded;"
                                + " finishing the whole map's copy to plan on...").withStyle(
                                ChatFormatting.GRAY));
                    } else {
                        plan(client);
                    }
                    return;
                }
            }
            if (p.error() != null && p.noPath() && !charted && Places.current() != null) {
                // The way round may run past what's loaded: find it over the saved chunks.
                chart(client);
                return;
            }
            if (p.error() != null && p.noPath() && wider < WIDER.length) {
                // The way round may lie further out: read wider and plan again.
                narrower = p.error();
                wider++;
                say.accept(Component.literal("No way round close by; reading "
                        + WIDER[wider - 1] + " blocks further out...").withStyle(
                        ChatFormatting.GRAY));
                plan(client);
                return;
            }
            if (p.error() != null) {
                stop(p.error());
                return;
            }
            drive(player, p);
            return;
        }
        if (state != State.DRIVING) {
            return;
        }
        if (player.isDeadOrDying()) {
            stop("the player died");
            return;
        }
        if (player.input != input) {
            stop("the player was replaced (respawn or world change)");
            return;
        }
        if (touched(client.options, player)) {
            pause(player, client.options);
            say.accept(Component.literal("Paused: you took the controls. /goto resume carries on,"
                    + " /goto stop ends it.").withStyle(ChatFormatting.YELLOW));
            return;
        }
        if (cast != null) {
            switch (cast.tick(client)) {
                case RUNNING -> { }
                case LANDED -> {
                    cast = null;
                    leg++;
                    startLeg(player);
                }
                case FAILED -> {
                    // Leave that hop out and plan again from here, walking round if need be.
                    Warps.Hop failed = hops.get(leg);
                    Warps.ban(failed);
                    String why = cast.reason();
                    cast = null;
                    release(player, client.options);
                    if (++failedHops > MAX_FAILED_HOPS) {
                        stop(why);
                        return;
                    }
                    say.accept(Component.literal("Couldn't " + (failed.transmit()
                            ? "transmit" : "etherwarp") + ": " + why + ". Planning"
                            + " round it...").withStyle(ChatFormatting.GRAY));
                    state = State.PLANNING;
                    plan(client);
                }
            }
            return;
        }
        Journey.Status status = journey.tick();
        if (status == Journey.Status.RUNNING && leg < hops.size() && walkingIn(player)) {
            // The last straight bit onto a hop's spot: the cast takes it from here, stopping
            // where it can be cast from (one step, or sneaking to a stop for an etherwarp)
            // and aimed afresh, instead of the walk slowing onto the spot.
            castHop(player);
            return;
        }
        noteLead();
        tell();
        switch (status) {
            case RUNNING -> { }
            case ARRIVED -> {
                if (leg < hops.size()) {
                    castHop(player);
                    return;
                }
                release(player, client.options);
                if (!target.equals(goal)) {
                    // A stop on the way: on along the charted way, to the furthest point of
                    // it the game has loaded now, charting again only if that fails.
                    target = nextStop(client, target);
                    charted = false;
                    triedWhole = false;
                    fullBox = false;
                    wider = 0;
                    narrower = null;
                    state = State.PLANNING;
                    say.accept(Component.literal("Reached " + player.blockPosition()
                            .toShortString() + "; on to " + (target.equals(goal) ? "the goal"
                            : target.toShortString()) + "...").withStyle(ChatFormatting.GRAY));
                    plan(client);
                    return;
                }
                finish("arrived at " + player.blockPosition().toShortString());
            }
            case UNSUPPORTED -> {
                release(player, client.options);
                finish("stopped in front of " + blocker() + ", which it can't do yet");
            }
            case FAILED -> {
                release(player, client.options);
                stop(journey.reason());
            }
        }
    }

    /** The journey being played or paused, for drawing; null while planning or done. */
    Journey journey() {
        return state == State.DRIVING || state == State.PAUSED ? journey : null;
    }

    /**
     * A trip with teleports as it stands, for drawing: the walks between hops (null where there
     * is none), the hops, and which leg is under way.
     */
    record Trip(List<Journey.Prepared> legs, List<Warps.Hop> hops, int leg) {}

    /** The trip being made, if it teleports and is under way or paused; else null. */
    Trip trip() {
        return (state == State.DRIVING || state == State.PAUSED) && !hops.isEmpty()
                ? new Trip(legs, hops, leg) : null;
    }

    /** Where the journey's grid is in the world: add it to a path cell to get the block. */
    BlockPos origin() {
        return origin;
    }

    /**
     * The point on the route {@link #LEAD_AHEAD} blocks ahead of the player, in the world,
     * moved smoothly from last tick's to this tick's by {@code partialTick}; null before the
     * first tick.
     */
    Vec3 lead(float partialTick) {
        return lead == null ? null : lastLead.lerp(lead, partialTick);
    }

    private void noteLead() {
        // Where the camera looks, so the marker sits where the view is aimed.
        double[] p = journey.executor().gaze();
        if (p == null) {
            p = journey.executor().follower().placeAhead(LEAD_AHEAD);
        }
        Vec3 now = new Vec3(p[0] + origin.getX(), p[1] + origin.getY(), p[2] + origin.getZ());
        // A new stretch (after a reroute) starts somewhere else: jump there, don't slide.
        lastLead = lead == null || lead.distanceToSqr(now) > 4 ? now : lead;
        lead = now;
    }

    /** Says in chat when it had to get back on track, once per kind of trouble and tick. */
    private void tell() {
        for (Map.Entry<Journey.Event, Integer> e : journey.events().entrySet()) {
            int before = told.getOrDefault(e.getKey(), 0);
            if (e.getValue() > before) {
                told.put(e.getKey(), e.getValue());
                String what = switch (e.getKey()) {
                    case TELEPORTED -> "Moved back (lag?); carrying on.";
                    case BLOCKED -> "Something's in the way ahead; finding a way round.";
                    case OFF_COURSE -> "Off the route; getting back on.";
                    case STUCK -> "Stuck; trying another way.";
                    case REROUTED -> "Planned the whole way again.";
                    default -> null;
                };
                if (what != null) {
                    say.accept(Component.literal(what).withStyle(ChatFormatting.GRAY));
                }
            }
        }
    }

    /** What the journey stops in front of: a ladder or water, by the blocks there. */
    private String blocker() {
        ExecutionPlan.Kind kind = journey.executor().blockedBy();
        if (kind == ExecutionPlan.Kind.CLIMB) {
            return "a ladder";
        }
        if (kind == ExecutionPlan.Kind.SWIM) {
            return "water";
        }
        List<ExecutionPlan.Node> nodes = journey.plan().nodes();
        int end = journey.executor().end();
        for (int i = end; i < Math.min(nodes.size(), end + 3); i++) {
            BlockPoint c = nodes.get(i).cell();
            for (int dy = -1; dy <= 0; dy++) {
                astar.movement.sim.SimBlock b = router.world().block(c.x(), c.y() + dy, c.z());
                if (b.climbable()) {
                    return "a ladder";
                }
                if (b.water()) {
                    return "water";
                }
            }
        }
        return "a spot with nothing to stand on";
    }

    private void drive(LocalPlayer player, Planned p) {
        input = new ScriptedInput();
        player.input = input;
        origin = p.world().origin();
        lead = null;
        controller = new GameController(player, input, origin);
        router = new LiveRouter(p.world(), p.finder(), () -> player.level(), () -> {
            Vec3 v = player.position();
            return new double[] {v.x - origin.getX(), v.y - origin.getY(), v.z - origin.getZ()};
        });
        List<PathStep> path = p.path();
        legs = p.legs();
        hops = p.hops();
        hopBlocks = hops.isEmpty() ? null : p.world().blockView();
        leg = 0;
        cast = null;
        told.clear();
        state = State.DRIVING;
        if (!hops.isEmpty()) {
            startLeg(player);
            double walked = 0;
            for (Journey.Prepared l : legs) {
                walked += l == null ? 0 : l.plan().length();
            }
            say.accept(Component.literal(String.format("Going to %s: %d %s and %.0f"
                    + " blocks on foot (planned in %.0f ms: %.0f reading blocks, %.0f laying out"
                    + " the walks; %s). Any key or mouse move pauses it.",
                    goal.toShortString(), hops.size(), warp.plural, walked, p.ms(), p.copyMs(),
                    p.prepareMs(), p.landmarks())));
            return;
        }
        journey = new Journey(router, controller, path.get(path.size() - 1).pos(),
                p.prepared(), Journey.Settings.DEFAULT.withHand(MouseHand.get()));
        int end = journey.executor().end();
        String upTo = journey.executor().blockedBy() == null ? ""
                : ", up to " + blocker() + " at step " + end;
        say.accept(Component.literal(String.format("Going to %s: %d steps, %.0f blocks%s"
                + " (planned in %.0f ms: %.0f reading blocks, %.0f of it on the game thread,"
                + " %.0f laying out the walk; %s). Any key or mouse move pauses it.",
                goal.toShortString(), path.size() - 1, journey.plan().length(), upTo, p.ms(),
                p.copyMs(), p.gameMs(), p.prepareMs(), p.landmarks())));
    }

    /** Walks leg {@link #leg} of a route with hops, or casts the next hop if it's no walk. */
    private void startLeg(LocalPlayer player) {
        Journey.Prepared l = legs.get(leg);
        if (l == null && leg < hops.size()) {
            castHop(player);
            return;
        }
        if (l == null) {
            // The last hop landed on the goal.
            release(player, Minecraft.getInstance().options);
            finish("arrived at " + player.blockPosition().toShortString());
            return;
        }
        List<PathStep> path = l.path();
        journey = new Journey(router, controller, path.get(path.size() - 1).pos(), l,
                Journey.Settings.DEFAULT.withHand(MouseHand.get()));
    }

    /** How near a transmission's spot, in blocks, the cast takes over from the walk. */
    private static final double WALK_IN = 2.5;

    /**
     * Whether the player is on the ground within {@link #WALK_IN} of where hop {@link #leg}
     * is cast from, with the walk left to it a straight line on that floor (and, for an
     * etherwarp, the target in sight).
     */
    private boolean walkingIn(LocalPlayer player) {
        Warps.Hop h = hops.get(leg);
        Vec3 at = player.position();
        double y = h.from().y() + origin.getY();
        double straight = Math.hypot(h.from().x() + 0.5 + origin.getX() - at.x,
                h.from().z() + 0.5 + origin.getZ() - at.z);
        if (!player.onGround() || Math.abs(at.y - y) > 1e-3 || straight > WALK_IN) {
            return false;
        }
        Follower f = journey.executor().follower();
        if (f.length() - f.progress() > straight + 0.3 || Math.abs(f.offset()) > 0.3) {
            return false;
        }
        List<ExecutionPlan.Node> nodes = journey.plan().nodes();
        for (int i = Math.max(0, f.node()); i < nodes.size(); i++) {
            if (nodes.get(i).cell().y() != h.from().y()) {
                return false;
            }
        }
        if (h.transmit()) {
            return true;
        }
        // An etherwarp is cast from wherever sneaking brings the player to a stop: from
        // here, and from as far on as the slide goes, the target must be in sight.
        BlockPos block = new BlockPos(h.to().x() + origin.getX(), h.to().y() - 1 + origin.getY(),
                h.to().z() + origin.getZ());
        Vec3 aim = new Vec3(h.aimX() + origin.getX(), h.aimY() + origin.getY(),
                h.aimZ() + origin.getZ());
        Vec3 v = player.getDeltaMovement();
        Vec3 eye = at.add(0, 1.27, 0);
        Vec3 slid = eye.add(v.x * 1.5, 0, v.z * 1.5);
        Minecraft client = Minecraft.getInstance();
        return WarpCast.reaches(client, eye, aim, block) && WarpCast.reaches(client, slid, aim,
                block);
    }

    /** Starts casting hop {@link #leg}, standing where it's cast from. */
    private void castHop(LocalPlayer player) {
        Warps.Hop h = hops.get(leg);
        if (h.transmit()) {
            cast = new TransmitCast(controller, new BlockPos(h.to().x() + origin.getX(),
                    h.to().y() + origin.getY(), h.to().z() + origin.getZ()), h.yaws(),
                    h.pitches(), after(leg), aimer(h), new Vec3(h.from().x() + 0.5 + origin.getX(),
                            h.from().y() + origin.getY(), h.from().z() + 0.5 + origin.getZ()),
                    leg > 0 && legs.get(leg) == null && hops.get(leg - 1).transmit());
            return;
        }
        BlockPos block = new BlockPos(h.to().x() + origin.getX(), h.to().y() - 1 + origin.getY(),
                h.to().z() + origin.getZ());
        Vec3 aim = new Vec3(h.aimX() + origin.getX(), h.aimY() + origin.getY(),
                h.aimZ() + origin.getZ());
        cast = new WarpCast(controller, block, aim, after(leg));
    }

    /**
     * Checks transmission {@code h}'s casts on the map the route was planned on: each must put
     * the feet in the block the route's does. Null if that isn't known.
     */
    private TransmitCast.Aimer aimer(Warps.Hop h) {
        if (hopBlocks == null || h.through().size() < h.chain()) {
            return null;
        }
        astar.pathing.ArrayBlockView world = hopBlocks;
        BlockPos o = origin;
        List<BlockPoint> through = h.through();
        double range = astar.pathing.TransmitHops.DEFAULT_RANGE;
        return new TransmitCast.Aimer() {
            @Override
            public boolean lands(double[][] feet, float yaw, float pitch, int k, boolean air) {
                if (air) {
                    // Mid-air the cast is checked as it is: from these feet, at this view.
                    for (double[] f : local(feet)) {
                        long got = astar.pathing.TransmitHops.cellOf(world, f[0], f[1], f[2],
                                yaw, pitch, range);
                        if (got != through.get(k).pack()) {
                            return false;
                        }
                    }
                    return true;
                }
                return astar.pathing.TransmitHops.castsTo(world, local(feet), yaw, pitch, range,
                        through.get(k).pack(), 0, air);
            }

            @Override
            public float[] view(double[][] feet, float yaw, float pitch, int k, boolean air) {
                float[] got = astar.pathing.TransmitHops.aimFrom(world, local(feet), yaw, pitch,
                        range, through.get(k).pack(), 0, air);
                if (got == null && Boolean.getBoolean("astar.trace.casts")) {
                    double[] f = local(feet)[0];
                    long c = astar.pathing.TransmitHops.cellOf(world, f[0], f[1], f[2], yaw,
                            pitch, range);
                    System.out.printf(java.util.Locale.ROOT, "[astar] transmission cast %d: no view from feet %.2f %.2f"
                            + " %.2f view %.1f %.1f lands %s, not %s (last %s)%n", k, f[0], f[1],
                            f[2], yaw, pitch, c == astar.pathing.TransmitHops.UNKNOWN ? "unknown"
                                    : astar.core.Pos.toString(c), through.get(k),
                            k > 0 ? through.get(k - 1) : "-");
                }
                return got;
            }

            @Override
            public float[] from(double[][] feet, float pitch, int k) {
                double[][] here = local(feet);
                BlockPoint to = through.get(k);
                double dx = to.x() + 0.5 - here[0][0], dz = to.z() + 0.5 - here[0][2];
                if (dx * dx + dz * dz > (range + 0.5) * (range + 0.5)) {
                    return null;
                }
                float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                return astar.pathing.TransmitHops.aimFrom(world, here, yaw, pitch, range,
                        to.pack(), 0, false);
            }

            private double[][] local(double[][] feet) {
                double[][] out = new double[feet.length][];
                for (int i = 0; i < feet.length; i++) {
                    out[i] = new double[] {feet[i][0] - o.getX(), feet[i][1] - o.getY(),
                            feet[i][2] - o.getZ()};
                }
                return out;
            }
        };
    }

    /**
     * The view to turn toward once hop {@code k} is clicked: onto the next hop if it's cast
     * where this one lands, else along the start of the walk that follows; null at the end.
     */
    private float[] after(int k) {
        BlockPoint land = hops.get(k).to();
        if (k + 1 >= legs.size()) {
            return null;
        }
        Journey.Prepared walk = legs.get(k + 1);
        if (walk == null) {
            if (k + 1 >= hops.size()) {
                return null;
            }
            Warps.Hop next = hops.get(k + 1);
            if (next.transmit()) {
                return new float[] {next.yaw(), next.pitch()};
            }
            return view(land.x() + 0.5, land.y() + 1.54, land.z() + 0.5, next.aimX(),
                    next.aimY(), next.aimZ());
        }
        List<PathStep> path = walk.path();
        BlockPoint ahead = path.get(Math.min(4, path.size() - 1)).pos();
        float[] v = view(land.x() + 0.5, land.y() + 1.62, land.z() + 0.5, ahead.x() + 0.5,
                ahead.y() + 1.62, ahead.z() + 0.5);
        return new float[] {v[0], 10};
    }

    /** {yaw, pitch} looking from one point to another. */
    private static float[] view(double fx, double fy, double fz, double tx, double ty,
            double tz) {
        double dx = tx - fx, dy = ty - fy, dz = tz - fz;
        return new float[] {(float) Math.toDegrees(Math.atan2(-dx, dz)),
                (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)))};
    }

    /** Plans again from where the player is, after a pause. */
    void resume(Minecraft client) {
        if (state != State.PAUSED) {
            return;
        }
        plan(client);
    }

    /** After the game's tick: lets the frames until the next one sweep across its turn. */
    void blendCamera(Minecraft client) {
        if (state == State.DRIVING && controller != null && client.player != null) {
            controller.blend(client.player);
        }
    }

    /** Ends it, giving the controls back. */
    void cancel(Minecraft client, String why) {
        if (client.player != null && input != null && client.player.input == input) {
            release(client.player, client.options);
        }
        stop(why);
    }

    /** Whether the player pressed a movement key or turned the camera since the last tick. */
    private boolean touched(Options options, LocalPlayer player) {
        for (KeyMapping k : new KeyMapping[] {options.keyUp, options.keyDown, options.keyLeft,
                options.keyRight, options.keyJump, options.keyShift, options.keySprint}) {
            if (k.isDown()) {
                return true;
            }
        }
        return controller.turned(player);
    }

    private void pause(LocalPlayer player, Options options) {
        release(player, options);
        state = State.PAUSED;
    }

    private void release(LocalPlayer player, Options options) {
        if (player.input == input) {
            player.input = new KeyboardInput(options);
        }
    }

    private void finish(String what) {
        state = State.DONE;
        outcome = what;
        say.accept(Component.literal("/goto: " + what + "."));
    }

    private void stop(String why) {
        state = State.DONE;
        outcome = "stopped: " + why;
        say.accept(Component.literal("/goto stopped: " + why + ".").withStyle(ChatFormatting.RED));
    }

    /**
     * The executor's seam to the real player: it reads the player at the start of a tick,
     * in the plan's coordinates, and sets the keys and camera the game then plays the tick with.
     */
    static final class GameController implements PlayerController {

        private final LocalPlayer player;
        private final ScriptedInput input;
        private final BlockPos origin;
        private float yaw = Float.NaN;
        private float pitch = Float.NaN;
        private float fromYaw;
        private float fromPitch;
        private boolean turnedThisTick;

        GameController(LocalPlayer player, ScriptedInput input, BlockPos origin) {
            this.player = player;
            this.input = input;
            this.origin = origin;
        }

        @Override
        public Observation observe() {
            Vec3 p = player.position();
            Vec3 v = player.getDeltaMovement();
            return new Observation(p.x - origin.getX(), p.y - origin.getY(),
                    p.z - origin.getZ(), v.x, v.y, v.z, player.getYRot(), player.getXRot(),
                    player.onGround(), player.horizontalCollision, player.isSprinting(),
                    player.minorHorizontalCollision, player.fallDistance);
        }

        @Override
        public double movementSpeed() {
            // The attribute carries sprinting's 30% while sprinting; the simulator adds its own.
            double speed = player.getAttributeValue(Attributes.MOVEMENT_SPEED);
            return player.isSprinting() ? speed / 1.3 : speed;
        }

        @Override
        public void tick(Keys keys, float yaw, float pitch) {
            input.set(keys);
            fromYaw = player.getYRot();
            fromPitch = player.getXRot();
            turnedThisTick = true;
            player.setYRot(yaw);
            player.setXRot(pitch);
            this.yaw = yaw;
            this.pitch = pitch;
        }

        /**
         * After the game's tick: sets where the camera was before this tick's turn as where it
         * turns from, so the frames drawn until the next tick sweep across the turn instead of
         * showing it all on the first frame. The game draws the view between the two, as it
         * does for everything else that moves each tick; the tick itself already stepped the
         * player with the new yaw.
         */
        void blend(LocalPlayer player) {
            if (turnedThisTick && player == this.player) {
                player.yRotO = fromYaw;
                player.xRotO = fromPitch;
            }
            turnedThisTick = false;
        }

        /** Whether the camera moved since this controller last turned it: the mouse. */
        boolean turned(LocalPlayer player) {
            return !Float.isNaN(yaw) && (Math.abs(player.getYRot() - yaw) > TURNED
                    || Math.abs(player.getXRot() - pitch) > TURNED);
        }
    }
}
