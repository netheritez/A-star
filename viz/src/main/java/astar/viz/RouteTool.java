package astar.viz;

import astar.core.AStarSearch;
import astar.core.BlockPoint;
import astar.core.CostModel;
import astar.core.DefaultCostModel;
import astar.core.Heuristic;
import astar.core.Heuristics;
import astar.core.MoveType;
import astar.core.PathStep;
import astar.core.Pos;
import astar.core.SearchListener;
import astar.core.SearchResult;
import astar.core.TurnPenalty;
import astar.mcworld.ImportedWorld;
import astar.movement.Scenario;
import astar.movement.exec.Executor;
import astar.movement.exec.GotoPathfinder;
import astar.movement.exec.GridRouter;
import astar.movement.exec.Journey;
import astar.movement.exec.SimController;
import astar.movement.sim.SimulatedPlayer;
import astar.movement.exec.SimRun;
import astar.movement.plan.ExecutionPlan;
import astar.movement.plan.PlanBuilder;
import astar.movement.plan.StraightRoute;
import astar.movement.sim.SimWorld;
import astar.mcworld.Island;
import astar.mcworld.WorldImporter;
import astar.pathing.ArrayBlockView;
import astar.pathing.AutoLandmarks;
import astar.pathing.KeptPathfinder;
import astar.pathing.BlockView;
import astar.pathing.ClusterLayout;
import astar.pathing.EntityProfile;
import astar.pathing.HierarchicalPathfinder;
import astar.pathing.Landmarks;
import astar.pathing.HopGraph;
import astar.pathing.NavGraph;
import astar.pathing.TransmitHops;
import astar.pathing.WarpHops;
import astar.pathing.PathAnalysis;
import astar.pathing.PathSmoother;
import astar.pathing.PortalGraph;
import astar.pathing.Reachability;
import astar.pathing.TerrainCostModel;
import astar.pathing.TerrainCosts;
import astar.pathing.Waypoint;
import astar.pathing.WorldPathfinder;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import javax.imageio.ImageIO;

/**
 * Finds a route across an island of a world save and draws it: an overview from above (and
 * the same with the terrain coloured by height), a height profile, and close-ups of the
 * busiest levels. Made for maps too big for one panel per
 * level. It can also time the search, and compare the default weights with your own.
 *
 * <pre>
 * RouteTool [world folder or .zip] [options]
 *   (no world: the saved one, which is the last world given to route, islands or edit,
 *   or else the Dwarven Mines in maps/)
 *   --island n               which island (default 1, the largest)
 *   --from x,y,z --to x,y,z  endpoints in game coordinates (default: two far-apart points)
 *   --out dir                where images go (default build/viz/route)
 *   --4way                   no diagonal moves
 *   --16way                  also level moves one across and two along (about 27 and 63
 *                            degrees off the axes); plain A* only
 *   --bench n                time the search n times after warming up, and print stats
 *   --walk-cost c  --diagonal-cost c  --jump-cost c  --drop-cost c  --drop-per-block c
 *   --swim-cost c  --climb-cost c
 *                            move costs (defaults 1, 1.414, 2, 1, 0.5, 2, 1.5)
 *   --door-cost c  --slow-cost x  --web-cost x  --damage-cost c
 *                            terrain: added per door opened, times slower on soul sand and
 *                            honey, times slower in cobwebs, added per step onto magma or
 *                            into a berry bush (defaults 1, 2.5, 4, 5)
 *   --wall-cost w            steps against a wall or at a ledge cost 1 + w times as much,
 *                            one cell in 1 + w/2, further in nothing (default 0.3; 0 walks
 *                            as tight as it can)
 *                            (any costs above 0: the heuristic is scaled to them)
 *   --heuristic-weight w     weighted A*: faster, but paths up to w times the cheapest
 *   --no-diagonal-leaps      jump up and drop down only along x or z
 *   --height-heuristic       also count the cost of climbing or coming down to the goal's
 *                            height in the heuristic (same paths, can search fewer nodes)
 *   --landmarks n            bound the heuristic by exact costs to and from n landmarks,
 *                            worked out once per map (same paths, fewer nodes; plain A* only;
 *                            default 16, 0 turns them off)
 *   --no-graph               plain A* works out each node's moves as it goes, instead of
 *                            over the move graph (every move worked out once per map)
 *   --exact-turns            with a turn cost, keep a node per heading, so the path is the
 *                            cheapest counting turns; landmarks then count turns too (the
 *                            first 4 get tables per heading). Slower on most routes
 *   --approx-turns           one node per cell, charging each turn from the cell's cheapest
 *                            way in (the default)
 *   --turn-cost t            add t for every 45 degrees the heading turns (diagonal runs
 *                            count as straight), so straight runs beat zigzags; plain A* only
 *   --sweep [w/d/h[/t],...]  also search the route with each straight cost w, diagonal cost d,
 *                            heuristic weight h and turn cost t (default: --turn-cost), print how the paths differ, and draw
 *                            them side by side (sweep-overview.png, sweep-closeup.png with
 *                            every node and key node). With no list: 1/1.414/1, 1/1/1,
 *                            1/1.6/1, 1/2/1, 1/1.414/1.5 and 1/1.414/3. With --pairs n,
 *                            also compare them over n random routes
 *   --hpa                    hierarchical A*: search between cluster portals, then fill in
 *   --cluster n              cluster size for --hpa, in blocks (default 16, like a chunk)
 *   --short-range n          with --hpa, try plain A* first when the goal is within n blocks
 *                            (default 4 clusters; 0 always uses the hierarchy)
 *   --short-budget n         expansions that plain A* try may use (default 32 per block of range)
 *   --compare                also run the default settings (plain A*) and print both side by side
 *   --pairs n                also time n random routes with the default settings and yours
 *   --pairs-within n         pick each random route's goal within n blocks of its start
 *   --goto n                 time n /goto plans one after another, each from where the last
 *                            ended to a goal within --pairs-within blocks (default 150): as
 *                            before (a new pathfinder on a copy around each trip) and as now
 *                            (one pathfinder kept for the map: move graph built once,
 *                            landmarks built once in the background), and print the first
 *                            8 trips' goals in game coordinates for the mod's -Pastar.goto
 *   --plan                   also build the executor's plan for the route (floors, room on
 *                            each side, doors, speed limits) and summarise it
 *   --exec                   also build the plan and walk it with the executor in the
 *                            simulator, stretch by stretch (moves it can't do yet are
 *                            skipped over), and say how it went; with --pairs, walk the
 *                            random routes instead of timing them
 *   --grid-steps             with --exec, walk the grid steps as found instead of the
 *                            smoothed lines (the executor walks those by default, like /goto)
 *   --disturb                with --exec and --pairs, walk each random route with the
 *                            recovery on (Journey) while pushing the player every few
 *                            seconds, lagging it back once and placing a block on the path
 *                            ahead once, and say how many still arrived
 *   --speed-effect n         with --disturb, the player has Speed n (7 is 2.4x as fast)
 *   --script dir             with --exec, also write each stretch the executor walked as a
 *                            script for the recorder mod (route-00.txt, ...), starting with
 *                            a teleport to its first step in the world, and autoplay.txt
 *                            listing them; see docs/TRACES.md
 *   --warp                   also find the route with etherwarp hops (see WarpHops): build
 *                            every sure hop over the move graph, and print the route walking
 *                            only and with hops, and each hop with where to aim; with
 *                            --pairs n, also over n random routes
 *   --transmit               the same with Instant Transmission hops (see TransmitHops); with
 *                            --warp too, with both; --transmit-min m leaves casts landing
 *                            closer than m blocks to walking (default 10)
 *   --warp-range r  --warp-cost c  --warp-min m  --warp-spacing s  --warp-turn t
 *                            hop reach from the eye (default 57), what a hop costs in blocks
 *                            walked (5), shortest hop kept (35 blocks), anchors at most one
 *                            per s-block box (4), turning onto or off a hop per 45 degrees (1)
 *   --hop-detour d  --ether-length l
 *                            what a teleport pays per block that doesn't bring it nearer the
 *                            goal (0.5), and etherwarps shorter than l blocks pay 0.15 per
 *                            block short (40)
 *   --walk-detour w          with teleports, what walking pays per block that doesn't bring
 *                            it nearer the goal (0.25)
 *   --score dir              with --exec, score the traces the mod recorded from those scripts
 *                            (route-00-*.jsonl, ... in dir): how far the real player drifted
 *                            from the simulated one and from the route, where it stopped, and
 *                            how well the simulator replays it on the real blocks
 * </pre>
 */
public final class RouteTool {
    private RouteTool() {}

    /**
     * The settings a search runs with: move and terrain costs, the heuristic weight, and
     * whether it's hierarchical (with what cluster size, and how far it tries plain A* first;
     * -1 for the pathfinder's defaults), the cost per 45 degrees of turn (plain A* only), and
     * whether jumps and drops may go diagonally (off for routes the executor walks).
     */
    record Settings(DefaultCostModel costs, TerrainCosts terrain, double heuristicWeight,
            int directions, boolean hierarchical, int clusterSize, int shortRange,
            int shortBudget, double turnCost, boolean diagonalLeaps, boolean heightAware,
            int landmarks, boolean exactTurns) {

        /** Terrain costs by default: {@link TerrainCosts#DEFAULT}, keeping off walls. */
        static final TerrainCosts TERRAIN = TerrainCosts.DEFAULT.withWall(TerrainCosts.WALL);
        static final Settings DEFAULT = defaults(true);

        /** Plain A* with the default weights, moving 4-way or 8-way. */
        static Settings defaults(boolean diagonal) {
            return new Settings(DefaultCostModel.DEFAULT, Settings.TERRAIN, 1,
                    diagonal ? 8 : 4, false, ClusterLayout.DEFAULT_SIZE, -1, -1,
                    TurnPenalty.DEFAULT_PER_TURN, true, false,
                    AutoLandmarks.DEFAULT_COUNT, false);
        }

        /** The same, without the short-route settings: the part that decides the graph. */
        Settings graphKey() {
            return new Settings(costs, terrain, heuristicWeight, directions, hierarchical,
                    clusterSize, -1, -1, turnCost, diagonalLeaps, heightAware, landmarks,
                    exactTurns);
        }

        /** Whether it moves diagonally (8-way or 16-way). */
        boolean diagonal() {
            return directions >= 8;
        }

        boolean isDefault() {
            return costs.equals(DefaultCostModel.DEFAULT) && terrain.equals(Settings.TERRAIN)
                    && heuristicWeight == 1 && !hierarchical && directions != 16
                    && turnCost == TurnPenalty.DEFAULT_PER_TURN
                    && diagonalLeaps && !heightAware
                    && landmarks == AutoLandmarks.DEFAULT_COUNT && !exactTurns;
        }

        Heuristic heuristic() {
            // Scaled to the costs, so cheaper steps than the defaults still give the cheapest path.
            return Heuristics.scaled(heightAware ? costs.heightAwareHeuristic(directions)
                    : costs.heuristic(directions), heuristicWeight);
        }

        String describe() {
            return String.format("walk %s, diagonal %s, jump %s, drop %s + %s per block, swim %s,"
                    + " climb %s, door %s, slow x%s, web x%s, damage %s, wall %s%s",
                    num(costs.walk()), num(costs.diagonal()), num(costs.jumpUp()),
                    num(costs.dropBase()), num(costs.dropPerBlock()), num(costs.swim()),
                    num(costs.climb()), num(terrain.door()), num(terrain.slow()),
                    num(terrain.web()), num(terrain.damage()), num(terrain.wall()),
                    heuristicWeight == 1 ? "" : ", heuristic x" + num(heuristicWeight))
                    + (turnCost == TurnPenalty.DEFAULT_PER_TURN ? ""
                            : turnCost == 0 ? ", no turn cost"
                            : ", turn cost " + num(turnCost) + " per 45 degrees")
                    + (diagonalLeaps ? "" : ", no diagonal jumps or drops")
                    + (heightAware ? ", height-aware heuristic" : "")
                    + (landmarks == AutoLandmarks.DEFAULT_COUNT ? ""
                            : landmarks == 0 ? ", no landmarks" : ", " + landmarks + " landmarks")
                    + (exactTurns && turnCost != 0 && !hierarchical ? ", exact turn costs" : "")
                    + (directions == 16 ? ", 16-way" : "")
                    + (hierarchical ? ", hierarchical (" + clusterSize + "-block clusters"
                            + (shortRange == 0 ? ", no plain A* for short routes"
                                    : shortRange > 0 || shortBudget >= 0
                                            ? ", plain A* first within " + (shortRange < 0
                                                    ? "the default range" : shortRange + " blocks")
                                            + (shortBudget < 0 ? ""
                                                    : ", up to " + shortBudget + " expansions")
                                            : "")
                            + ")" : "");
        }
    }

    /**
     * One search's outcome. For hierarchical searches, {@code hpa} is the pathfinder (its graph
     * and build time) and {@code stages} how the query's work split; both are null otherwise.
     */
    record Outcome(SearchResult result, List<Waypoint> waypoints, double searchMs, double smoothMs,
            HierarchicalPathfinder hpa, HierarchicalPathfinder.QueryStats stages) {

        long count(MoveType type) {
            return result.path().stream().filter(s -> s.via() == type).count();
        }

        /** Steps down one block: DROP moves that are descents, not drops. */
        long descents() {
            return PathAnalysis.descents(result.path());
        }

        /** Distance walked along the grid path, in blocks (3D). */
        double length() {
            return PathSmoother.length(result.positions());
        }
    }

    public static void main(String[] args) throws IOException {
        boolean worldGiven = args.length > 0 && !args[0].startsWith("--");
        Path world = SavedMap.resolve(worldGiven ? args[0] : null,
                "Usage: ./gradlew route --args=\"<world or .zip> [--island n]"
                        + " [--from x,y,z --to x,y,z] [--bench n] [--compare] [--jump-cost c] ...\""
                        + " (see RouteTool's documentation for every option)").orElse(null);
        if (world == null) {
            return;
        }
        int islandIndex = 1;
        BlockPoint from = null;
        BlockPoint to = null;
        Path out = Path.of("build/viz/route");
        boolean diagonal = true;
        boolean sixteen = false;
        int bench = 0;
        boolean compare = false;
        List<CostSweep.Setting> sweep = null;
        boolean plan = false;
        boolean exec = false;
        boolean disturb = false;
        int speedEffect = 0;
        Path scriptDir = null;
        Path scoreDir = null;
        boolean hierarchical = false;
        int clusterSize = ClusterLayout.DEFAULT_SIZE;
        int pairs = 0;
        int pairsWithin = 0;
        int gotoPlans = 0;
        int shortRange = -1;
        int shortBudget = -1;
        DefaultCostModel d = DefaultCostModel.DEFAULT;
        double walk = d.walk(), diag = d.diagonal(), jump = d.jumpUp(), drop = d.dropBase();
        double dropPer = d.dropPerBlock(), swim = d.swim(), climb = d.climb();
        TerrainCosts td = Settings.TERRAIN;
        double door = td.door(), slow = td.slow(), web = td.web(), damage = td.damage();
        double wall = td.wall();
        double turn = Double.NaN; // the default, or 0 with --hpa
        boolean noDiagonalLeaps = false;
        boolean heightAware = false;
        int landmarks = -1; // the default, or none with --hpa
        boolean exactTurns = false;
        double weight = 1;
        boolean warp = false;
        boolean transmit = false;
        double transmitMin = TransmitHops.DEFAULT_MIN_LENGTH;
        double hopDetour = HopGraph.DEFAULT_DETOUR;
        double etherLength = HopGraph.DEFAULT_ETHER_LENGTH;
        double walkDetour = HopGraph.DEFAULT_WALK_DETOUR;
        double warpRange = WarpHops.DEFAULT_RANGE, warpCost = WarpHops.DEFAULT_COST;
        double warpMin = WarpHops.DEFAULT_MIN_LENGTH;
        int warpSpacing = WarpHops.DEFAULT_SPACING;
        double warpTurn = WarpHops.DEFAULT_TURN;
        try {
            for (int i = worldGiven ? 1 : 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--island" -> islandIndex = Integer.parseInt(args[++i]);
                    case "--from" -> from = parse(args[++i]);
                    case "--to" -> to = parse(args[++i]);
                    case "--out" -> out = Path.of(args[++i]);
                    case "--4way" -> diagonal = false;
                    case "--16way" -> sixteen = true;
                    case "--bench" -> bench = Integer.parseInt(args[++i]);
                    case "--compare" -> compare = true;
                    case "--sweep" -> sweep = i + 1 < args.length && !args[i + 1].startsWith("--")
                            ? CostSweep.parse(args[++i]) : CostSweep.DEFAULTS;
                    case "--plan" -> plan = true;
                    case "--exec" -> exec = true;
                    case "--disturb" -> disturb = true;
                    case "--speed-effect" -> speedEffect = Integer.parseInt(args[++i]);
                    case "--grid-steps" -> gridSteps = true;
                    case "--script" -> scriptDir = Path.of(args[++i]);
                    case "--score" -> scoreDir = Path.of(args[++i]);
                    case "--hpa" -> hierarchical = true;
                    case "--cluster" -> clusterSize = Integer.parseInt(args[++i]);
                    case "--pairs" -> pairs = Integer.parseInt(args[++i]);
                    case "--pairs-within" -> pairsWithin = Integer.parseInt(args[++i]);
                    case "--short-range" -> shortRange = Integer.parseInt(args[++i]);
                    case "--short-budget" -> shortBudget = Integer.parseInt(args[++i]);
                    case "--walk-cost" -> walk = Double.parseDouble(args[++i]);
                    case "--diagonal-cost" -> diag = Double.parseDouble(args[++i]);
                    case "--jump-cost" -> jump = Double.parseDouble(args[++i]);
                    case "--drop-cost" -> drop = Double.parseDouble(args[++i]);
                    case "--drop-per-block" -> dropPer = Double.parseDouble(args[++i]);
                    case "--swim-cost" -> swim = Double.parseDouble(args[++i]);
                    case "--climb-cost" -> climb = Double.parseDouble(args[++i]);
                    case "--door-cost" -> door = Double.parseDouble(args[++i]);
                    case "--slow-cost" -> slow = Double.parseDouble(args[++i]);
                    case "--web-cost" -> web = Double.parseDouble(args[++i]);
                    case "--damage-cost" -> damage = Double.parseDouble(args[++i]);
                    case "--wall-cost" -> wall = Double.parseDouble(args[++i]);
                    case "--heuristic-weight" -> weight = Double.parseDouble(args[++i]);
                    case "--turn-cost" -> turn = Double.parseDouble(args[++i]);
                    case "--no-diagonal-leaps" -> noDiagonalLeaps = true;
                    case "--height-heuristic" -> heightAware = true;
                    case "--landmarks" -> landmarks = Integer.parseInt(args[++i]);
                    case "--exact-turns" -> exactTurns = true;
                    case "--approx-turns" -> exactTurns = false;
                    case "--no-graph" -> moveGraph = false;
                    case "--goto" -> gotoPlans = Integer.parseInt(args[++i]);
                    case "--warp" -> warp = true;
                    case "--transmit" -> transmit = true;
                    case "--transmit-min" -> transmitMin = Double.parseDouble(args[++i]);
                    case "--hop-detour" -> hopDetour = Double.parseDouble(args[++i]);
                    case "--ether-length" -> etherLength = Double.parseDouble(args[++i]);
                    case "--walk-detour" -> walkDetour = Double.parseDouble(args[++i]);
                    case "--warp-range" -> warpRange = Double.parseDouble(args[++i]);
                    case "--warp-cost" -> warpCost = Double.parseDouble(args[++i]);
                    case "--warp-min" -> warpMin = Double.parseDouble(args[++i]);
                    case "--warp-spacing" -> warpSpacing = Integer.parseInt(args[++i]);
                    case "--warp-turn" -> warpTurn = Double.parseDouble(args[++i]);
                    default -> throw new IllegalArgumentException("Unknown option " + args[i]);
                }
            }
            if (Double.isNaN(turn)) {
                turn = hierarchical ? 0 : TurnPenalty.DEFAULT_PER_TURN;
            }
            if (landmarks < 0) {
                landmarks = hierarchical ? 0 : AutoLandmarks.DEFAULT_COUNT;
            }
            if (landmarks > 64) {
                throw new IllegalArgumentException("--landmarks must be 0 to 64");
            }
            if (landmarks > 0 && hierarchical) {
                throw new IllegalArgumentException("--landmarks works with plain A* only");
            }
            if (!(turn >= 0)) {
                throw new IllegalArgumentException("--turn-cost can't be negative");
            }
            if (turn > 0 && hierarchical) {
                throw new IllegalArgumentException("--turn-cost works with plain A* only");
            }
            if (warp && (hierarchical || !(warpRange > 0) || !(warpCost > 0) || warpSpacing < 1)) {
                throw new IllegalArgumentException("--warp works with plain A* only, with a"
                        + " range and cost above 0 and a spacing of at least 1");
            }
            if (weight < 1) {
                throw new IllegalArgumentException("--heuristic-weight must be at least 1");
            }
            if (clusterSize < 2) {
                throw new IllegalArgumentException("--cluster must be at least 2");
            }
            if (shortRange < -1 || shortBudget < -1) {
                throw new IllegalArgumentException("--short-range and --short-budget can't be"
                        + " negative");
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            System.out.println("An option is missing its value.");
            return;
        } catch (IllegalArgumentException e) {
            System.out.println(e.getMessage());
            return;
        }
        Settings settings;
        try {
            settings = new Settings(new DefaultCostModel(walk, diag, jump, drop, dropPer,
                    DefaultCostModel.DEFAULT.stepInPlace(), swim, climb),
                    new TerrainCosts(door, slow, web, damage, TerrainCosts.DEFAULT.current(), wall),
                    weight,
                    sixteen ? 16 : diagonal ? 8 : 4, hierarchical,
                    clusterSize, shortRange, shortBudget, turn, !noDiagonalLeaps,
                    heightAware, landmarks, exactTurns);
        } catch (IllegalArgumentException e) {
            System.out.println("Those weights aren't allowed: " + e.getMessage() + ". Move costs"
                    + " must be above 0 (drop per block 0 or more), slow and web at least 1, door,"
                    + " damage and wall 0 or more.");
            return;
        }
        if (sixteen && (hierarchical || !diagonal)) {
            System.out.println("--16way works with plain A* only, and not with --4way.");
            return;
        }
        Files.createDirectories(out);

        long t = System.nanoTime();
        List<Island> islands;
        try {
            islands = WorldImporter.scan(world);
        } catch (IOException e) {
            System.out.println(e.getMessage());
            return;
        }
        if (islandIndex < 1 || islandIndex > islands.size()) {
            System.out.println("There's no island " + islandIndex + "; this world has "
                    + islands.size() + ". Run ./gradlew islands to list them.");
            return;
        }
        if (worldGiven && !args[0].isBlank()) {
            SavedMap.save(world);
        }
        Island island = islands.get(islandIndex - 1);
        System.out.printf("%s%n  scanned %d islands in %.1f s%n", island, islands.size(), secs(t));

        t = System.nanoTime();
        ImportedWorld imported = WorldImporter.load(world, island);
        ArrayBlockView blocks = imported.world();
        BlockPoint o = imported.origin();
        System.out.printf("  imported %d x %d x %d cells in %.1f s%n", blocks.sizeX(), blocks.sizeY(),
                blocks.sizeZ(), secs(t));
        if (WorldImporter.sealRoofIfCapped(imported)) {
            System.out.println("  the island is capped by a solid top layer: sealed the roof so"
                    + " routes stay inside");
        }

        BlockPoint start;
        BlockPoint goal;
        if ((from == null) != (to == null)) {
            System.out.println("  --from and --to go together: give both, or neither for the"
                    + " longest route");
            return;
        }
        if (from != null && to != null) {
            start = from.offset(-o.x(), -o.y(), -o.z());
            goal = to.offset(-o.x(), -o.y(), -o.z());
        } else {
            t = System.nanoTime();
            Reachability.Route r = Reachability.longestRoute(blocks, EntityProfile.DEFAULT, diagonal)
                    .orElse(null);
            if (r == null) {
                System.out.println("  nowhere to stand on this island");
                return;
            }
            start = r.start();
            goal = r.goal();
            System.out.printf("  picked far-apart points in %.1f s%n", secs(t));
        }
        System.out.printf("  route %s -> %s (in game)%n", imported.toWorld(start), imported.toWorld(goal));

        WorldPathfinder validator = new WorldPathfinder(blocks, EntityProfile.DEFAULT, diagonal);
        start = ontoLowFloor(validator, imported, start, "start");
        goal = ontoLowFloor(validator, imported, goal, "goal");
        if (!validator.canStand(start) || !validator.canStand(goal)) {
            System.out.println("  the entity can't stand at "
                    + (!validator.canStand(start) ? "the start " + imported.toWorld(start)
                            : "the goal " + imported.toWorld(goal))
                    + ": it needs a floor (a block, or a slab, stairs, carpet... in the cell"
                    + " itself) and room for its 1.8-block body");
            return;
        }

        Outcome mine = run(blocks, start, goal, settings);
        if (!mine.result().found()) {
            System.out.println("  no route between those points");
            return;
        }
        report(blocks, settings.isDefault() ? "result" : "result (" + settings.describe() + ")", mine);
        if (plan) {
            printPlan(blocks, imported, mine.result().path());
        }
        if (exec) {
            printExecution(blocks, imported, mine.result().path(),
                    lines(blocks, settings, mine), scriptDir, scoreDir);
        }

        String name = imported.name();
        String suffix = compare ? "-custom" : "";
        writeImages(blocks, mine, o, name, out, suffix);

        Settings base = Settings.defaults(diagonal);
        if (compare) {
            Outcome def = run(blocks, start, goal, base);
            writeImages(blocks, def, o, name, out, "-default");
            printComparison(blocks, base, def, settings, mine);
        }

        if (warp || transmit) {
            BlockPoint[][] routes = pairs > 0 && !exec
                    ? randomRoutes(blocks, settings.diagonal(), true, pairs, pairsWithin) : null;
            WarpReport.run(finder(blocks, settings), settings.costs(), blocks, imported, start,
                    goal, new WarpReport.Options(warpRange, warpCost, warpMin, warpSpacing,
                            warpTurn, settings.turnCost(), warp, transmit, transmitMin, hopDetour,
                            etherLength, walkDetour),
                    routes, out);
            pairs = exec ? pairs : 0; // the hops used them
        }

        if (sweep != null) {
            sweep(blocks, start, goal, settings, sweep, name, out);
            if (pairs > 0) {
                BlockPoint[][] routes = randomRoutes(blocks, settings.diagonal(), true, pairs,
                        pairsWithin);
                if (routes != null) {
                    CostSweep.printRandom(blocks, routes, settings, sweep);
                }
                pairs = 0; // the sweep used them
            }
        }

        if (bench > 0) {
            benchmark(blocks, start, goal, compare ? List.of(base, settings) : List.of(settings),
                    bench);
        }
        if (gotoPlans > 0) {
            gotoPlans(blocks, o, gotoPlans, pairsWithin > 0 ? pairsWithin : 150);
        }
        if (pairs > 0 && exec && disturb) {
            disturbedPairs(blocks, imported, settings, pairs, pairsWithin, speedEffect);
        } else if (pairs > 0 && exec) {
            execPairs(blocks, imported, settings, pairs, pairsWithin);
        } else if (pairs > 0) {
            pairs(blocks, base, settings, pairs, pairsWithin);
        }
    }

    /**
     * Times {@code count} /goto plans in a row, each from where the last one ended: first as
     * the mod used to plan (a new pathfinder, and so a new move graph, on a copy of the blocks
     * around each trip, with {@code Navigator}'s margins), then as it plans now (one
     * {@link KeptPathfinder} for the whole map, landmarks built in the background after the
     * first plan, as they would be while the player walks).
     */
    private static void gotoPlans(ArrayBlockView blocks, BlockPoint o, int count, int within) {
        List<BlockPoint> area = Reachability.mainArea(blocks,
                EntityProfile.DEFAULT, true);
        if (area.size() < 2) {
            System.out.println("  not enough room for /goto trips");
            return;
        }
        Random rng = new Random(1);
        List<BlockPoint> stops = new ArrayList<>();
        stops.add(area.get(rng.nextInt(area.size())));
        while (stops.size() <= count) {
            BlockPoint at = stops.get(stops.size() - 1);
            BlockPoint next = null;
            for (int tries = 0; next == null && tries < 100_000; tries++) {
                BlockPoint g = area.get(rng.nextInt(area.size()));
                int dx = Math.abs(g.x() - at.x());
                int dz = Math.abs(g.z() - at.z());
                next = dx <= within && dz <= within && dx + dz > 0 ? g : null;
            }
            if (next == null) {
                System.out.println("  couldn't find /goto trips: the area is too small");
                return;
            }
            stops.add(next);
        }
        System.out.printf("  %d /goto trips in a row, each within %d blocks%n", count, within);
        // The same trips in the game, for the mod's unattended goto (the player starts at the first).
        StringBuilder game = new StringBuilder();
        for (int i = 1; i <= Math.min(count, 8); i++) {
            BlockPoint g = stops.get(i).offset(o.x(), o.y(), o.z());
            game.append(i > 1 ? ";" : "").append(g.x()).append(',').append(g.y()).append(',')
                    .append(g.z());
        }
        BlockPoint first = stops.get(0).offset(o.x(), o.y(), o.z());
        System.out.printf("  in the game: start at %d %d %d, then -Pastar.goto=\"%s\"%n",
                first.x(), first.y(), first.z(), game);

        // Before: a copy around each trip and a new pathfinder on it.
        double[] before = new double[count];
        double[] beforeCost = new double[count];
        double copyMs = 0;
        for (int i = 0; i < count; i++) {
            BlockPoint a = stops.get(i);
            BlockPoint b = stops.get(i + 1);
            int margin = 48;
            int y = 24;
            long t0 = System.nanoTime();
            int x0 = Math.min(a.x(), b.x()) - margin;
            int y0 = Math.min(a.y(), b.y()) - y;
            int z0 = Math.min(a.z(), b.z()) - margin;
            ArrayBlockView copy = blocks.crop(x0, y0, z0, Math.max(a.x(), b.x()) + margin,
                    Math.max(a.y(), b.y()) + y, Math.max(a.z(), b.z()) + margin);
            int ox = Math.max(0, x0);
            int oy = Math.max(0, y0);
            int oz = Math.max(0, z0);
            long t1 = System.nanoTime();
            copyMs += (t1 - t0) / 1e6;
            WorldPathfinder finder = GotoPathfinder.create(copy);
            SearchResult r = finder.find(a.offset(-ox, -oy, -oz), b.offset(-ox, -oy, -oz));
            before[i] = (System.nanoTime() - t1) / 1e6;
            beforeCost[i] = r.found() ? r.cost() : Double.NaN;
        }

        // Now: one pathfinder for the map, kept from trip to trip.
        KeptPathfinder kept = new KeptPathfinder(GotoPathfinder.create(blocks),
                AutoLandmarks.DEFAULT_COUNT);
        double[] now = new double[count];
        double[] nowCost = new double[count];
        double landmarkMs = 0;
        for (int i = 0; i < count; i++) {
            BlockPoint a = stops.get(i);
            BlockPoint b = stops.get(i + 1);
            long t0 = System.nanoTime();
            SearchResult r = kept.forStart(a.pack()).find(a, b);
            now[i] = (System.nanoTime() - t0) / 1e6;
            nowCost[i] = r.found() ? r.cost() : Double.NaN;
            if (i == 0) {
                // The player walks the first trip meanwhile.
                long t1 = System.nanoTime();
                while (kept.buildingLandmarks()) {
                    Thread.onSpinWait();
                }
                landmarkMs = (System.nanoTime() - t1) / 1e6;
            }
        }
        int cheaper = 0;
        int dearer = 0;
        int onlyNow = 0;
        double worst = 0;
        for (int i = 0; i < count; i++) {
            if (Double.isNaN(beforeCost[i])) {
                onlyNow += Double.isNaN(nowCost[i]) ? 0 : 1;
            } else if (nowCost[i] < beforeCost[i] - 1e-6) {
                cheaper++;
            } else if (nowCost[i] > beforeCost[i] + 1e-6) {
                dearer++;
                worst = Math.max(worst, nowCost[i] / beforeCost[i] - 1);
            }
        }
        double[] later = Arrays.copyOfRange(now, 1, count);
        System.out.printf("  before: every plan %.1f ms median, %.1f ms p90, %.0f ms in all"
                + " (plus %.1f ms copying blocks per trip, the same now)%n", median(before),
                percentile(before, 0.9), Arrays.stream(before).sum(), copyMs / count);
        System.out.printf("  now:    first plan %.1f ms (no move graph yet), graph and landmarks"
                + " ready %.0f ms later in the background%n", now[0], landmarkMs);
        System.out.printf("          later plans %.1f ms median, %.1f ms p90, %.0f ms in all%n",
                median(later), percentile(later, 0.9), Arrays.stream(now).sum());
        System.out.printf("  same cost on %d trips, cheaper now on %d (routes may leave the old"
                + " copy), dearer on %d (by at most %.2f%%: turn costs are approximate), found"
                + " only now on %d%n", count - cheaper - dearer - onlyNow, cheaper, dearer,
                100 * worst, onlyNow);
    }

    private static double median(double[] v) {
        return percentile(v, 0.5);
    }

    private static double percentile(double[] v, double p) {
        if (v.length == 0) {
            return Double.NaN;
        }
        double[] s = v.clone();
        Arrays.sort(s);
        return s[Math.min(s.length - 1, (int) Math.floor(p * (s.length - 1) + 0.5))];
    }

    /** Runs the route with each sweep setting, prints the table and draws the pictures. */
    private static void sweep(ArrayBlockView blocks, BlockPoint start, BlockPoint goal,
            Settings base, List<CostSweep.Setting> settings, String name, Path out)
            throws IOException {
        List<CostSweep.Row> rows = CostSweep.run(blocks, start, goal, base, settings);
        CostSweep.print(rows);
        if (rows.stream().noneMatch(r -> r.outcome().result().found())) {
            return;
        }
        java.awt.Rectangle window = CostSweep.busiestDifference(blocks, rows, 56);
        write(CostSweep.overview(blocks, rows, window,
                name + ": the same route with different step costs and heuristic weights"),
                out.resolve("sweep-overview.png"));
        write(CostSweep.closeUp(blocks, rows, window, name + ": close-up where the paths differ"
                + " most (" + window.width + " x " + window.height + " blocks)"),
                out.resolve("sweep-closeup.png"));
    }

    /** Runs one search with these settings and smooths the result. */
    static Outcome run(ArrayBlockView blocks, BlockPoint start, BlockPoint goal, Settings s) {
        if (s.hierarchical()) {
            HierarchicalPathfinder hpa = hierarchical(blocks, s);
            long t0 = System.nanoTime();
            SearchResult result = hpa.find(start, goal);
            long t1 = System.nanoTime();
            List<Waypoint> waypoints = result.found() ? hpa.smoother().smooth(result.path()) : List.of();
            long t2 = System.nanoTime();
            return new Outcome(result, waypoints, (t1 - t0) / 1e6, (t2 - t1) / 1e6, hpa,
                    hpa.lastQuery());
        }
        WorldPathfinder finder = FINDERS.computeIfAbsent(new GraphKey(blocks, s),
                k -> finder(blocks, s));
        long t0 = System.nanoTime();
        AStarSearch search = finder.search(start, goal, SearchListener.NONE);
        search.runToEnd();
        long t1 = System.nanoTime();
        SearchResult result = search.result();
        List<Waypoint> waypoints = result.found() ? finder.smoother().smooth(result.path()) : List.of();
        long t2 = System.nanoTime();
        return new Outcome(result, waypoints, (t1 - t0) / 1e6, (t2 - t1) / 1e6, null, null);
    }

    /**
     * The route the executor walks: the path laid along its smoothed lines, or its grid steps
     * as they are with {@code --grid-steps} or hierarchical search.
     */
    private static StraightRoute lines(ArrayBlockView blocks, Settings s, Outcome o) {
        WorldPathfinder finder = s.hierarchical() ? null : FINDERS.get(new GraphKey(blocks, s));
        return gridSteps || finder == null ? null
                : StraightRoute.of(o.result().path(),
                        StraightRoute.smoother(finder).smooth(o.result().path()),
                        finder.moves());
    }

    /** An angle in degrees, brought into -180 to 180. */
    private static double wrap(double degrees) {
        double d = degrees % 360;
        return d > 180 ? d - 360 : d < -180 ? d + 360 : d;
    }

    /** The executor's plan for a route: along its lines when given, else its grid steps. */
    private static ExecutionPlan plan(SimWorld world, List<PathStep> path, StraightRoute lines) {
        PlanBuilder b = new PlanBuilder(world);
        return lines != null ? b.build(lines) : b.build(path);
    }

    private static WorldPathfinder finder(ArrayBlockView blocks, Settings s) {
        EntityProfile profile = EntityProfile.DEFAULT.withDiagonalLeaps(s.diagonalLeaps());
        WorldPathfinder plain = new WorldPathfinder(blocks, profile, s.directions(), s.costs(),
                s.terrain(), s.heuristic(), s.turnCost(), s.exactTurns()).useGraph(moveGraph);
        if (s.landmarks() == 0) {
            return plain;
        }
        List<BlockPoint> area = Reachability.mainArea(blocks, profile, s.diagonal());
        if (area.isEmpty()) {
            return plain;
        }
        Landmarks lm = Landmarks.build(plain, area.get(0).pack(), s.landmarks());
        NavGraph g = lm.graph();
        System.out.printf("  %d landmarks over %,d cells in %.0f ms (%.1f MB), with the move"
                + " graph (%,d moves, %.0f ms, %.1f MB)%n", lm.count(), lm.cells(), lm.buildMs(),
                lm.bytes() / 1e6, g.moves(), g.buildMs(), g.bytes() / 1e6);
        Heuristic flat = s.heightAware() ? s.costs().heightAwareHeuristic(s.directions())
                : s.costs().heuristic(s.directions());
        return plain.withHeuristic(Heuristics.scaled(lm.heuristic(flat), s.heuristicWeight()));
    }

    /** Whether plain searches run over a move graph ({@code --no-graph} turns it off). */
    private static boolean moveGraph = true;
    /** Whether the executor walks the grid steps as found, not the smoothed lines. */
    private static boolean gridSteps = false;

    private record GraphKey(ArrayBlockView world, Settings settings) {}

    private static final Map<GraphKey, HierarchicalPathfinder> GRAPHS = new HashMap<>();

    /**
     * Plain pathfinders, kept like a game would keep one: they remember each cell's moves (see
     * {@code MoveCache}), so the first search on a map is the slowest.
     */
    private static final Map<GraphKey, WorldPathfinder> FINDERS = new HashMap<>();

    /** The hierarchical pathfinder for this world and these settings, built the first time. */
    static HierarchicalPathfinder hierarchical(ArrayBlockView blocks, Settings s) {
        HierarchicalPathfinder hpa = GRAPHS.computeIfAbsent(new GraphKey(blocks, s.graphKey()),
                k -> buildGraph(blocks, s));
        double range = s.shortRange() < 0
                ? HierarchicalPathfinder.defaultShortRange(s.clusterSize()) : s.shortRange();
        return hpa.shortRoutes(range, s.shortBudget() < 0
                ? HierarchicalPathfinder.defaultShortBudget(range) : s.shortBudget());
    }

    private static HierarchicalPathfinder buildGraph(ArrayBlockView blocks, Settings s) {
        return HierarchicalPathfinder.build(finder(blocks, s), blocks, s.clusterSize());
    }

    /**
     * {@code count} random routes in the island's main area, as {start, goal} pairs, or null
     * (having said why) when the area is too small. See {@link #pairs} for {@code within}.
     */
    private static BlockPoint[][] randomRoutes(ArrayBlockView blocks, boolean diagonal,
            boolean diagonalLeaps, int count, int within) {
        // Without diagonal jumps and drops, routes keep out of places only they reach.
        List<BlockPoint> area = Reachability.mainArea(blocks,
                EntityProfile.DEFAULT.withDiagonalLeaps(diagonalLeaps), diagonal);
        if (area.size() < 2) {
            System.out.println("  not enough room for random routes");
            return null;
        }
        Random rng = new Random(1);
        BlockPoint[][] routes = new BlockPoint[count][];
        Map<Long, List<BlockPoint>> columns = new HashMap<>();
        if (within > 0) {
            for (BlockPoint p : area) {
                columns.computeIfAbsent(Pos.pack(p.x(), 0, p.z()), k -> new ArrayList<>()).add(p);
            }
        }
        for (int i = 0; i < count; i++) {
            BlockPoint start = area.get(rng.nextInt(area.size()));
            BlockPoint goal = null;
            // A goal at the start makes an empty route, whose near-zero times give meaningless
            // speed-ups; --pairs-within also keeps out the start's own column (distance 0).
            for (int tries = 0; goal == null && tries < 10_000; tries++) {
                if (within <= 0) {
                    BlockPoint g = area.get(rng.nextInt(area.size()));
                    goal = g.equals(start) ? null : g;
                    continue;
                }
                int d = 1 + rng.nextInt(within);
                int dx = rng.nextInt(2 * d + 1) - d;
                int dz = rng.nextInt(2 * d + 1) - d;
                List<BlockPoint> column = dx == 0 && dz == 0 ? null
                        : columns.get(Pos.pack(start.x() + dx, 0, start.z() + dz));
                if (column != null) {
                    goal = column.get(rng.nextInt(column.size()));
                }
            }
            if (goal == null) {
                System.out.println("  couldn't find routes to time: the area is too small");
                return null;
            }
            routes[i] = new BlockPoint[] {start, goal};
        }
        return routes;
    }

    /**
     * Walks {@code count} random routes with the executor in the simulator and prints how many
     * stretches failed, and where.
     */
    private static void execPairs(ArrayBlockView blocks, ImportedWorld imported, Settings s,
            int count, int within) {
        System.out.printf("%n  walking %d random routes%s with the executor...%n", count,
                within > 0 ? " with goals within " + within + " blocks" : "");
        BlockPoint[][] routes = randomRoutes(blocks, s.diagonal(), s.diagonalLeaps(), count, within);
        if (routes == null) {
            return;
        }
        BlockTypeWorld world = new BlockTypeWorld(blocks, imported.shapes());
        int stretches = 0;
        int ticks = 0;
        int bumps = 0;
        int skipped = 0;
        int jumps = 0;
        int drops = 0;
        double offset = 0;
        List<String> failures = new ArrayList<>();
        double turned = 0;
        double walkedBlocks = 0;
        long t = System.nanoTime();
        for (BlockPoint[] r : routes) {
            Outcome o = run(blocks, r[0], r[1], s);
            if (!o.result().found()) {
                continue;
            }
            ExecutionPlan plan = plan(world, o.result().path(), lines(blocks, s, o));
            SimRun.Result result = SimRun.run(world, plan, Executor.Settings.DEFAULT, 6000, true);
            for (SimRun.Stretch st : result.stretches()) {
                if (st.recording() == null) {
                    continue;
                }
                List<Scenario.Frame> frames = st.recording().script().frames();
                for (int k = 1; k < frames.size(); k++) {
                    turned += Math.abs(wrap(frames.get(k).yaw() - frames.get(k - 1).yaw()));
                }
                List<double[]> track = st.recording().track();
                for (int k = 1; k < track.size(); k++) {
                    walkedBlocks += Math.hypot(track.get(k)[0] - track.get(k - 1)[0],
                            track.get(k)[2] - track.get(k - 1)[2]);
                }
            }
            stretches += result.stretches().size();
            ticks += result.ticks();
            bumps += result.bumps();
            skipped += result.skipped();
            for (SimRun.Stretch st : result.stretches()) {
                for (ExecutionPlan.Segment seg : plan.segments()) {
                    if (seg.from() >= st.from() && seg.to() <= st.to()) {
                        jumps += seg.kind() == ExecutionPlan.Kind.JUMP_UP ? 1 : 0;
                        drops += seg.kind() == ExecutionPlan.Kind.DROP ? 1 : 0;
                    }
                }
            }
            offset = Math.max(offset, result.maxOffset());
            for (SimRun.Stretch f : result.failures()) {
                failures.add(String.format("%s near %s (route %s -> %s, steps %d to %d)",
                        f.status(), imported.toWorld(plan.nodes().get(f.where()).cell()),
                        imported.toWorld(r[0]), imported.toWorld(r[1]), f.from(), f.to()));
            }
        }
        System.out.printf("    %d stretches (with %d jumps up and %d drops), %d failed, %d moves"
                + " skipped (not supported yet); %.0f s of game time; furthest off the route"
                + " %.2f blocks; %d ticks against a wall; simulated in %.1f s%n",
                stretches, jumps, drops, failures.size(), skipped, ticks / 20.0, offset, bumps,
                (System.nanoTime() - t) / 1e9);
        System.out.printf("    walked %.0f blocks, turning the camera %.0f degrees in all (%.0f"
                + " per 100 blocks)%n", walkedBlocks, turned, 100 * turned / walkedBlocks);
        for (String f : failures.subList(0, Math.min(20, failures.size()))) {
            System.out.println("    " + f);
        }
    }

    /**
     * Walks {@code count} random routes with the recovery on while disturbing the player, the
     * way {@code /goto} plans (around ladders and water where it can, doors shut), and prints
     * how many arrived and what it took.
     */
    private static void disturbedPairs(ArrayBlockView blocks, ImportedWorld imported,
            Settings s, int count, int within, int speedEffect) {
        System.out.printf("%n  walking %d random routes%s with pushes, lag-backs and placed"
                + " blocks%s...%n", count, within > 0 ? " with goals within " + within + " blocks"
                : "", speedEffect > 0 ? " at Speed " + speedEffect : "");
        BlockPoint[][] routes = randomRoutes(blocks, s.diagonal(), s.diagonalLeaps(), count, within);
        if (routes == null) {
            return;
        }
        DefaultCostModel c = s.costs();
        DefaultCostModel avoiding = new DefaultCostModel(c.walk(), c.diagonal(), c.jumpUp(),
                c.dropBase(), c.dropPerBlock(), c.stepInPlace(), 20, 20);
        WorldPathfinder finder = new WorldPathfinder(blocks,
                EntityProfile.DEFAULT.withOpensDoors(false).withDiagonalLeaps(s.diagonalLeaps()),
                s.directions(), avoiding, s.terrain(),
                s.heuristic());
        BlockTypeWorld world = new BlockTypeWorld(blocks, imported.shapes());
        GridRouter router = new GridRouter(finder, world);
        Map<Journey.Status, Integer> outcomes = new java.util.EnumMap<>(Journey.Status.class);
        Map<Journey.Event, Integer> events = new java.util.EnumMap<>(Journey.Event.class);
        List<String> failures = new ArrayList<>();
        int pushes = 0;
        int lags = 0;
        int placed = 0;
        int timeouts = 0;
        long ticks = 0;
        long t = System.nanoTime();
        for (int n = 0; n < routes.length; n++) {
            BlockPoint[] r = routes[n];
            List<PathStep> path = router.find(r[0], r[1], java.util.Set.of(), 0);
            if (path.size() < 2) {
                continue;
            }
            java.util.Random random = new java.util.Random(n);
            BlockPoint a = path.get(0).pos();
            BlockPoint b = path.get(1).pos();
            SimulatedPlayer player = new SimulatedPlayer(world, player(speedEffect),
                    a.x() + 0.5, finder.validator().elevation(a), a.z() + 0.5,
                    (float) astar.movement.exec.Follower.yawToward(a.x(), a.z(), b.x(), b.z()),
                    true);
            Journey journey = new Journey(router, new SimController(player), r[1], path,
                    Journey.Settings.DEFAULT.withStraightLines(!gridSteps));
            int nextPush = 40 + random.nextInt(60);
            int lagAt = 20 + random.nextInt(Math.max(1, path.size() * 3));
            int placeAt = 20 + random.nextInt(Math.max(1, path.size() * 3));
            List<double[]> history = new ArrayList<>();
            List<BlockPoint> walls = new ArrayList<>();
            int tick = 0;
            while (journey.status() == Journey.Status.RUNNING && tick < 12000) {
                history.add(new double[] {player.x(), player.y(), player.z()});
                if (tick == nextPush && player.onGround()) {
                    double angle = random.nextDouble() * 2 * Math.PI;
                    double strength = 0.2 + 0.3 * random.nextDouble();
                    player.setPositionAndVelocity(player.x(), player.y(), player.z(),
                            player.vx() + strength * Math.sin(angle), 0.36,
                            player.vz() + strength * Math.cos(angle));
                    pushes++;
                    nextPush = tick + 60 + random.nextInt(80);
                } else if (tick == nextPush) {
                    nextPush++;
                }
                if (tick == lagAt && history.size() > 10) {
                    double[] back = history.get(history.size() - 11);
                    player.setPositionAndVelocity(back[0], back[1], back[2], 0, 0, 0);
                    lags++;
                }
                if (tick == placeAt) {
                    placed += block(journey, player, blocks, router, r[1], walls) ? 1 : 0;
                }
                journey.tick();
                tick++;
            }
            ticks += tick;
            for (BlockPoint w : walls) {
                blocks.set(w.x(), w.y(), w.z(), astar.pathing.BlockType.AIR);
                blocks.set(w.x(), w.y() + 1, w.z(), astar.pathing.BlockType.AIR);
            }
            Journey.Status status = journey.status() == Journey.Status.RUNNING
                    ? null : journey.status();
            if (status == null) {
                timeouts++;
            } else {
                outcomes.merge(status, 1, Integer::sum);
            }
            journey.events().forEach((k, v) -> events.merge(k, v, Integer::sum));
            if (status != Journey.Status.ARRIVED && status != Journey.Status.UNSUPPORTED) {
                failures.add(String.format("%s near %s (route %s -> %s): %s",
                        status == null ? "TIMED OUT" : status,
                        imported.toWorld(new BlockPoint((int) Math.floor(player.x()),
                                (int) Math.floor(player.y()), (int) Math.floor(player.z()))),
                        imported.toWorld(r[0]), imported.toWorld(r[1]),
                        journey.reason().isEmpty() ? String.join("; ", journey.log().subList(
                                Math.max(0, journey.log().size() - 3), journey.log().size()))
                                : journey.reason()));
            }
        }
        System.out.printf("    %d routes: %s, %d timed out; %d pushes, %d lag-backs, %d blocks"
                + " placed; %.0f s of game time; simulated in %.1f s%n", routes.length,
                outcomes, timeouts, pushes, lags, placed, ticks / 20.0,
                (System.nanoTime() - t) / 1e9);
        System.out.println("    events: " + events);
        for (String f : failures.subList(0, Math.min(20, failures.size()))) {
            System.out.println("    " + f);
        }
    }

    /**
     * Puts a two-high block on the path a few steps ahead of the player, not on the goal and
     * only where there's still a way round; whether it found a place.
     */
    /**
     * The player {@code /goto} drives with the Speed effect at this level (0 for none):
     * movement speed 0.1 plus 20% of it per level.
     */
    private static SimulatedPlayer.Attributes player(int speedEffect) {
        SimulatedPlayer.Attributes a = SimulatedPlayer.Attributes.PLAYER;
        double base = a.movementSpeed();
        return speedEffect == 0 ? a : new SimulatedPlayer.Attributes(
                base + base * 0.2 * speedEffect, a.jumpStrength(), a.stepHeight(), a.gravity(),
                a.sneakingSpeed(), a.frictionModifier(), a.airDragModifier());
    }

    private static boolean block(Journey journey, SimulatedPlayer player, ArrayBlockView blocks,
            GridRouter router, BlockPoint goal, List<BlockPoint> walls) {
        BlockPoint here = router.standingAt(player.x(), player.y(), player.z());
        if (here == null) {
            return false;
        }
        List<PathStep> path = journey.path();
        int node = journey.executor().follower().node();
        for (int i = node + 6; i < Math.min(path.size() - 1, node + 12); i++) {
            BlockPoint c = path.get(i).pos();
            boolean near = Math.abs(c.x() + 0.5 - player.x()) < 2
                    && Math.abs(c.z() + 0.5 - player.z()) < 2;
            if (!c.equals(goal) && !near
                    && blocks.blockAt(c.x(), c.y(), c.z()) == astar.pathing.BlockType.AIR
                    && blocks.blockAt(c.x(), c.y() + 1, c.z()) == astar.pathing.BlockType.AIR) {
                blocks.set(c.x(), c.y(), c.z(), astar.pathing.BlockType.SOLID);
                blocks.set(c.x(), c.y() + 1, c.z(), astar.pathing.BlockType.SOLID);
                if (router.find(here, goal, java.util.Set.of(), 0).isEmpty()) {
                    blocks.set(c.x(), c.y(), c.z(), astar.pathing.BlockType.AIR);
                    blocks.set(c.x(), c.y() + 1, c.z(), astar.pathing.BlockType.AIR);
                    continue;
                }
                walls.add(c);
                return true;
            }
        }
        return false;
    }

    /** Builds the executor's plan for a path and prints what's in it. */
    static void printPlan(BlockView blocks, ImportedWorld imported, List<PathStep> path) {
        long t = System.nanoTime();
        ExecutionPlan plan = new PlanBuilder(new BlockTypeWorld(blocks, imported.shapes())).build(path);
        double ms = (System.nanoTime() - t) / 1e6;
        for (String line : describe(plan, p -> imported.toWorld(p).toString())) {
            System.out.println("  " + line);
        }
        System.out.printf("  plan built in %.0f ms%n", ms);
    }

    /**
     * Walks the route's plan with the executor in the simulator and prints how it went; writes
     * the stretches as scripts to {@code scriptDir} and scores traces in {@code scoreDir}, when
     * given.
     */
    static void printExecution(BlockView blocks, ImportedWorld imported, List<PathStep> path,
            StraightRoute lines, Path scriptDir, Path scoreDir) throws IOException {
        BlockTypeWorld world = new BlockTypeWorld(blocks, imported.shapes());
        ExecutionPlan plan = plan(world, path, lines);
        long t = System.nanoTime();
        boolean record = scriptDir != null || scoreDir != null;
        SimRun.Result r = SimRun.run(world, plan, Executor.Settings.DEFAULT, 6000, record);
        double ms = (System.nanoTime() - t) / 1e6;
        for (String line : describe(r, plan, p -> imported.toWorld(p).toString())) {
            System.out.println("  " + line);
        }
        System.out.printf("  simulated in %.0f ms%n", ms);
        if (scriptDir != null) {
            writeScripts(r, imported.origin(), scriptDir);
        }
        if (scoreDir != null) {
            for (String line : ChoreographyScore.score(r, plan, imported.origin(), scoreDir)) {
                System.out.println("  " + line);
            }
        }
    }

    /** Writes each recorded stretch as a script that starts at its place in the world. */
    private static void writeScripts(SimRun.Result r, BlockPoint origin, Path dir)
            throws IOException {
        Files.createDirectories(dir);
        List<String> names = new ArrayList<>();
        for (SimRun.Stretch s : r.stretches()) {
            if (s.recording() == null) {
                continue;
            }
            String name = ChoreographyScore.scriptName(names.size());
            Scenario script = s.recording().script();
            Scenario.Start a = script.start();
            Scenario inWorld = new Scenario(name, "route steps " + s.from() + " to " + s.to()
                    + ", as the executor walked them in the simulator",
                    new Scenario.Start(a.x() + origin.x(), a.y() + origin.y(),
                            a.z() + origin.z(), a.yaw(), true), script.frames());
            Files.writeString(dir.resolve(name + ".txt"), inWorld.toText());
            names.add(name);
        }
        Files.writeString(dir.resolve("autoplay.txt"), String.join(",", names) + "\n");
        System.out.printf("  wrote %d scripts to %s (autoplay.txt lists them)%n", names.size(),
                dir);
    }

    /** A few lines about a simulated run of a plan. */
    static List<String> describe(SimRun.Result r, ExecutionPlan plan,
            java.util.function.Function<BlockPoint, String> where) {
        List<String> out = new ArrayList<>();
        int walked = 0;
        for (SimRun.Stretch s : r.stretches()) {
            walked += s.to() - s.from();
        }
        List<SimRun.Stretch> failed = r.failures();
        out.add(String.format("executor: %d stretches over %d of %d steps, %d reached their"
                + " end, %d failed; %d moves skipped (not supported yet)", r.stretches().size(),
                walked, plan.nodes().size() - 1, r.stretches().size() - failed.size(),
                failed.size(), r.skipped()));
        out.add(String.format("  %.1f s of game time; furthest off the route %.2f blocks; %d"
                + " ticks against a wall", r.ticks() / 20.0, r.maxOffset(), r.bumps()));
        for (SimRun.Stretch s : failed.subList(0, Math.min(10, failed.size()))) {
            out.add(String.format("  %s near %s (steps %d to %d)", s.status(),
                    where.apply(plan.nodes().get(s.where()).cell()), s.from(), s.to()));
        }
        return out;
    }

    /** A few lines about a plan: its runs, doors, room at the sides and speeds. */
    static List<String> describe(ExecutionPlan plan,
            java.util.function.Function<BlockPoint, String> where) {
        List<String> out = new ArrayList<>();
        Map<ExecutionPlan.Kind, Integer> kinds = new java.util.EnumMap<>(ExecutionPlan.Kind.class);
        for (ExecutionPlan.Segment s : plan.segments()) {
            kinds.merge(s.kind(), 1, Integer::sum);
        }
        out.add(String.format("plan: %d nodes, %.1f blocks, %d segments %s", plan.nodes().size(),
                plan.length(), plan.segments().size(), kinds));
        int doors = 0;
        int edges = 0;
        int floorless = 0;
        ExecutionPlan.Node narrowest = null;
        double sprint = 0;
        double slow = 0;
        double ticks = 0;
        List<ExecutionPlan.Node> nodes = plan.nodes();
        for (int i = 0; i < nodes.size(); i++) {
            ExecutionPlan.Node n = nodes.get(i);
            doors += n.door() != null ? 1 : 0;
            edges += n.left().edge() == ExecutionPlan.Edge.DROP
                    || n.right().edge() == ExecutionPlan.Edge.DROP ? 1 : 0;
            floorless += !n.floor() && (n.via() == MoveType.WALK || n.via() == MoveType.DIAGONAL)
                    ? 1 : 0;
            if (narrowest == null || n.left().width() + n.right().width()
                    < narrowest.left().width() + narrowest.right().width()) {
                narrowest = n;
            }
            if (i > 0) {
                double d = n.distance() - nodes.get(i - 1).distance();
                double v = Math.min(n.speedLimit(), nodes.get(i - 1).speedLimit());
                if (v >= ExecutionPlan.SPRINT_SPEED) {
                    sprint += d;
                } else if (v <= ExecutionPlan.WALK_SPEED) {
                    slow += d;
                }
                ticks += d / Math.max(v, 0.05);
            }
        }
        out.add(String.format("%d doors; %d nodes with an edge to fall off beside them;"
                + " %d walked nodes with no floor found", doors, edges, floorless));
        if (narrowest != null) {
            out.add(String.format("narrowest: %.2f left + %.2f right at %s (%s / %s)",
                    narrowest.left().width(), narrowest.right().width(),
                    where.apply(narrowest.cell()), narrowest.left().edge(),
                    narrowest.right().edge()));
        }
        out.add(String.format("speed limits: %.0f%% of the way at sprint, %.0f%% at walking or"
                + " slower; about %.0f s at those limits", 100 * sprint / Math.max(plan.length(),
                1e-9), 100 * slow / Math.max(plan.length(), 1e-9), ticks / 20));
        return out;
    }

    private static void report(BlockView blocks, String label, Outcome r) {
        SearchResult res = r.result();
        System.out.printf("  %s: %d steps, %.1f blocks, cost %.1f, %d jumps up, %d descents,"
                        + " %d drops, %d swims, %d climbs, %d doors, %,d nodes expanded%n",
                label, res.path().size() - 1, r.length(), res.cost(), r.count(MoveType.JUMP_UP),
                r.descents(), r.count(MoveType.DROP) - r.descents(), r.count(MoveType.SWIM), r.count(MoveType.CLIMB),
                doors(blocks, r), res.expanded());
        System.out.printf("  search %.1f ms, smoothing %.1f ms (one cold run; use --bench for"
                        + " steady timings); smoothed to %d waypoints, %.1f blocks%n",
                r.searchMs(), r.smoothMs(), r.waypoints().size(),
                PathSmoother.length(PathSmoother.positions(r.waypoints())));
        if (r.hpa() != null) {
            PortalGraph g = r.hpa().graph();
            ClusterLayout l = g.layout();
            System.out.printf("  hierarchical: %,d clusters of %d x %d, %,d entrances, %,d portals,"
                            + " %,d edges (%.1f MB), built once in %.0f ms%n",
                    l.count(), l.size(), l.size(), g.entranceCount(), g.portalCount(),
                    g.edgeCount(), g.bytes() / 1e6, r.hpa().buildMs());
            HierarchicalPathfinder.QueryStats q = r.stages();
            if (q.plain()) {
                System.out.printf("  this query: close enough for plain A* (%,d expanded, %.1f ms)"
                        + "%n", q.plainExpanded(), q.plainMs());
                return;
            }
            if (q.plainExpanded() > 0) {
                System.out.printf("  plain A* tried first and gave up after %,d expanded (%.1f ms)%n",
                        q.plainExpanded(), q.plainMs());
            }
            System.out.printf("  this query: insert %.1f ms (%,d cells), portals %.1f ms (%,d expanded),"
                            + " refine %.1f ms (%,d expanded)%n",
                    q.insertMs(), q.insertSettled(), q.abstractMs(), q.abstractExpanded(),
                    q.refineMs(), q.refineExpanded());
        }
    }

    /** How many steps of the path stand in a doorway (the lower half of a door, or a gate). */
    static long doors(BlockView blocks, Outcome r) {
        return r.result().path().stream()
                .filter(s -> blocks.blockAt(s.pos().x(), s.pos().y(), s.pos().z()).opens())
                .count();
    }

    private static void printComparison(BlockView blocks, Settings a, Outcome ra, Settings b,
            Outcome rb) {
        System.out.println();
        System.out.println("  Comparison (same start and goal):");
        System.out.printf("    %-22s %14s %14s%n", "", "default", "yours");
        row("steps", ra.result().path().size() - 1, rb.result().path().size() - 1);
        rowD("blocks walked", ra.length(), rb.length());
        row("jumps up", ra.count(MoveType.JUMP_UP), rb.count(MoveType.JUMP_UP));
        row("descents (1 down)", ra.descents(), rb.descents());
        row("drops (2+ down)", ra.count(MoveType.DROP) - ra.descents(),
                rb.count(MoveType.DROP) - rb.descents());
        row("swims", ra.count(MoveType.SWIM), rb.count(MoveType.SWIM));
        row("climbs", ra.count(MoveType.CLIMB), rb.count(MoveType.CLIMB));
        row("doors", doors(blocks, ra), doors(blocks, rb));
        row("waypoints", ra.waypoints().size(), rb.waypoints().size());
        row("nodes expanded", ra.result().expanded(), rb.result().expanded());
        // Each path priced under the *default* move weights (no turn cost), so the numbers
        // are comparable.
        double costA = priceUnderDefaults(blocks, ra.result().path());
        double costB = priceUnderDefaults(blocks, rb.result().path());
        rowD("cost (default weights)", costA, costB);
        rowD("cost gap vs default %", 0, gapPercent(costA, costB));
        // One run each, one after the other: the second has a warmer JIT (and, with the same
        // graph settings, a move cache the first filled). --bench times them fairly.
        rowD("search ms (one run)", ra.searchMs(), rb.searchMs());
        rowD("graph build ms (once)", buildMs(ra), buildMs(rb));
        System.out.println("    default: " + a.describe());
        System.out.println("    yours: " + b.describe());
        System.out.println("  images: overview-default.png / overview-custom.png (and profile, levels)");
    }

    private static double buildMs(Outcome r) {
        return r.hpa() == null ? 0 : r.hpa().buildMs();
    }

    /** How much dearer {@code cost} is than {@code cheapest}, in percent. */
    private static double gapPercent(double cheapest, double cost) {
        return cheapest == 0 ? 0 : (cost / cheapest - 1) * 100;
    }

    /** What a path would cost under the default weights. */
    static double priceUnderDefaults(BlockView blocks, List<PathStep> path) {
        CostModel defaults = new TerrainCostModel(DefaultCostModel.DEFAULT, blocks,
                EntityProfile.DEFAULT, Settings.TERRAIN);
        double total = 0;
        for (int i = 1; i < path.size(); i++) {
            total += defaults.cost(path.get(i - 1).pos().pack(),
                    path.get(i).pos().pack(), path.get(i).via());
        }
        return total;
    }

    private static void row(String label, long a, long b) {
        System.out.printf("    %-22s %,14d %,14d%n", label, a, b);
    }

    private static void rowD(String label, double a, double b) {
        System.out.printf("    %-22s %14.1f %14.1f%n", label, a, b);
    }

    /** Benchmark warm-up: at least this many runs, then more until about 3 s have passed. */
    private static final int WARMUP_MIN_RUNS = 10;
    private static final int WARMUP_MAX_RUNS = 2000;
    private static final long WARMUP_NANOS = 3_000_000_000L;

    /**
     * Times the same search repeatedly after warming up the JVM, and prints stats. With more than
     * one setting (--compare), the runs take turns, so JIT and garbage collection hit all alike.
     * Hierarchical settings also time building their graph, which happens once per map.
     */
    private static void benchmark(ArrayBlockView blocks, BlockPoint start, BlockPoint goal,
            List<Settings> settings, int runs) {
        System.out.printf("%n  benchmark: warming up, then %d timed runs%s...%n", runs,
                settings.size() > 1 ? " of each, taking turns" : "");
        double[] buildMedian = new double[settings.size()];
        for (int k = 0; k < settings.size(); k++) {
            Settings s = settings.get(k);
            if (s.hierarchical()) {
                double[] builds = new double[3];
                for (int i = 0; i < builds.length; i++) {
                    builds[i] = buildGraph(blocks, s).buildMs();
                }
                Arrays.sort(builds);
                buildMedian[k] = builds[1];
                System.out.printf("  graph build (%s): median %.0f ms of %d%n", label(s),
                        builds[1], builds.length);
            }
        }
        // The JIT only finishes compiling the search after a few dozen runs of a short route
        // (until then a run can take 10x as long), so warm up for a while, not a set count.
        long warmStart = System.nanoTime();
        int warmup = 0;
        while (warmup < WARMUP_MIN_RUNS
                || (System.nanoTime() - warmStart < WARMUP_NANOS && warmup < WARMUP_MAX_RUNS)) {
            for (Settings s : settings) {
                run(blocks, start, goal, s);
            }
            warmup++;
        }
        System.out.printf("  warmed up with %d runs in %.1f s%n", warmup, secs(warmStart));
        int n = settings.size();
        double[][] search = new double[n][runs];
        double[][] smooth = new double[n][runs];
        Outcome[] lastRun = new Outcome[n];
        double[][] stages = new double[n][3 * runs];
        for (int i = 0; i < runs; i++) {
            for (int k = 0; k < n; k++) {
                Outcome r = run(blocks, start, goal, settings.get(k));
                search[k][i] = r.searchMs();
                smooth[k][i] = r.smoothMs();
                lastRun[k] = r;
                if (r.stages() != null) {
                    stages[k][i] = r.stages().insertMs();
                    stages[k][runs + i] = r.stages().abstractMs();
                    stages[k][2 * runs + i] = r.stages().refineMs();
                }
            }
        }
        double[] medians = new double[n];
        for (int k = 0; k < n; k++) {
            Arrays.sort(search[k]);
            Arrays.sort(smooth[k]);
            double median = search[k][runs / 2];
            medians[k] = median;
            System.out.printf("  %s%n", label(settings.get(k)));
            System.out.printf("    search:    median %.1f ms, fastest %.1f ms, slowest %.1f ms%n",
                    median, search[k][0], search[k][runs - 1]);
            if (lastRun[k].stages() != null) {
                System.out.printf("    stages:    insert %.1f ms, portals %.1f ms, refine %.1f ms"
                                + " (medians)%n",
                        median(stages[k], 0, runs), median(stages[k], runs, runs),
                        median(stages[k], 2 * runs, runs));
            }
            System.out.printf("    smoothing: median %.1f ms%n", smooth[k][runs / 2]);
            System.out.printf("    speed:     %,.0f nodes expanded per millisecond (median run;"
                    + " %,d expanded)%n", lastRun[k].result().expanded() / Math.max(1e-9, median),
                    lastRun[k].result().expanded());
        }
        if (n == 2) {
            double saved = medians[0] - medians[1];
            System.out.printf("  yours is %.1fx the speed of the default%n",
                    medians[0] / Math.max(1e-9, medians[1]));
            if (settings.get(1).hierarchical() && saved > 0) {
                System.out.printf("  the graph pays for itself after about %.0f searches like this"
                        + " one%n", Math.ceil(buildMedian[1] / saved));
            }
        }
        System.out.println("  (big swings between runs are usually garbage collection or other"
                + " programs using the CPU)");
    }

    /** The 95th percentile of sorted values (nearest rank): the maximum only from 20 values up. */
    static double p95(double[] sorted) {
        return sorted[Math.max(0, (int) Math.ceil(0.95 * sorted.length) - 1)];
    }

    private static double median(double[] values, int from, int count) {
        double[] v = Arrays.copyOfRange(values, from, from + count);
        Arrays.sort(v);
        return v[count / 2];
    }

    private static String label(Settings s) {
        return s.isDefault() ? "default (plain A*)" : "yours (" + s.describe() + ")";
    }

    /**
     * Times {@code count} routes between random points of the main area (the same points every
     * time), with the default settings and yours, and prints the speed-up and the cost gap by
     * route length. One long route flatters hierarchical search; short ones are where it loses.
     * With {@code within} above 0, each goal is within that many blocks of its start (on x and
     * z), at a distance picked evenly from 1 to {@code within}; random points are mostly far apart.
     */
    private static void pairs(ArrayBlockView blocks, Settings base, Settings mine, int count,
            int within) {
        System.out.printf("%n  %d random routes%s, default against yours...%n", count,
                within > 0 ? " with goals within " + within + " blocks" : "");
        BlockPoint[][] routes = randomRoutes(blocks, base.diagonal(), true, count, within);
        if (routes == null) {
            return;
        }
        if (mine.hierarchical()) {
            hierarchical(blocks, mine); // build outside the timings
        }
        for (int i = 0; i < Math.min(count, 10); i++) { // warm up
            run(blocks, routes[i][0], routes[i][1], base);
            run(blocks, routes[i][0], routes[i][1], mine);
        }
        String[] names = {"under 32 steps", "32 to 127 steps", "128 to 255 steps",
            "256 steps or more", "all"};
        List<List<double[]>> buckets = List.of(new ArrayList<>(), new ArrayList<>(),
                new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        double totalBase = 0;
        double totalMine = 0;
        long nodesBase = 0;
        long nodesMine = 0;
        double fewest = 1;
        for (BlockPoint[] r : routes) {
            Outcome a = run(blocks, r[0], r[1], base);
            Outcome b = run(blocks, r[0], r[1], mine);
            if (!a.result().found() || !b.result().found()) {
                System.out.printf("  %s -> %s: found by default %s, by yours %s%n", r[0], r[1],
                        a.result().found(), b.result().found());
                continue;
            }
            totalBase += a.searchMs();
            totalMine += b.searchMs();
            nodesBase += a.result().expanded();
            nodesMine += b.result().expanded();
            fewest = Math.min(fewest, b.result().expanded() / (double) a.result().expanded());
            int steps = a.result().path().size() - 1;
            double[] row = {a.searchMs() / Math.max(1e-6, b.searchMs()),
                    gapPercent(priceUnderDefaults(blocks, a.result().path()),
                            priceUnderDefaults(blocks, b.result().path())),
                    b.stages() != null && b.stages().plain() ? 1 : 0};
            buckets.get(steps < 32 ? 0 : steps < 128 ? 1 : steps < 256 ? 2 : 3).add(row);
            buckets.get(4).add(row);
        }
        System.out.printf("    %-20s %7s %26s %22s %9s%n", "route length", "routes",
                "speed-up worst/median/p95", "cost gap % median/p95", "plain A*");
        for (int b = 0; b < names.length; b++) {
            List<double[]> rows = buckets.get(b);
            if (rows.isEmpty()) {
                continue;
            }
            double[] speed = rows.stream().mapToDouble(x -> x[0]).sorted().toArray();
            double[] gap = rows.stream().mapToDouble(x -> x[1]).sorted().toArray();
            long plain = rows.stream().filter(x -> x[2] > 0).count();
            System.out.printf("    %-20s %7d %10.1fx %6.1fx %6.1fx %12.1f %8.1f %9d%n", names[b],
                    rows.size(), speed[0], speed[speed.length / 2], p95(speed),
                    gap[gap.length / 2], p95(gap), plain);
        }
        System.out.printf("    total search time: default %.0f ms, yours %.0f ms%n", totalBase,
                totalMine);
        System.out.printf("    nodes expanded: default %,d, yours %,d (%+.1f%%; best route %+.1f%%)%n",
                nodesBase, nodesMine, gapPercent(nodesBase, nodesMine), 100 * (fewest - 1));
        System.out.println("    (speed-up: default time / yours, per route; p95 is the 95th"
                + " percentile; cost gap: how much dearer yours is, priced at default weights;"
                + " plain A*: routes hierarchical search answered with plain A*)");
    }

    private static void writeImages(ArrayBlockView blocks, Outcome r, BlockPoint o, String name,
            Path out, String suffix) throws IOException {
        String tag = suffix.isEmpty() ? "" : " (" + suffix.substring(1) + ")";
        write(RouteImages.overview(blocks, r.result().path(), r.waypoints(), o,
                name + ": route from above" + tag, 1400), out.resolve("overview" + suffix + ".png"));
        write(RouteImages.overview(blocks, r.result().path(), r.waypoints(), o,
                name + ": floor heights from above" + tag, 1400, HeightMap.Style.HEIGHT),
                out.resolve("heights" + suffix + ".png"));
        write(RouteImages.profile(r.result().path(), o, name + ": height along the route" + tag),
                out.resolve("profile" + suffix + ".png"));
        write(RouteImages.levels(blocks, r.result(), r.waypoints(), 6, 1500,
                name + ": the levels the route uses most (in-game y)" + tag, o),
                out.resolve("levels" + suffix + ".png"));
    }

    private static void write(BufferedImage img, Path file) throws IOException {
        ImageIO.write(img, "png", file.toFile());
        System.out.println("  wrote " + file);
    }

    private static BlockPoint parse(String s) {
        String[] p = s.split(",");
        if (p.length != 3) {
            throw new IllegalArgumentException("Coordinates must be x,y,z, got " + s);
        }
        return new BlockPoint(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()),
                Integer.parseInt(p[2].trim()));
    }

    private static String num(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format("%.3f", v).replaceAll("0+$", "");
    }

    /**
     * On a slab, stairs or other low floor, the feet are in that block's cell (y 206.5 is cell
     * 206), but it's natural to give the block above. Moves such a point down onto the floor.
     */
    private static BlockPoint ontoLowFloor(WorldPathfinder finder, ImportedWorld imported,
            BlockPoint p, String which) {
        BlockPoint below = p.offset(0, -1, 0);
        if (finder.canStand(p) || !finder.canStand(below)
                || !imported.world().blockAt(below.x(), below.y(), below.z()).isPartialFloor()) {
            return p;
        }
        System.out.printf("  the %s is on a low block: using %s, where the feet are%n", which,
                imported.toWorld(below));
        return below;
    }

    private static double secs(long start) {
        return (System.nanoTime() - start) / 1e9;
    }
}
