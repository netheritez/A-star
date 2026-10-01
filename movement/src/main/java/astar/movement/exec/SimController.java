package astar.movement.exec;

import astar.movement.Keys;
import astar.movement.sim.SimulatedPlayer;

/** Drives a {@link SimulatedPlayer}. */
public final class SimController implements PlayerController {

    private final SimulatedPlayer player;
    private float pitch;

    public SimController(SimulatedPlayer player) {
        this.player = player;
    }

    public SimulatedPlayer player() {
        return player;
    }

    @Override
    public Observation observe() {
        return new Observation(player.x(), player.y(), player.z(), player.vx(), player.vy(),
                player.vz(), player.yaw(), pitch, player.onGround(),
                player.horizontalCollision(), player.sprinting(), player.softCollision(),
                player.fallDistance());
    }

    @Override
    public double movementSpeed() {
        return player.attributes().movementSpeed();
    }

    @Override
    public void tick(Keys keys, float yaw, float pitch) {
        this.pitch = pitch;
        player.tick(keys, yaw, pitch);
    }
}
