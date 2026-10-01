package astar.movement.trace;

import astar.movement.Keys;

/**
 * One game tick of a trace: the state before the tick, the inputs the game used during it, and
 * the state after.
 *
 * @param tick the tick's number, counted from the start of the trace
 * @param keys the keys the game read this tick
 * @param moveSideways the movement input after the game's own scaling (sneaking, using items):
 *     positive is left
 * @param moveForward the same, forward
 * @param yaw the camera yaw the tick moved with, in degrees
 * @param pitch the camera pitch, in degrees
 * @param posBefore the feet position at the start of the tick
 * @param velBefore the velocity at the start of the tick, in blocks per tick
 * @param pos the feet position after the tick
 * @param vel the velocity after the tick
 */
public record TickRecord(long tick, Keys keys, float moveSideways, float moveForward, float yaw,
        float pitch, Vec3 posBefore, Vec3 velBefore, Vec3 pos, Vec3 vel, boolean onGround,
        boolean horizontalCollision, boolean verticalCollision, boolean sprinting,
        boolean sneaking, boolean inWater, boolean climbing, double fallDistance) {}
