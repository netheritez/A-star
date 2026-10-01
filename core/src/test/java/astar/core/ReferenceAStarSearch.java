package astar.core;

import astar.core.SearchResult.Status;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The original object-per-node A* ({@link PathNode}s in a {@link HashNodeStore}, a
 * {@link MinHeap} open set), kept as a reference: {@link AStarSearch} must produce exactly the
 * same results and listener events.
 */
final class ReferenceAStarSearch {
    private final long goal;
    private final MoveSource moves;
    private final CostModel costs;
    private final Heuristic heuristic;
    private final SearchListener listener;
    private final boolean observed;

    private final NodeStore nodes = new HashNodeStore();
    // Open set: min-heap on f, ties broken by lower h (see PathNode.compareTo).
    private final MinHeap<PathNode> open = new MinHeap<>();
    // Closed set, in expansion order. Membership is PathNode.closed().
    private final List<PathNode> closed = new ArrayList<>();

    private Status status = Status.RUNNING;
    private PathNode found;
    private PathNode best; // closest expanded node to the goal, for bestSoFar()

    ReferenceAStarSearch(BlockPoint start, BlockPoint goal, MoveSource moves, CostModel costs,
            Heuristic heuristic) {
        this(start, goal, moves, costs, heuristic, SearchListener.NONE);
    }

    ReferenceAStarSearch(BlockPoint start, BlockPoint goal, MoveSource moves, CostModel costs,
            Heuristic heuristic, SearchListener listener) {
        this.goal = goal.pack();
        this.moves = moves;
        this.costs = costs;
        this.heuristic = heuristic;
        this.listener = listener;
        this.observed = listener != SearchListener.NONE;

        PathNode startNode = nodes.get(start.pack());
        startNode.reach(0, heuristic.between(startNode.pos(), this.goal), null, null);
        open.add(startNode);
        if (observed) {
            listener.onStart(view(startNode), goal);
        }
    }

    public Status status() {
        return status;
    }

    /** Expands one node. Does nothing once the search has finished. */
    public Status step() {
        if (status != Status.RUNNING) {
            return status;
        }
        if (open.isEmpty()) {
            return finish(Status.NO_PATH);
        }

        PathNode current = open.poll();
        if (current.pos() == goal) {
            found = current;
            return finish(Status.FOUND);
        }

        current.close();
        closed.add(current);
        if (best == null || current.h() < best.h()
                || (current.h() == best.h() && current.g() < best.g())) {
            best = current;
        }
        if (observed) {
            listener.onExpand(view(current));
        }

        moves.moves(current.pos(), (to, type) -> relax(current, to, type));
        return status;
    }

    private void relax(PathNode current, long to, MoveType type) {
        PathNode next = nodes.get(to);
        if (next.closed()) {
            return;
        }

        double stepCost = costs.cost(current.pos(), to, type);
        double tentativeG = current.g() + stepCost;
        boolean inOpen = open.contains(next);
        if (inOpen && tentativeG >= next.g()) {
            return; // not an improvement
        }

        double oldG = next.g();
        next.reach(tentativeG, heuristic.between(to, goal), current, type);
        if (inOpen) {
            open.update(next); // f dropped: sift up in place
            if (observed) {
                listener.onImprove(view(next), oldG);
            }
        } else {
            open.add(next);
            if (observed) {
                listener.onOpen(view(next), stepCost);
            }
        }
    }

    /** Runs up to {@code maxExpansions} steps, stopping early if the search finishes. */
    public Status run(int maxExpansions) {
        for (int i = 0; i < maxExpansions && status == Status.RUNNING; i++) {
            step();
        }
        return status;
    }

    public Status runToEnd() {
        while (status == Status.RUNNING) {
            step();
        }
        return status;
    }

    public int expanded() {
        return closed.size();
    }

    /** The current state. While running, the path is empty. */
    public SearchResult result() {
        Set<BlockPoint> closedPoints = new LinkedHashSet<>();
        for (PathNode n : closed) {
            closedPoints.add(Pos.toPoint(n.pos()));
        }
        Set<BlockPoint> openPoints = new LinkedHashSet<>();
        for (PathNode n : open.toList()) {
            openPoints.add(Pos.toPoint(n.pos()));
        }
        boolean ok = status == Status.FOUND;
        return new SearchResult(
                status,
                ok ? reconstructPath(found) : List.of(),
                ok ? found.g() : Double.POSITIVE_INFINITY,
                Collections.unmodifiableSet(closedPoints),
                Collections.unmodifiableSet(openPoints),
                closed.size());
    }

    /**
     * The path to the expanded node closest to the goal (lowest h), or to the goal once found.
     * A fallback for callers that stop a search before it finishes.
     */
    public List<PathStep> bestSoFar() {
        if (found != null) {
            return reconstructPath(found);
        }
        return best == null ? List.of() : reconstructPath(best);
    }

    private Status finish(Status s) {
        status = s;
        if (observed) {
            listener.onFinish(result());
        }
        return s;
    }

    static NodeView view(PathNode n) {
        PathNode p = n.parent();
        return new NodeView(
                Pos.toPoint(n.pos()), n.g(), n.h(), p == null ? null : Pos.toPoint(p.pos()), n.via());
    }

    /** Walks parent pointers back to the start, then reverses. */
    static List<PathStep> reconstructPath(PathNode node) {
        List<PathStep> path = new ArrayList<>();
        for (PathNode n = node; n != null; n = n.parent()) {
            path.add(new PathStep(Pos.toPoint(n.pos()), n.via()));
        }
        Collections.reverse(path);
        return List.copyOf(path);
    }
}
