package astar.pathing;

import astar.core.AStarSearch;
import astar.core.BlockPoint;
import astar.core.CostModel;
import astar.core.DefaultCostModel;
import astar.core.Heuristic;
import astar.core.Heuristics;
import astar.core.MoveSource;
import astar.core.SearchListener;
import astar.core.SearchResult;
import astar.core.TurnPenalty;
import astar.core.TurningCostModel;
import java.util.concurrent.CompletableFuture;

/**
 * Ties a world, an entity profile, a cost model and a heuristic together, and checks that the
 * start and goal can be stood on before searching.
 *
 * <p>Moves are priced by the cost model plus {@link TerrainCosts} for doors, slow and damaging
 * blocks ({@link TerrainCosts#DEFAULT} unless given; {@link TerrainCosts#NONE} turns them off).
 *
 * <p>On an {@link ArrayBlockView}, searches run over a {@link NavGraph}: every move reachable
 * from the start, worked out and priced once, and shared by later searches. The first search
 * builds it. After an edit, the next search patches it: only the cells near the changed blocks
 * get their moves worked out again ({@link NavGraph#patch}). When that isn't possible (too many
 * changes), it builds it again, before searching if the last build took at most
 * {@link #GRAPH_SYNC_LIMIT_MS}, else in the background while searches work out their moves as
 * they go. Paths are the same either way, only faster.
 */
public final class WorldPathfinder {
    private final BlockView world;
    private final MoveValidator validator;
    private final MoveSource moves;
    private final CostModel costs;
    private final Heuristic heuristic;
    private final Clearance clearance;
    private final double wallCost;

    /** Graph builds up to this long happen before the search that needs them. */
    public static final double GRAPH_SYNC_LIMIT_MS = 60;
    private boolean useGraph;
    private double graphSyncLimitMs = GRAPH_SYNC_LIMIT_MS;
    private volatile NavGraph graph;
    private CompletableFuture<NavGraph> pendingGraph;
    private long lastGraphSeed = Long.MIN_VALUE;
    private int lastGraphVersion;
    private int graphBuilds;

    /**
     * Uses {@link DefaultCostModel#DEFAULT}, {@link TerrainCosts#DEFAULT} and the matching
     * admissible heuristic.
     */
    public WorldPathfinder(BlockView world, EntityProfile profile, boolean diagonal) {
        this(world, profile, diagonal, DefaultCostModel.DEFAULT,
                diagonal ? Heuristics.OCTILE_XZ : Heuristics.MANHATTAN_XZ);
    }

    /** With {@link TerrainCosts#DEFAULT} on top of the given move costs. */
    public WorldPathfinder(BlockView world, EntityProfile profile, boolean diagonal,
            CostModel costs, Heuristic heuristic) {
        this(world, profile, diagonal, costs, TerrainCosts.DEFAULT, heuristic);
    }

    public WorldPathfinder(BlockView world, EntityProfile profile, boolean diagonal,
            CostModel costs, TerrainCosts terrain, Heuristic heuristic) {
        this(world, profile, diagonal ? 8 : 4, costs, terrain, heuristic);
    }

    /**
     * Heading in 4, 8 or 16 directions, with {@link DefaultCostModel#DEFAULT},
     * {@link TerrainCosts#DEFAULT} and the matching admissible heuristic ({@link #heuristicFor}).
     */
    public WorldPathfinder(BlockView world, EntityProfile profile, int directions) {
        this(world, profile, directions, DefaultCostModel.DEFAULT, TerrainCosts.DEFAULT,
                heuristicFor(directions));
    }

    /** @param directions 4, 8 or 16; 16 adds level moves one across and two along */
    public WorldPathfinder(BlockView world, EntityProfile profile, int directions,
            CostModel costs, TerrainCosts terrain, Heuristic heuristic) {
        this(world, profile, directions, costs, terrain, heuristic, 0);
    }

    /**
     * As above, also charging {@code turnCost} for every 45 degrees the heading turns (see
     * {@link TurnPenalty}); 0 charges nothing. Only plain A* searches see turn costs.
     */
    public WorldPathfinder(BlockView world, EntityProfile profile, int directions,
            CostModel costs, TerrainCosts terrain, Heuristic heuristic, double turnCost) {
        this(world, profile, directions, costs, terrain, heuristic, turnCost, false);
    }

    /**
     * As above; with {@code exactTurns}, searches keep a node per heading, so they return the
     * cheapest path counting turns, at the price of searching several times as many nodes (see
     * {@link TurningCostModel#exact}).
     */
    public WorldPathfinder(BlockView world, EntityProfile profile, int directions,
            CostModel costs, TerrainCosts terrain, Heuristic heuristic, double turnCost,
            boolean exactTurns) {
        this.world = world;
        this.validator = new MoveValidator(world, profile);
        MoveSource direct = new BlockMoveSource(validator, directions);
        // On an array world, each cell's moves are worked out once (see MoveCache).
        this.moves = world instanceof ArrayBlockView a ? new MoveCache(direct, a) : direct;
        this.clearance = new Clearance(validator);
        CostModel priced = terrain.isNone() ? costs
                : new TerrainCostModel(costs, world, profile, terrain, clearance);
        this.wallCost = terrain.wall();
        this.costs = turnCost > 0 ? new TurnPenalty(priced, turnCost, exactTurns) : priced;
        this.heuristic = heuristic;
        this.useGraph = world instanceof ArrayBlockView;
    }

    /**
     * Whether searches use a {@link NavGraph} (on by default for an {@link ArrayBlockView});
     * off, every search works out its moves as it goes.
     */
    public WorldPathfinder useGraph(boolean on) {
        synchronized (this) {
            useGraph = on && world instanceof ArrayBlockView;
            if (!useGraph) {
                graph = null;
            }
        }
        return this;
    }

    /** For tests: rebuilds slower than this go to the background (-1: all but the first). */
    synchronized void graphSyncLimit(double ms) {
        graphSyncLimitMs = ms;
    }

    public synchronized boolean usesGraph() {
        return useGraph;
    }

    /** The graph searches use, or null when there is none yet or it is out of date. */
    public NavGraph graph() {
        NavGraph g = graph;
        return g != null && !g.stale() ? g : null;
    }

    /** How many graph builds have been started. */
    /** Takes a graph made elsewhere ({@link NavCache#read}) as the kept one. */
    public synchronized void adopt(NavGraph g) {
        graph = g;
        graphBuilds++;
    }

    public synchronized int graphBuilds() {
        return graphBuilds;
    }

    /** Whether a graph is being built in the background. */
    public synchronized boolean buildingGraph() {
        return pendingGraph != null && !pendingGraph.isDone();
    }

    /**
     * A graph that covers {@code seed} (packed), built now unless the current one does: for
     * things built on top of it, like {@link Landmarks}.
     */
    public synchronized NavGraph graphCovering(long seed) {
        NavGraph g = graph();
        if (g == null && graph != null) {
            g = graph.patch(); // after an edit: only the cells near it
            if (g != null && useGraph) {
                graph = g;
            }
        }
        if (g == null || !g.covers(seed)) {
            graphBuilds++;
            g = NavGraph.build(this, seed);
            if (useGraph) {
                graph = g;
            }
        }
        return g;
    }

    /**
     * The graph for a search from {@code start}, or null to search without one: building or
     * rebuilding it first (see the class notes).
     */
    private synchronized NavGraph graphFor(long start) {
        if (!useGraph) {
            return null;
        }
        if (pendingGraph != null && pendingGraph.isDone()) {
            NavGraph done = pendingGraph.getNow(null);
            pendingGraph = null;
            if (done != null) {
                graph = done;
            }
        }
        NavGraph g = graph;
        if (g != null && g.stale()) {
            // After an edit: patch the cells near it, if the world can say where it was.
            NavGraph patched = g.patch();
            if (patched != null) {
                graph = patched;
                g = patched;
            }
        }
        if (g != null && !g.stale() && g.covers(start)) {
            return g;
        }
        if (pendingGraph != null) {
            return null;
        }
        int version = worldVersion();
        // Once per start and world version: a start the flood can't grow from stays uncovered.
        if (start == lastGraphSeed && version == lastGraphVersion) {
            return null;
        }
        lastGraphSeed = start;
        lastGraphVersion = version;
        graphBuilds++;
        if (g == null || g.fullBuildMs() <= graphSyncLimitMs) {
            g = NavGraph.build(this, start);
            graph = g;
            return g.covers(start) ? g : null;
        }
        pendingGraph = CompletableFuture.supplyAsync(() -> NavGraph.build(this, start));
        return null;
    }

    private WorldPathfinder(WorldPathfinder other, Heuristic heuristic) {
        this.world = other.world;
        this.validator = other.validator;
        this.moves = other.moves;
        this.costs = other.costs;
        this.heuristic = heuristic;
        this.clearance = other.clearance;
        this.wallCost = other.wallCost;
        synchronized (other) {
            this.useGraph = other.useGraph;
            this.graph = other.graph;
        }
    }

    /**
     * The same pathfinder with another heuristic, starting with this one's move graph (if it
     * has one), so it isn't built twice: e.g. {@link Landmarks} built on this one.
     */
    public WorldPathfinder withHeuristic(Heuristic heuristic) {
        return new WorldPathfinder(this, heuristic);
    }

    /** The admissible heuristic for moves in 4, 8 or 16 directions. */
    public static Heuristic heuristicFor(int directions) {
        return switch (directions) {
            case 4 -> Heuristics.MANHATTAN_XZ;
            case 8 -> Heuristics.OCTILE_XZ;
            case 16 -> Heuristics.SIXTEEN_XZ;
            default -> throw new IllegalArgumentException("directions must be 4, 8 or 16");
        };
    }

    /** The full cost model searches use, terrain included. */
    public CostModel costs() {
        return costs;
    }

    BlockView world() {
        return world;
    }

    /**
     * The world's change counter ({@link ArrayBlockView#version()}), so things built from its
     * moves can tell they're out of date; always 0 for other kinds of world.
     */
    public int worldVersion() {
        return world instanceof ArrayBlockView a ? a.version() : 0;
    }

    public MoveValidator validator() {
        return validator;
    }

    /** The moves searches use: this entity's, 4-, 8- or 16-way. */
    public MoveSource moves() {
        return moves;
    }

    public Heuristic heuristic() {
        return heuristic;
    }

    /** How much room there is around each cell, as the wall cost measures it. */
    public Clearance clearance() {
        return clearance;
    }

    /** Whether searches charge for steps near walls and ledges ({@link TerrainCosts#wall}). */
    public boolean keepsRoom() {
        return wallCost > 0;
    }

    /**
     * Smooths this pathfinder's paths using the same world and entity rules; with a wall cost,
     * its lines keep as far off walls and ledges as the path did.
     */
    public PathSmoother smoother() {
        PathSmoother s = new PathSmoother(validator);
        return wallCost > 0 ? s.keepingRoom(clearance) : s;
    }

    public boolean canStand(BlockPoint p) {
        return validator.canStand(p.x(), p.y(), p.z());
    }

    public SearchResult find(BlockPoint start, BlockPoint goal) {
        return find(start, goal, SearchListener.NONE);
    }

    /** Runs a search to the end, or reports no path if the start or goal can't be stood on. */
    public SearchResult find(BlockPoint start, BlockPoint goal, SearchListener listener) {
        if (!canStand(start) || !canStand(goal)) {
            return SearchResult.noPath();
        }
        AStarSearch search = search(start, goal, listener);
        search.runToEnd();
        return search.result();
    }

    /**
     * A search the caller steps itself, e.g. a few hundred expansions per tick.
     *
     * @throws IllegalArgumentException if the start or goal can't be stood on
     */
    public AStarSearch search(BlockPoint start, BlockPoint goal, SearchListener listener) {
        if (!canStand(start)) {
            throw new IllegalArgumentException("Can't stand at start " + start);
        }
        if (!canStand(goal)) {
            throw new IllegalArgumentException("Can't stand at goal " + goal);
        }
        NavGraph g = graphFor(start.pack());
        return g != null ? new AStarSearch(start, goal, g, costs, heuristic, listener)
                : new AStarSearch(start, goal, moves, costs, heuristic, listener);
    }
}
