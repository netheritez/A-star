package astar.client;

import astar.movement.Keys;
import net.minecraft.client.player.ClientInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;

/** Movement input that comes from a script instead of the keyboard. */
final class ScriptedInput extends ClientInput {

    private Keys keys = Keys.NONE;

    void set(Keys keys) {
        this.keys = keys;
    }

    /** Does what {@code KeyboardInput.tick} does, with the script's keys. */
    @Override
    public void tick() {
        keyPresses = new Input(keys.forward(), keys.back(), keys.left(), keys.right(),
                keys.jump(), keys.sneak(), keys.sprint());
        float forward = multiplier(keys.forward(), keys.back());
        float sideways = multiplier(keys.left(), keys.right());
        moveVector = new Vec2(sideways, forward).normalized();
    }

    private static float multiplier(boolean positive, boolean negative) {
        if (positive == negative) {
            return 0.0F;
        }
        return positive ? 1.0F : -1.0F;
    }
}
