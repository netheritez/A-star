package astar.movement.trace;

import java.util.Map;

/**
 * The first line of a trace.
 *
 * @param format the trace format's version, {@link Trace#FORMAT}
 * @param game the Minecraft version it was recorded in
 * @param scenario the script that drove it, or {@code ""} when a person played
 * @param started when recording started, as ISO-8601 text
 * @param attributes the player's movement attributes at the start (movement speed, jump
 *     strength, step height, gravity, sneaking speed...), by their game names
 */
public record TraceHeader(int format, String game, String scenario, String started,
        Map<String, Double> attributes) {

    public TraceHeader {
        attributes = Map.copyOf(attributes);
    }
}
