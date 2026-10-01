package astar.pathing;

import astar.core.CostModel;
import astar.core.MoveSource;
import astar.core.MoveType;

/**
 * One query's view of a {@link PortalGraph}: the shared graph, plus edges from the start to the
 * portals of its cluster (and to the goal, if they share one), and from the portals of the
 * goal's cluster to the goal. The shared graph is never changed.
 *
 * <p>It's both the {@link MoveSource} and the {@link CostModel} of the abstract search, which is
 * an ordinary {@code AStarSearch}. Every edge is reported as a {@link MoveType#WALK}; the real
 * moves come back when the path is refined.
 */
final class QueryGraph implements MoveSource, CostModel {
    private final PortalGraph graph;
    private final long start;
    private final long goal;
    private final int goalCluster;
    private final long[] startTo;
    private final double[] startCost;
    private final int[] goalFrom; // portal ids
    private final double[] goalCost;

    // The edge last handed to the search: AStarSearch prices each move as soon as it gets it.
    private long lastFrom;
    private long lastTo;
    private double lastCost;

    QueryGraph(PortalGraph graph, long start, long goal, long[] startTo, double[] startCost,
            int[] goalFrom, double[] goalCost) {
        this.graph = graph;
        this.start = start;
        this.goal = goal;
        this.goalCluster = graph.layout.clusterOf(goal);
        this.startTo = startTo;
        this.startCost = startCost;
        this.goalFrom = goalFrom;
        this.goalCost = goalCost;
    }

    @Override
    public void moves(long from, MoveSink sink) {
        if (from == start) {
            for (int k = 0; k < startTo.length; k++) {
                emit(from, startTo[k], startCost[k], sink);
            }
        }
        int id = graph.idOf(from);
        if (id < 0) {
            return;
        }
        for (int e = graph.edgeStart[id]; e < graph.edgeStart[id + 1]; e++) {
            emit(from, graph.pos[graph.edgeTo[e]], graph.edgeCost[e], sink);
        }
        int k = goalIndex(from, id);
        if (k >= 0) {
            emit(from, goal, goalCost[k], sink);
        }
    }

    private void emit(long from, long to, double cost, MoveSink sink) {
        lastFrom = from;
        lastTo = to;
        lastCost = cost;
        sink.accept(to, MoveType.WALK);
    }

    @Override
    public double cost(long from, long to, MoveType type) {
        if (from == lastFrom && to == lastTo) {
            return lastCost;
        }
        // Asked out of turn: look the edge up (the cheapest, if there are two).
        double best = Double.POSITIVE_INFINITY;
        if (from == start) {
            for (int k = 0; k < startTo.length; k++) {
                if (startTo[k] == to) {
                    best = Math.min(best, startCost[k]);
                }
            }
        }
        int id = graph.idOf(from);
        if (id >= 0) {
            for (int e = graph.edgeStart[id]; e < graph.edgeStart[id + 1]; e++) {
                if (graph.pos[graph.edgeTo[e]] == to) {
                    best = Math.min(best, graph.edgeCost[e]);
                }
            }
            int k = goalIndex(from, id);
            if (k >= 0 && to == goal) {
                best = Math.min(best, goalCost[k]);
            }
        }
        return best;
    }

    private int goalIndex(long from, int id) {
        if (graph.layout.clusterOf(from) != goalCluster) {
            return -1;
        }
        for (int k = 0; k < goalFrom.length; k++) {
            if (goalFrom[k] == id) {
                return k;
            }
        }
        return -1;
    }
}
