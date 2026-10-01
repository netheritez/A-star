package astar.core;

/**
 * The seam between the core and validation: lists the moves out of a position. The core never
 * asks whether a block is walkable; whatever implements this has already decided.
 */
@FunctionalInterface
public interface MoveSource {
    void moves(long from, MoveSink sink);

    @FunctionalInterface
    interface MoveSink {
        void accept(long to, MoveType type);
    }
}
