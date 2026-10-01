package astar.core;

/**
 * One search node: a position plus its costs, parent, how it was reached, and its heap slot.
 *
 * <ul>
 *   <li>{@code g}: exact accumulated cost from the start to this node.
 *   <li>{@code h}: heuristic estimate from this node to the goal (must not overestimate).
 *   <li>{@code f}: {@code g + h}, the total estimated cost of a path through this node.
 * </ul>
 *
 * <p>Nodes belong to one search's {@link NodeStore}; a new search starts with fresh nodes.
 */
final class PathNode implements HeapItem<PathNode> {
    private final long pos;
    private double g = Double.POSITIVE_INFINITY;
    private double h;
    private PathNode parent;
    private MoveType via;
    private boolean closed;
    private int heapIndex = -1;

    PathNode(long pos) {
        this.pos = pos;
    }

    public long pos() {
        return pos;
    }

    public double g() {
        return g;
    }

    public double h() {
        return h;
    }

    public double f() {
        return g + h;
    }

    /** The node this one was reached from, or {@code null} for the start. */
    public PathNode parent() {
        return parent;
    }

    /** How this node was reached, or {@code null} for the start. */
    public MoveType via() {
        return via;
    }

    public boolean closed() {
        return closed;
    }

    void reach(double g, double h, PathNode parent, MoveType via) {
        this.g = g;
        this.h = h;
        this.parent = parent;
        this.via = via;
    }

    void close() {
        closed = true;
    }

    @Override
    public int getHeapIndex() {
        return heapIndex;
    }

    @Override
    public void setHeapIndex(int index) {
        heapIndex = index;
    }

    /** Lower f first; on a tie, lower h (closer to the goal) first. */
    @Override
    public int compareTo(PathNode other) {
        int byF = Double.compare(f(), other.f());
        return byF != 0 ? byF : Double.compare(h, other.h);
    }

    @Override
    public String toString() {
        return Pos.toString(pos);
    }
}
