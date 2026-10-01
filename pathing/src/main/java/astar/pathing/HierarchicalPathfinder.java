package astar.pathing;

import astar.core.AStarSearch;
import astar.core.BlockPoint;
import astar.core.CostModel;
import astar.core.MoveType;
import astar.core.PathStep;
import astar.core.Pos;
import astar.core.SearchListener;
import astar.core.SearchResult;
import astar.core.SearchResult.Status;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Hierarchical A* (HPA*) for long routes on big maps. The world is split into clusters (see
 * {@link ClusterLayout}), and a {@link PortalGraph} of the cells where moves cross between
 * clusters is built once. Each query then runs in three stages:
 *
 * <ol>
 *   <li><b>Insert:</b> a {@link ClusterDijkstra} from the start prices its way to the portals of
 *       its cluster, and a {@link GoalDijkstra}, backwards from the goal, prices the way there from
 *       every portal of the goal's cluster at once.
 *   <li><b>Abstract search:</b> an ordinary {@link AStarSearch} over the portals, with the same
 *       heuristic as the plain search. It stays admissible: every edge is made of real moves.
 *   <li><b>Refine:</b> each hop becomes real steps: a move between clusters is one step, and a
 *       hop inside a cluster is the cheapest path inside it, from another cluster Dijkstra.
 * </ol>
 *
 * <p>Close together, the hierarchy costs more than it saves: inserting the start and goal alone
 * can take longer than a plain search, and the path detours through portals. So when the goal
 * is within {@link #shortRange()} blocks (by the heuristic), a plain A* runs first, with a budget
 * of {@link #shortBudget()} expansions. If it finishes within the budget, its path (the cheapest)
 * is the answer; if not, say around a long wall, the hierarchical search takes over.
 *
 * <p>The result is the same kind of {@link SearchResult} as {@link WorldPathfinder#find}, so
 * smoothing, analysis and drawing work unchanged. It finds a path exactly when one exists, but
 * the path can cost a little more than the cheapest: it goes through the portals.
 *
 * <p>If the world is edited after the graph is built, the next query (or {@link #graph()})
 * rebuilds it first: an old graph could route through a new wall, or miss a new way through.
 *
 * <p>Not thread-safe: queries share scratch arrays. The graph itself is read-only once built.
 */
public final class HierarchicalPathfinder {
    private final WorldPathfinder finder;
    private final ClusterLayout layout;
    private PortalGraph graph;
    private double buildMs;
    private int builtVersion; // the world's version when the graph was built
    private final ClusterDijkstra dijkstra;
    private final GoalDijkstra toGoal;
    private long[] goalSources = new long[64];
    private double shortRange;
    private int shortBudget;
    private QueryStats last = QueryStats.NONE;

    /**
     * How the last query's work split: the plain search tried first on short routes, then the
     * three hierarchical stages (all zero when the plain search answered, which {@code plain}
     * says).
     */
    public record QueryStats(boolean plain, int plainExpanded, int insertSettled,
            int abstractExpanded, int refineExpanded, double plainMs, double insertMs,
            double abstractMs, double refineMs) {
        static final QueryStats NONE = new QueryStats(false, 0, 0, 0, 0, 0, 0, 0, 0);

        public int total() {
            return plainExpanded + insertSettled + abstractExpanded + refineExpanded;
        }

        public double totalMs() {
            return plainMs + insertMs + abstractMs + refineMs;
        }
    }

    /** Plain A* is tried first within this many cluster widths (by the heuristic). */
    public static final int SHORT_RANGE_CLUSTERS = 4;

    /**
     * Expansions per block of short range that the plain search may use before giving up: about
     * what a hierarchical query costs on the Dwarven Mines, so a failed try at most doubles it.
     */
    public static final int SHORT_BUDGET_PER_BLOCK = 32;

    private HierarchicalPathfinder(WorldPathfinder finder, ClusterLayout layout) {
        this.finder = finder;
        this.layout = layout;
        buildGraph();
        this.dijkstra = new ClusterDijkstra(layout, finder.moves(), finder.costs());
        this.toGoal = new GoalDijkstra(layout, finder.moves(), finder.costs());
        double range = defaultShortRange(layout.size());
        shortRoutes(range, defaultShortBudget(range));
    }

    /** How far plain A* is tried first by default, in blocks: a few clusters. */
    public static double defaultShortRange(int clusterSize) {
        return SHORT_RANGE_CLUSTERS * clusterSize;
    }

    /** The default expansion budget for plain A* over a short range. */
    public static int defaultShortBudget(double range) {
        return (int) Math.min(Integer.MAX_VALUE, Math.ceil(SHORT_BUDGET_PER_BLOCK * range));
    }

    /** Builds the portal graph for this pathfinder's world, entity and costs. */
    public static HierarchicalPathfinder build(WorldPathfinder finder, ClusterLayout layout) {
        if (finder.world() instanceof ArrayBlockView w && (w.sizeX() != layout.sizeX()
                || w.sizeY() != layout.sizeY() || w.sizeZ() != layout.sizeZ())) {
            throw new IllegalArgumentException("the cluster layout is for a " + layout.sizeX()
                    + " x " + layout.sizeY() + " x " + layout.sizeZ() + " world, not this one");
        }
        return new HierarchicalPathfinder(finder, layout);
    }

    private void buildGraph() {
        long t = System.nanoTime();
        builtVersion = finder.worldVersion();
        graph = PortalGraph.build(layout, finder.moves(), finder.costs());
        buildMs = (System.nanoTime() - t) / 1e6;
    }

    /** Rebuilds the graph if the world changed since it was built. */
    private void refresh() {
        if (finder.worldVersion() != builtVersion) {
            buildGraph();
        }
    }

    public static HierarchicalPathfinder build(WorldPathfinder finder, ArrayBlockView world,
            int clusterSize) {
        return build(finder, ClusterLayout.of(world, clusterSize));
    }

    /** The portal graph, rebuilt first if the world changed. */
    public PortalGraph graph() {
        refresh();
        return graph;
    }

    public ClusterLayout layout() {
        return layout;
    }

    /** How long building the graph took (the latest build, if it was rebuilt). */
    public double buildMs() {
        return buildMs;
    }

    public WorldPathfinder pathfinder() {
        return finder;
    }

    public PathSmoother smoother() {
        return finder.smoother();
    }

    public QueryStats lastQuery() {
        return last;
    }

    /**
     * Routes whose goal is within {@code range} blocks of the start (by the heuristic) try plain
     * A* first, for up to {@code budget} expansions. A range of 0 always uses the hierarchy.
     */
    public HierarchicalPathfinder shortRoutes(double range, int budget) {
        if (range < 0 || budget < 0) {
            throw new IllegalArgumentException("range and budget can't be negative");
        }
        this.shortRange = range;
        this.shortBudget = budget;
        return this;
    }

    public double shortRange() {
        return shortRange;
    }

    public int shortBudget() {
        return shortBudget;
    }

    /**
     * A path from start to goal, or no path if either can't be stood on or none exists. Its
     * {@code expanded} counts the work of all three stages; {@code closed} and {@code open} are
     * the abstract search's: portals (and the start and goal).
     */
    public SearchResult find(BlockPoint start, BlockPoint goal) {
        last = QueryStats.NONE;
        if (!finder.canStand(start) || !finder.canStand(goal)) {
            return SearchResult.noPath();
        }
        long s = start.pack();
        long g = goal.pack();
        CostModel costs = finder.costs();
        refresh();

        // 0. Close together, a plain search is usually quicker, and its path the cheapest.
        long tp = System.nanoTime();
        int plainExpanded = 0;
        if (shortBudget > 0 && shortRange > 0 && finder.heuristic().between(s, g) <= shortRange) {
            AStarSearch plain = finder.search(start, goal, SearchListener.NONE);
            Status status = plain.run(shortBudget);
            plainExpanded = plain.expanded();
            if (status != Status.RUNNING) {
                last = new QueryStats(true, plainExpanded, 0, 0, 0, ms(tp, System.nanoTime()),
                        0, 0, 0);
                SearchResult r = plain.result();
                return new SearchResult(r.status(), r.path(), r.cost(), r.closed(), r.open(),
                        last.total());
            }
        }

        // 1. Insert the start and goal.
        long t0 = System.nanoTime();
        double plainMs = ms(tp, t0);
        int settled = 0;
        int startCluster = layout.clusterOf(s);
        int goalCluster = layout.clusterOf(g);
        dijkstra.run(s, ClusterDijkstra.NO_TARGET);
        settled += dijkstra.settled();
        long[] startTo = new long[portalsIn(startCluster) + 1];
        double[] startCost = new double[startTo.length];
        int n = 0;
        for (int i = graph.clusterStart[startCluster]; i < graph.clusterStart[startCluster + 1]; i++) {
            long p = graph.pos[graph.clusterPortals[i]];
            double d = dijkstra.distance(p);
            if (p != s && d < Double.POSITIVE_INFINITY) {
                startTo[n] = p;
                startCost[n++] = d;
            }
        }
        if (goalCluster == startCluster && dijkstra.distance(g) < Double.POSITIVE_INFINITY) {
            startTo[n] = g;
            startCost[n++] = dijkstra.distance(g);
        }
        startTo = Arrays.copyOf(startTo, n);
        startCost = Arrays.copyOf(startCost, n);

        // One search backwards from the goal prices the way from all its cluster's portals: a
        // forward search from each would flood the cluster once per portal that can't get there.
        int first = graph.clusterStart[goalCluster];
        int portals = portalsIn(goalCluster);
        if (goalSources.length < portals) {
            goalSources = new long[Math.max(portals, goalSources.length * 2)];
        }
        n = 0;
        for (int i = first; i < first + portals; i++) {
            long p = graph.pos[graph.clusterPortals[i]];
            if (p != g) {
                goalSources[n++] = p;
            }
        }
        toGoal.run(g, goalSources, n);
        settled += toGoal.settled();
        int[] goalFrom = new int[portals];
        double[] goalCost = new double[portals];
        n = 0;
        for (int i = first; i < first + portals; i++) {
            int id = graph.clusterPortals[i];
            double d = toGoal.distance(graph.pos[id]);
            if (graph.pos[id] != g && d < Double.POSITIVE_INFINITY) {
                goalFrom[n] = id;
                goalCost[n++] = d;
            }
        }
        QueryGraph query = new QueryGraph(graph, s, g, startTo, startCost,
                Arrays.copyOf(goalFrom, n), Arrays.copyOf(goalCost, n));

        // 2. Search the portals.
        long t1 = System.nanoTime();
        AStarSearch abstractSearch = new AStarSearch(start, goal, query, query, finder.heuristic());
        abstractSearch.runToEnd();
        SearchResult coarse = abstractSearch.result();
        long t2 = System.nanoTime();
        if (!coarse.found()) {
            last = new QueryStats(false, plainExpanded, settled, coarse.expanded(), 0, plainMs,
                    ms(t0, t1), ms(t1, t2), 0);
            return new SearchResult(Status.NO_PATH, List.of(), Double.POSITIVE_INFINITY,
                    coarse.closed(), coarse.open(), last.total());
        }

        // 3. Refine each hop into real steps.
        List<PathStep> path = new ArrayList<>();
        path.add(new PathStep(start, null));
        int refined = 0;
        List<PathStep> hops = coarse.path();
        for (int i = 1; i < hops.size(); i++) {
            long u = hops.get(i - 1).pos().pack();
            long v = hops.get(i).pos().pack();
            int cu = layout.clusterOf(u);
            if (cu != layout.clusterOf(v)) {
                MoveType type = graph.moveBetween(u, v);
                if (type == null) {
                    throw new IllegalStateException("No move between portals " + Pos.toString(u)
                            + " and " + Pos.toString(v));
                }
                path.add(new PathStep(Pos.toPoint(v), type));
                continue;
            }
            dijkstra.run(u, v);
            refined += dijkstra.settled();
            List<PathStep> hop = dijkstra.pathTo(v);
            if (hop.isEmpty()) {
                throw new IllegalStateException("No path inside cluster " + cu + " from "
                        + Pos.toString(u) + " to " + Pos.toString(v));
            }
            path.addAll(hop.subList(1, hop.size()));
        }
        long t3 = System.nanoTime();
        double cost = 0;
        for (int i = 1; i < path.size(); i++) {
            cost += costs.cost(path.get(i - 1).pos().pack(), path.get(i).pos().pack(),
                    path.get(i).via());
        }
        last = new QueryStats(false, plainExpanded, settled, coarse.expanded(), refined, plainMs,
                ms(t0, t1), ms(t1, t2), ms(t2, t3));
        return new SearchResult(Status.FOUND, List.copyOf(path), cost, coarse.closed(),
                coarse.open(), last.total());
    }

    private int portalsIn(int cluster) {
        return graph.clusterStart[cluster + 1] - graph.clusterStart[cluster];
    }

    private static double ms(long from, long to) {
        return (to - from) / 1e6;
    }
}
