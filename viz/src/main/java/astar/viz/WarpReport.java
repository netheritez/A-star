package astar.viz;

import astar.core.AStarSearch;
import astar.core.BlockPoint;
import astar.core.CostModel;
import astar.core.DefaultCostModel;
import astar.core.Heuristic;
import astar.core.MoveGraph;
import astar.core.MoveType;
import astar.core.PathStep;
import astar.core.SearchListener;
import astar.core.SearchResult;
import astar.mcworld.ImportedWorld;
import astar.pathing.ArrayBlockView;
import astar.pathing.HopGraph;
import astar.pathing.TransmitHops;
import astar.pathing.Flights;
import astar.pathing.NavGraph;
import astar.pathing.WarpHops;
import astar.pathing.WorldPathfinder;
import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Set;
import java.util.List;

/**
 * What teleport hops do for routes: {@code route --warp} builds the sure etherwarp hops ({@link
 * WarpHops}) over the map's move graph, {@code route --transmit} the sure Instant Transmission
 * hops ({@link TransmitHops}), both with both flags; then finds the route walking only and with
 * hops, over the same graph with the same costs, prints both and every hop the route takes, and
 * draws the route with hops from above (overview-warp.png).
 */
final class WarpReport {
    private WarpReport() {}

    /** Sprinting speed in blocks per second, to put costs (blocks walked) in seconds. */
    private static final double SPRINT = 5.612;

    record Options(double range, double cost, double minLength, int spacing, double turn,
            double walkTurn, boolean ether, boolean transmit, double transmitMin, double detour,
            double etherLength, double walkDetour) {}

    static void run(WorldPathfinder finder, DefaultCostModel moveCosts, ArrayBlockView blocks,
            ImportedWorld imported, BlockPoint start, BlockPoint goal, Options opt,
            BlockPoint[][] pairs, Path out) throws IOException {
        NavGraph walking = finder.graphCovering(start.pack());
        if (walking == null || !walking.covers(goal.pack())) {
            System.out.println("  warp: the move graph doesn't cover the start and goal");
            return;
        }
        List<HopGraph.Moves> sets = new ArrayList<>();
        WarpHops hops = null;
        TransmitHops casts = null;
        if (opt.ether()) {
            hops = WarpHops.build(walking, blocks, opt.range(), opt.cost(), opt.minLength(),
                    opt.spacing());
            sets.add(hops.moves(Set.of()));
            System.out.printf("%nEtherwarp hops (range %s, cost %s blocks walked + %s per 45"
                    + " degrees of turn, at least %s blocks long, anchors every %d):%n",
                    num(opt.range()), num(opt.cost()), num(opt.turn()), num(opt.minLength()),
                    opt.spacing());
            System.out.printf("  %,d anchors, %,d sure hops (%.1f per anchor), %,d rays, built"
                    + " in %.0f ms%n", hops.anchors(), hops.count(),
                    hops.count() / (double) Math.max(1, hops.anchors()), hops.raysCast(),
                    hops.buildMs());
        }
        if (opt.transmit()) {
            casts = TransmitHops.build(walking, blocks, TransmitHops.DEFAULT_RANGE,
                    TransmitHops.DEFAULT_COST, opt.transmitMin(), TransmitHops.DEFAULT_SPACING);
            sets.add(casts.moves(Set.of()));
            System.out.printf("%nInstant Transmission hops (range %s, cost %s blocks walked plus"
                    + " the fall, %d directions x %d pitches, anchors every %d and every"
                    + " landing):%n", num(TransmitHops.DEFAULT_RANGE),
                    num(TransmitHops.DEFAULT_COST), 32, 8, TransmitHops.DEFAULT_SPACING);
            System.out.printf("  %,d spots cast from, %,d sure casts (%.1f per spot), built in"
                    + " %.0f ms%n", casts.anchors(), casts.count(),
                    casts.count() / (double) Math.max(1, casts.anchors()), casts.buildMs());
        }
        MoveGraph withHops = HopGraph.of(walking, sets);

        // The graphs hold every move's price; these only add the turns. Walking alone is charged
        // the pathfinder's turn cost; hops are charged for swinging the view onto them and off.
        CostModel walkTurns = WarpHops.turns(opt.walkTurn(), opt.walkTurn());
        CostModel costs = costs(opt, goal);
        Heuristic walkH = finder.heuristic();
        double rate = HopGraph.rate(walking, sets, walkFloor(moveCosts));
        Heuristic warpH = HopGraph.heuristic(rate);
        System.out.printf("  heuristic: %.3f per block (walking alone %.3f)%n", rate,
                walkFloor(moveCosts));
        Timed walk = search(start, goal, walking, walkTurns, walkH);
        Timed warp = search(start, goal, withHops, costs, warpH);
        if (!walk.result().found() || !warp.result().found()) {
            System.out.println("  no route over the move graph");
            return;
        }
        double wc = walk.result().cost(), hc = time(warp.result().path(), warp.result().cost(),
                goal, opt);
        System.out.printf("  walking only: cost %.1f (about %.1f s sprinting), %,d expanded, %.1f"
                + " ms%n", wc, wc / SPRINT, walk.result().expanded(), walk.ms());
        List<PathStep> path = warp.result().path();
        java.util.Map<Integer, Flights.Flight> flights = java.util.Map.of();
        if (casts != null) {
            long t = System.nanoTime();
            Flights.Result air = Flights.straighten(blocks, withHops, path, casts.range(),
                    opt.detour(), opt.etherLength(), opt.walkDetour(), Set.of());
            double ms = (System.nanoTime() - t) / 1e6;
            int flown = air.flights().values().stream().mapToInt(Flights.Flight::casts).sum();
            System.out.printf("  air lines: %d flights (%d casts) from %d spots tried, %.1f"
                    + " saved, %.0f ms%n", air.flights().size(), flown, air.tried(),
                    air.saved(), ms);
            System.out.printf("  time without turns: %.1f s before, %.1f s with air lines%n",
                    Flights.time(withHops, path, java.util.Map.of()) / SPRINT,
                    Flights.time(withHops, air.path(), air.flights()) / SPRINT);
            path = air.path();
            flights = air.flights();
            hc = Flights.time(withHops, path, flights);
        }
        long ethers = path.stream().filter(s -> s.via() == MoveType.WARP).count();
        long its = path.stream().filter(s -> s.via() == MoveType.TRANSMIT).count();
        System.out.printf("  with hops:    cost %.1f (about %.1f s), %d etherwarps, %d"
                + " transmissions, %,d expanded, %.1f ms: %.0f%% quicker%n", hc, hc / SPRINT,
                ethers, its, warp.result().expanded(), warp.ms(), 100 * (1 - hc / wc));
        double hopLen = 0, detour = 0;
        for (int i = 1; i < path.size(); i++) {
            MoveType t = path.get(i).via();
            if (t == MoveType.WARP || t == MoveType.TRANSMIT) {
                long a = path.get(i - 1).pos().pack(), b = path.get(i).pos().pack();
                double len = HopGraph.extra(a, b, MoveType.TRANSMIT, a, 1, 0) / 2;
                hopLen += len;
                detour += HopGraph.extra(a, b, MoveType.TRANSMIT, goal.pack(), 1, 0);
            }
        }
        System.out.printf("  teleports cover %.0f blocks, %.0f of them not toward the goal%n",
                hopLen, detour);
        BlockPoint o = imported.origin();
        java.util.Map<Integer, List<BlockPoint>> hopCells = new java.util.HashMap<>();
        for (int i = 1; i < path.size(); i++) {
            PathStep s = path.get(i);
            BlockPoint a = path.get(i - 1).pos();
            if (s.via() == MoveType.WARP) {
                hopCells.put(i, List.of(s.pos()));
            } else if (flights.containsKey(i)) {
                List<BlockPoint> cells = new ArrayList<>();
                for (long p : flights.get(i).through()) {
                    cells.add(new BlockPoint(astar.core.Pos.x(p), astar.core.Pos.y(p), astar.core.Pos.z(p)));
                }
                hopCells.put(i, cells);
            } else if (s.via() == MoveType.TRANSMIT) {
                int k = casts.index(a.pack(), s.pos().pack());
                float[] view = casts.view(k);
                List<BlockPoint> cells = new ArrayList<>();
                for (long p : TransmitHops.cells(blocks, a.pack(), view[0], view[1],
                        casts.chain(k), casts.range())) {
                    cells.add(new BlockPoint(astar.core.Pos.x(p), astar.core.Pos.y(p), astar.core.Pos.z(p)));
                }
                hopCells.put(i, cells);
            }
        }
        Path map = out.resolve("teleports.png");
        ImageIO.write(RouteImages.teleports(blocks, path, hopCells, o, String.format(
                "%s: route with teleports, %d etherwarps and %d Instant Transmissions, about %.1f s",
                imported.name(), ethers, its, hc / SPRINT), 1600), "png", map.toFile());
        System.out.println("  wrote " + map);
        Path image = out.resolve("overview-warp.png");
        ImageIO.write(RouteImages.overview(blocks, path, List.of(), o,
                imported.name() + ": route with teleport hops (straight jumps), from above", 1400),
                "png", image.toFile());
        System.out.println("  wrote " + image);
        for (int i = 1; i < path.size(); i++) {
            PathStep s = path.get(i);
            BlockPoint a = path.get(i - 1).pos(), b = s.pos();
            double len = Math.sqrt(sq(b.x() - a.x()) + sq(b.y() - a.y()) + sq(b.z() - a.z()));
            if (s.via() == MoveType.WARP) {
                float[] aim = hops.aim(a.pack(), b.pack());
                double ex = a.x() + 0.5, ey = a.y() + 1.54, ez = a.z() + 0.5;
                double dx = aim[0] - ex, dy = aim[1] - ey, dz = aim[2] - ez;
                double yaw = Math.toDegrees(Math.atan2(-dx, dz));
                double pitch = -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
                System.out.printf("    etherwarp %s -> %s, %.1f blocks, aim at %.2f, %.2f, %.2f"
                        + " (yaw %.1f, pitch %.1f)%n", imported.toWorld(a), imported.toWorld(b),
                        len, aim[0] + o.x(), aim[1] + o.y(), aim[2] + o.z(), yaw, pitch);
            } else if (flights.containsKey(i)) {
                Flights.Flight f = flights.get(i);
                StringBuilder views = new StringBuilder();
                for (int c = 0; c < f.casts(); c++) {
                    views.append(c == 0 ? "" : ", ").append(String.format("%.0f/%.0f",
                            f.yaw()[c], f.pitch()[c]));
                }
                System.out.printf("    air line %s -> %s, %.1f blocks, %d casts (yaw/pitch %s)%n",
                        imported.toWorld(a), imported.toWorld(b), len, f.casts(), views);
            } else if (s.via() == MoveType.TRANSMIT) {
                int k = casts.index(a.pack(), b.pack());
                float[] view = casts.view(k);
                System.out.printf("    transmission %s -> %s, %.1f blocks (yaw %.1f, pitch"
                        + " %.1f)%s%n", imported.toWorld(a), imported.toWorld(b), len, view[0],
                        view[1], casts.chain(k) > 1 ? ", " + casts.chain(k) + " casts chained"
                        + " in the air" : "");
            }
        }

        if (pairs == null || pairs.length == 0) {
            return;
        }
        int n = 0;
        double[] walkCost = new double[pairs.length], warpCost = new double[pairs.length];
        double[] walkMs = new double[pairs.length], warpMs = new double[pairs.length];
        double[] saved = new double[pairs.length];
        double[] flightMs = new double[pairs.length];
        int[] hopCount = new int[pairs.length];
        // Warm up, so the first routes aren't timed with the JIT still compiling.
        for (int i = 0; i < Math.min(20, pairs.length); i++) {
            if (walking.covers(pairs[i][0].pack()) && walking.covers(pairs[i][1].pack())) {
                search(pairs[i][0], pairs[i][1], withHops, costs(opt, pairs[i][1]), warpH);
            }
        }
        Flights.STATS.reset();
        long digest = 1;
        for (BlockPoint[] p : pairs) {
            if (!walking.covers(p[0].pack()) || !walking.covers(p[1].pack())) {
                continue;
            }
            Timed a = search(p[0], p[1], walking, walkTurns, walkH);
            Timed b = search(p[0], p[1], withHops, costs(opt, p[1]), warpH);
            if (!a.result().found() || !b.result().found()) {
                continue;
            }
            walkCost[n] = a.result().cost();
            warpCost[n] = time(b.result().path(), b.result().cost(), p[1], opt);
            walkMs[n] = a.ms();
            warpMs[n] = b.ms();
            if (casts != null) {
                long t = System.nanoTime();
                Flights.Result air = Flights.straighten(blocks, withHops, b.result().path(),
                        casts.range(), opt.detour(), opt.etherLength(), opt.walkDetour(),
                        Set.of());
                warpCost[n] = Flights.time(withHops, air.path(), air.flights());
                flightMs[n] = (System.nanoTime() - t) / 1e6;
                digest = digest(digest, air);
            }
            saved[n] = 1 - warpCost[n] / walkCost[n];
            hopCount[n] = (int) b.result().path().stream()
                    .filter(s -> s.via() == MoveType.WARP || s.via() == MoveType.TRANSMIT)
                    .count();
            n++;
        }
        if (n == 0) {
            return;
        }
        int used = (int) Arrays.stream(hopCount, 0, n).filter(h -> h > 0).count();
        if (casts != null) {
            System.out.printf("  air lines: %.0f ms median, %.0f ms worst per route, %.0f ms"
                    + " in all%n", median(flightMs, n), percentile(flightMs, n, 1),
                    Arrays.stream(flightMs, 0, n).sum());
            System.out.println("  air lines: " + Flights.STATS);
            System.out.printf("  routes fingerprint %016x (the same when the routes are)%n",
                    digest);
        }
        System.out.printf("  %d random routes: %d use hops; median %.0f%% quicker (%.0f%% at the"
                + " 90th percentile), %.1f s -> %.1f s median; search %.1f ms walking, %.1f ms"
                + " with hops (median), %.1f ms worst%n", n, used, 100 * median(saved, n),
                100 * percentile(saved, n, 0.9), median(walkCost, n) / SPRINT,
                median(warpCost, n) / SPRINT, median(walkMs, n), median(warpMs, n),
                percentile(warpMs, n, 1));
        double all = 0;
        for (int k = 0; k < n; k++) {
            all += warpCost[k];
        }
        System.out.printf("  trips with hops: %.1f s in all, %.2f s mean%n", all / SPRINT,
                all / SPRINT / Math.max(1, n));
    }

    /**
     * A route's cost without what {@link HopGraph#costs} adds to steer teleports toward the
     * goal: the time it takes, in blocks walked.
     */
    private static double time(List<PathStep> path, double cost, BlockPoint goal, Options opt) {
        for (int i = 1; i < path.size(); i++) {
            MoveType t = path.get(i).via();
            long a = path.get(i - 1).pos().pack(), b = path.get(i).pos().pack();
            if (t == MoveType.WARP || t == MoveType.TRANSMIT) {
                cost -= HopGraph.extra(a, b, t, goal.pack(), opt.detour(), opt.etherLength());
            } else {
                cost -= HopGraph.extra(a, b, t, goal.pack(), opt.walkDetour(), 0);
            }
        }
        return cost;
    }

    /** The costs of a search to {@code goal} over the graph with hops. */
    private static CostModel costs(Options opt, BlockPoint goal) {
        return HopGraph.costs(opt.walkTurn(), opt.turn(), goal.pack(), opt.detour(),
                opt.etherLength(), opt.walkDetour());
    }

    private record Timed(SearchResult result, double ms) {}

    private static Timed search(BlockPoint start, BlockPoint goal, MoveGraph graph,
            CostModel costs, Heuristic h) {
        long t = System.nanoTime();
        AStarSearch s = new AStarSearch(start, goal, graph, costs, h, SearchListener.NONE);
        s.runToEnd();
        double ms = (System.nanoTime() - t) / 1e6;
        return new Timed(s.result(), ms);
    }

    /** The least any walking move costs per block of straight-line distance it covers. */
    private static double walkFloor(DefaultCostModel c) {
        double least = Math.min(c.straightFloor(), c.diagonalFloor() / Math.sqrt(2));
        least = Math.min(least, Math.min(c.stepInPlace(), c.dropPerBlock()));
        return least / Math.sqrt(3);
    }

    private static double median(double[] v, int n) {
        return percentile(v, n, 0.5);
    }

    private static double percentile(double[] v, int n, double p) {
        double[] s = Arrays.copyOf(v, n);
        Arrays.sort(s);
        return s[(int) Math.min(n - 1, Math.floor(p * n))];
    }

    private static double sq(double v) {
        return v * v;
    }

    private static String num(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    /** Folds a route with its flights (every step, cast and view) into {@code h}. */
    private static long digest(long h, Flights.Result air) {
        for (int i = 0; i < air.path().size(); i++) {
            h = h * 31 + air.path().get(i).pos().pack();
            Flights.Flight f = air.flights().get(i);
            if (f != null) {
                for (int k = 0; k < f.casts(); k++) {
                    h = h * 31 + Float.floatToIntBits(f.yaw()[k]);
                    h = h * 31 + Float.floatToIntBits(f.pitch()[k]);
                }
            }
        }
        return h;
    }
}
