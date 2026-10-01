package astar.movement.trace;

/**
 * Something that happened during a trace that isn't movement: a teleport from the server, the
 * script ending, recording stopping.
 *
 * @param tick the tick it happened in (the number of the last tick recorded before it)
 */
public record TraceEvent(long tick, String text) {}
