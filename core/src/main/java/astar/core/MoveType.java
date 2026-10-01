package astar.core;

/** How a node was reached from its parent. */
public enum MoveType {
    /** One block along x or z, same height. */
    WALK,
    /** One block along x and z at once, same height. */
    DIAGONAL,
    /** One block along x or z, or diagonally, and one block up. */
    JUMP_UP,
    /** One block along x or z, or diagonally, then down one or more blocks. */
    DROP,
    /**
     * Any move out of water (level, up onto a bank or down), a level move into water, or one
     * block straight up into water.
     */
    SWIM,
    /**
     * On a ladder or vine: one block up or down in the same column, or level, straight or
     * diagonal, while held by the ladder rather than standing on a floor.
     */
    CLIMB,
    /**
     * An etherwarp: a teleport from one standing spot onto the block aimed at, any distance
     * within range. Priced by the graph that holds it, never by a cost model.
     */
    WARP,
    /**
     * An Instant Transmission: a teleport along the view up to its range, then a fall straight
     * down onto the floor below. Priced by the graph that holds it, never by a cost model.
     */
    TRANSMIT
}
