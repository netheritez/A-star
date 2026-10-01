package astar.pathing;

import astar.core.BlockPoint;

/**
 * One point of a smoothed path: where to go next, and how to get there from the previous one.
 *
 * @param pos the block the entity's feet should reach
 */
public record Waypoint(BlockPoint pos, Kind kind) {

    public enum Kind {
        /** The first waypoint: where the entity already is. */
        START,
        /** Walk in a straight line from the previous waypoint, at any angle. */
        STRAIGHT,
        /** Jump up one block from the previous waypoint, which is the takeoff block. */
        JUMP_UP,
        /** Step off the previous waypoint and fall to this one. */
        DROP,
        /** Swim one block from the previous waypoint (along, diagonally, up or down). */
        SWIM,
        /** Climb one block from the previous waypoint, on a ladder or vine. */
        CLIMB,
        /** Etherwarp from the previous waypoint onto the block under this one. */
        WARP,
        /** Instant Transmission from the previous waypoint, falling onto this one. */
        TRANSMIT
    }
}
