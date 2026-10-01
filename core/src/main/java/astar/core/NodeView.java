package astar.core;

/**
 * A copy of a node's state at the moment a listener event fired. It never changes, so tools can
 * keep it.
 *
 * @param parent {@code null} for the start
 * @param via {@code null} for the start
 */
public record NodeView(BlockPoint pos, double g, double h, BlockPoint parent, MoveType via) {

    public double f() {
        return g + h;
    }
}
