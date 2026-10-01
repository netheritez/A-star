package astar.core;

/**
 * One step of a finished path.
 *
 * @param via how this position was reached; {@code null} for the start
 */
public record PathStep(BlockPoint pos, MoveType via) {}
