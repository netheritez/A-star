package astar.client;

import astar.core.AStarSearch;
import astar.core.BlockPoint;
import astar.core.MoveGraph;
import astar.core.MoveType;
import astar.core.PathStep;
import astar.core.SearchListener;
import astar.core.SearchResult;
import astar.core.TurnPenalty;
import astar.movement.exec.GotoPathfinder;
import astar.pathing.ArrayBlockView;
import astar.pathing.Flights;
import astar.pathing.HopGraph;
import astar.pathing.NavGraph;
import astar.pathing.TransmitHops;
import astar.pathing.WarpHops;
import astar.pathing.WorldPathfinder;
import java.util.ArrayList;
import java.util.List;

/**
 * Routes that teleport as well as walk, for {@code /goto warp}, {@code /goto it} and {@code
 * /goto aotv}: the sure etherwarp hops ({@link WarpHops}) and Instant Transmission casts
 * ({@link TransmitHops}) of the map, worked out once over the whole map's move graph and kept,
 * and a search over walking and those together. Plain Java, run off the game thread.
 */
final class Warps {
    private Warps() {}

    /** Which teleports routes use. */
    enum Mode {
        /** Etherwarp ({@code /goto warp}). */
        ETHER(true, false, "etherwarps"),
        /** Instant Transmission ({@code /goto it}). */
        TRANSMIT(false, true, "transmissions"),
        /** Both ({@code /goto aotv}). */
        BOTH(true, true, "teleports");

        final boolean ether;
        final boolean transmit;
        final String plural;

        Mode(boolean ether, boolean transmit, String plural) {
            this.ether = ether;
            this.transmit = transmit;
            this.plural = plural;
        }
    }

    /**
     * One hop of a route: from where it's cast, to where it lands. An etherwarp aims at a point
     * (aimX, aimY, aimZ); an Instant Transmission ({@code transmit}) turns the view to (yaw,
     * pitch) and lands in the air above {@code to} or on it; {@code chain} casts in a row at
     * that view, each from where the last left the player in the air (1 for a single cast).
     * {@code through} is the block each cast puts the feet in, for drawing (empty for an
     * etherwarp). {@code yaws} and {@code pitches} are each cast's view: the same for a chain at
     * one view, different for an air line ({@link Flights}), which turns between casts.
     */
    record Hop(BlockPoint from, BlockPoint to, boolean transmit, double aimX, double aimY,
            double aimZ, float yaw, float pitch, int chain, List<BlockPoint> through,
            float[] yaws, float[] pitches) {}

    /**
     * A route split where it hops: {@code legs.get(i)} is walked, then {@code hops.get(i)} is
     * cast (there's one leg more than hops). A leg of one step is no walk at all: the hop
     * before it lands where the next one is cast.
     */
    record Route(List<List<PathStep>> legs, List<Hop> hops, SearchResult result, double buildMs) {}

    private static WarpHops kept;
    private static ArrayBlockView keptWorld;
    /** Hops of {@link #kept} that failed in the game, left out of routes from then on. */
    private static final java.util.Set<Integer> banned = new java.util.HashSet<>();
    /** Spots (packed) a cast from failed in the game: no air line starts there from then on. */
    private static final java.util.Set<Long> bannedFlights = new java.util.HashSet<>();
    private static TransmitHops keptCasts;
    private static ArrayBlockView keptCastsWorld;
    /** Casts of {@link #keptCasts} that failed in the game. */
    private static final java.util.Set<Integer> bannedCasts = new java.util.HashSet<>();

    /** Where a whole map's hops are kept on disk, and the map they're for. */
    private static java.nio.file.Path hopDir;
    private static ArrayBlockView hopDirWorld;

    /**
     * Hops for {@code world} are kept in files in {@code dir} (beside its saved move graph),
     * read back instead of built when the world and graph haven't changed.
     */
    static synchronized void keepIn(java.nio.file.Path dir, ArrayBlockView world) {
        hopDir = dir;
        hopDirWorld = world;
    }

    private interface Reader<T> {
        T read(java.io.DataInputStream in) throws java.io.IOException;
    }

    private interface Writer {
        void write(java.io.DataOutputStream out) throws java.io.IOException;
    }

    /** Hops read from file {@code name} for {@code world}; null if there are none that fit. */
    private static <T> T load(ArrayBlockView world, String name, Reader<T> reader) {
        if (hopDir == null || hopDirWorld != world) {
            return null;
        }
        java.nio.file.Path file = hopDir.resolve(name);
        if (!java.nio.file.Files.exists(file)) {
            return null;
        }
        try (java.io.DataInputStream in = new java.io.DataInputStream(
                new java.io.BufferedInputStream(java.nio.file.Files.newInputStream(file),
                        1 << 16))) {
            return reader.read(in);
        } catch (java.io.IOException | RuntimeException e) {
            System.out.println("[astar] couldn't read " + file + ": " + e);
            return null;
        }
    }

    /** Writes hops just built for {@code world} to file {@code name}, if it has a place. */
    private static void save(ArrayBlockView world, String name, Writer writer) {
        if (hopDir == null || hopDirWorld != world) {
            return;
        }
        java.nio.file.Path file = hopDir.resolve(name);
        java.nio.file.Path tmp = hopDir.resolve(name + ".tmp");
        try {
            java.nio.file.Files.createDirectories(hopDir);
            try (java.io.DataOutputStream out = new java.io.DataOutputStream(
                    new java.io.BufferedOutputStream(java.nio.file.Files.newOutputStream(tmp),
                            1 << 16))) {
                writer.write(out);
            }
            java.nio.file.Files.move(tmp, file,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.io.IOException e) {
            System.out.println("[astar] couldn't save " + file + ": " + e);
        }
    }

    /**
     * The hops over {@code graph}: the kept ones if they were built on it or on a graph it
     * patched, else built now (a few seconds for a whole map).
     */
    static synchronized WarpHops hops(ArrayBlockView world, NavGraph graph) {
        if (kept != null && keptWorld == world) {
            WarpHops onto = kept.onto(graph);
            if (onto != null) {
                kept = onto;
                return onto;
            }
        }
        kept = load(world, "warps.bin", in -> WarpHops.read(in, world, graph));
        if (kept == null) {
            kept = WarpHops.build(graph, world);
            WarpHops built = kept;
            save(world, "warps.bin", out -> built.write(out, world));
        }
        keptWorld = world;
        banned.clear();
        System.out.printf("[astar] etherwarp hops: %,d anchors, %,d sure hops, %s in %.0f ms%n",
                kept.anchors(), kept.count(), kept.raysCast() == 0 ? "read" : "built",
                kept.buildMs());
        return kept;
    }

    /**
     * The Instant Transmission casts over {@code graph}: the kept ones if they were built on it
     * or on a graph it patched, else built now (several seconds for a whole map).
     */
    static synchronized TransmitHops casts(ArrayBlockView world, NavGraph graph) {
        if (keptCasts != null && keptCastsWorld == world) {
            TransmitHops onto = keptCasts.onto(graph);
            if (onto != null) {
                keptCasts = onto;
                return onto;
            }
        }
        keptCasts = load(world, "casts.bin", in -> TransmitHops.read(in, world, graph));
        if (keptCasts == null) {
            keptCasts = TransmitHops.build(graph, world);
            TransmitHops built = keptCasts;
            save(world, "casts.bin", out -> built.write(out, world));
        }
        keptCastsWorld = world;
        bannedCasts.clear();
        bannedFlights.clear();
        System.out.printf("[astar] instant transmission: %,d spots, %,d sure casts, ready in"
                + " %.0f ms%n", keptCasts.anchors(), keptCasts.count(), keptCasts.buildMs());
        return keptCasts;
    }

    /** Whether the hops for this world are built (so a route needn't wait for them). */
    static synchronized boolean ready(ArrayBlockView world, Mode mode) {
        return (!mode.ether || kept != null && keptWorld == world)
                && (!mode.transmit || keptCasts != null && keptCastsWorld == world);
    }

    /** The cheapest route from {@code from} to {@code to} walking and teleporting. */
    static Route route(ArrayBlockView world, WorldPathfinder base, BlockPoint from,
            BlockPoint to, Mode mode) {
        NavGraph graph = base.graphCovering(from.pack());
        if (!graph.covers(to.pack())) {
            return new Route(List.of(), List.of(), SearchResult.noPath(), 0);
        }
        boolean had = ready(world, mode);
        long t0 = System.nanoTime();
        WarpHops hops = mode.ether ? hops(world, graph) : null;
        TransmitHops casts = mode.transmit ? casts(world, graph) : null;
        double buildMs = had ? 0 : (System.nanoTime() - t0) / 1e6;
        Asked asked;
        synchronized (Warps.class) {
            asked = new Asked(world, world.version(), graph, hops, casts, to, mode,
                    java.util.Set.copyOf(banned), java.util.Set.copyOf(bannedCasts),
                    java.util.Set.copyOf(bannedFlights));
        }
        Route again = recent(asked, from);
        if (again != null) {
            System.out.printf("[astar] same route as a recent plan from %s on, not planned"
                    + " again%n", from);
            return again;
        }
        List<HopGraph.Moves> sets = new ArrayList<>();
        if (hops != null) {
            sets.add(hops.moves(asked.banned()));
        }
        if (casts != null) {
            sets.add(casts.moves(asked.bannedCasts()));
        }
        MoveGraph moves = HopGraph.of(graph, sets);
        AStarSearch search = new AStarSearch(from, to, moves,
                HopGraph.costs(TurnPenalty.DEFAULT_PER_TURN, WarpHops.DEFAULT_TURN, to.pack(),
                        HopGraph.DEFAULT_DETOUR, HopGraph.DEFAULT_ETHER_LENGTH,
                        HopGraph.DEFAULT_WALK_DETOUR),
                HopGraph.heuristic(rate(graph, sets)), SearchListener.NONE);
        search.runToEnd();
        SearchResult r = search.result();
        if (!r.found()) {
            return new Route(List.of(), List.of(), r, buildMs);
        }
        List<PathStep> path = r.path();
        java.util.Map<Integer, Flights.Flight> flights = java.util.Map.of();
        if (casts != null) {
            java.util.Set<Long> noFlights = asked.bannedFlights();
            long t1 = System.nanoTime();
            Flights.Result air = Flights.straighten(world, moves, path, casts.range(),
                    HopGraph.DEFAULT_DETOUR, HopGraph.DEFAULT_ETHER_LENGTH,
                    HopGraph.DEFAULT_WALK_DETOUR, noFlights);
            System.out.printf("[astar] air lines: %d flights, %.1f blocks saved, %.0f ms%n",
                    air.flights().size(), air.saved(), (System.nanoTime() - t1) / 1e6);
            path = air.path();
            flights = air.flights();
        }
        List<List<PathStep>> legs = new ArrayList<>();
        List<Hop> cast = new ArrayList<>();
        List<PathStep> leg = new ArrayList<>();
        for (int i = 0; i < path.size(); i++) {
            PathStep s = path.get(i);
            if (s.via() == MoveType.WARP || s.via() == MoveType.TRANSMIT) {
                BlockPoint a = leg.get(leg.size() - 1).pos();
                Flights.Flight f = flights.get(i);
                if (s.via() == MoveType.WARP) {
                    float[] aim = hops.aim(a.pack(), s.pos().pack());
                    cast.add(new Hop(a, s.pos(), false, aim[0], aim[1], aim[2], 0, 0, 1,
                            List.of(), new float[0], new float[0]));
                } else if (f != null) {
                    List<BlockPoint> through = new ArrayList<>();
                    for (long p : f.through()) {
                        through.add(new BlockPoint(astar.core.Pos.x(p), astar.core.Pos.y(p),
                                astar.core.Pos.z(p)));
                    }
                    cast.add(new Hop(a, s.pos(), true, 0, 0, 0, f.yaw()[0], f.pitch()[0],
                            f.casts(), through, f.yaw(), f.pitch()));
                } else {
                    int k = casts.index(a.pack(), s.pos().pack());
                    float[] view = casts.view(k);
                    List<BlockPoint> through = new ArrayList<>();
                    for (long p : TransmitHops.cells(world, a.pack(), view[0], view[1],
                            casts.chain(k), casts.range())) {
                        through.add(new BlockPoint(astar.core.Pos.x(p), astar.core.Pos.y(p),
                                astar.core.Pos.z(p)));
                    }
                    float[] yaws = new float[casts.chain(k)];
                    float[] pitches = new float[yaws.length];
                    java.util.Arrays.fill(yaws, view[0]);
                    java.util.Arrays.fill(pitches, view[1]);
                    cast.add(new Hop(a, s.pos(), true, 0, 0, 0, view[0], view[1],
                            casts.chain(k), through, yaws, pitches));
                }
                legs.add(leg);
                leg = new ArrayList<>();
                leg.add(new PathStep(s.pos(), null));
            } else {
                leg.add(s);
            }
        }
        legs.add(leg);
        Route route = new Route(legs, cast, r, buildMs);
        keep(asked, route);
        return route;
    }

    /**
     * What a route was planned over and to: the same map (unchanged since), graph and kept
     * hops, goal, mode and hops left out. Another plan asked the same from a spot the route
     * goes through is the rest of that route.
     */
    private record Asked(ArrayBlockView world, int version, NavGraph graph, WarpHops hops,
            TransmitHops casts, BlockPoint to, Mode mode, java.util.Set<Integer> banned,
            java.util.Set<Integer> bannedCasts, java.util.Set<Long> bannedFlights) {

        boolean same(Asked o) {
            return world == o.world && version == o.version && graph == o.graph
                    && hops == o.hops && casts == o.casts && to.equals(o.to) && mode == o.mode
                    && banned.equals(o.banned) && bannedCasts.equals(o.bannedCasts)
                    && bannedFlights.equals(o.bannedFlights);
        }
    }

    private record Planned(Asked asked, Route route) {}

    /** Routes planned lately, newest first. */
    private static final java.util.Deque<Planned> RECENT = new java.util.ArrayDeque<>();
    private static final int RECENT_MOST = 8;

    private static synchronized void keep(Asked asked, Route route) {
        RECENT.addFirst(new Planned(asked, route));
        while (RECENT.size() > RECENT_MOST) {
            RECENT.removeLast();
        }
    }

    /**
     * The rest of a route planned lately for the same {@code asked}, from where it goes
     * through {@code from} (a spot walked to or teleported to on it); null if none does.
     */
    private static synchronized Route recent(Asked asked, BlockPoint from) {
        for (Planned p : RECENT) {
            if (!p.asked().same(asked)) {
                continue;
            }
            List<List<PathStep>> legs = p.route().legs();
            for (int l = 0; l < legs.size(); l++) {
                List<PathStep> leg = legs.get(l);
                for (int k = 0; k < leg.size(); k++) {
                    if (!leg.get(k).pos().equals(from)) {
                        continue;
                    }
                    List<List<PathStep>> rest = new ArrayList<>();
                    List<PathStep> first = new ArrayList<>(leg.subList(k, leg.size()));
                    first.set(0, new PathStep(from, null));
                    rest.add(first);
                    rest.addAll(legs.subList(l + 1, legs.size()));
                    List<PathStep> steps = new ArrayList<>();
                    for (List<PathStep> r : rest) {
                        steps.addAll(steps.isEmpty() ? r : r.subList(1, r.size()));
                    }
                    SearchResult was = p.route().result();
                    return new Route(rest, p.route().hops().subList(l, p.route().hops().size()),
                            new SearchResult(SearchResult.Status.FOUND, steps, was.cost(),
                                    java.util.Set.of(), java.util.Set.of(), 0), 0);
                }
            }
        }
        return null;
    }

    /**
     * The heuristic's rate for these teleports, worked out once per set of kept hops (it's a
     * pass over all of them) and kept.
     */
    private static double rate(NavGraph graph, List<HopGraph.Moves> sets) {
        List<Object> key = new ArrayList<>();
        for (HopGraph.Moves m : sets) {
            key.add(m.from());
        }
        synchronized (Warps.class) {
            if (!key.equals(rateKey)) {
                keptRate = HopGraph.rate(graph, sets, walkFloor());
                rateKey = key;
            }
            return keptRate;
        }
    }

    private static List<Object> rateKey;
    private static double keptRate;

    /**
     * Leaves the hop from {@code from} to {@code to} (grid cells) out of routes from now on:
     * it didn't work in the game.
     */
    static synchronized void ban(Hop hop) {
        if (hop.transmit()) {
            // No air line from there either.
            bannedFlights.add(hop.from().pack());
            if (keptCasts != null) {
                int k = keptCasts.index(hop.from().pack(), hop.to().pack());
                if (k >= 0) {
                    bannedCasts.add(k);
                }
            }
        } else if (kept != null) {
            int k = kept.index(hop.from().pack(), hop.to().pack());
            if (k >= 0) {
                banned.add(k);
            }
        }
    }

    /** The least a /goto walking move costs per block of straight-line distance. */
    private static double walkFloor() {
        var c = GotoPathfinder.COSTS;
        double least = Math.min(c.straightFloor(), c.diagonalFloor() / Math.sqrt(2));
        least = Math.min(least, Math.min(c.stepInPlace(), c.dropPerBlock()));
        return least / Math.sqrt(3);
    }
}
