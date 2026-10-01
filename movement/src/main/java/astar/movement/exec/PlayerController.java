package astar.movement.exec;

import astar.movement.Keys;
import astar.movement.sim.SimulatedPlayer;

/**
 * The executor's only seam to a player: read where it is, then play one tick with some keys
 * held and the camera turned to a yaw and pitch. {@link SimController} drives a
 * {@code SimulatedPlayer}; the game client gets its own in M8.
 */
public interface PlayerController {

    /** The player as it is now, before the next tick. */
    Observation observe();

    /** Plays one game tick with these keys held and the camera at this yaw and pitch. */
    void tick(Keys keys, float yaw, float pitch);

    /**
     * The player's movement speed attribute without the sprint boost: 0.1 normally, 0.24 with
     * Speed VII.
     */
    default double movementSpeed() {
        return SimulatedPlayer.Attributes.PLAYER.movementSpeed();
    }

    /**
     * What the executor reads from the player.
     *
     * @param yaw the camera's yaw in degrees, the game's way: 0 faces +z, 90 faces -x
     * @param sprinting whether the player is sprinting now
     * @param softCollision whether the last tick's bump into a wall was only a glancing one,
     *     which doesn't stop a sprint
     * @param fallDistance how far the player has fallen since last on the ground: while it's
     *     under a step, sneaking still holds the player back from an edge
     */
    record Observation(double x, double y, double z, double vx, double vy, double vz,
            float yaw, float pitch, boolean onGround, boolean horizontalCollision,
            boolean sprinting, boolean softCollision, double fallDistance) {

        public Observation(double x, double y, double z, double vx, double vy, double vz,
                float yaw, float pitch, boolean onGround, boolean horizontalCollision,
                boolean sprinting, boolean softCollision) {
            this(x, y, z, vx, vy, vz, yaw, pitch, onGround, horizontalCollision, sprinting,
                    softCollision, 0);
        }

        public Observation(double x, double y, double z, double vx, double vy, double vz,
                float yaw, float pitch, boolean onGround, boolean horizontalCollision,
                boolean sprinting) {
            this(x, y, z, vx, vy, vz, yaw, pitch, onGround, horizontalCollision, sprinting,
                    false);
        }

        /** Speed across the ground, in blocks per tick. */
        public double groundSpeed() {
            return Math.hypot(vx, vz);
        }
    }
}
