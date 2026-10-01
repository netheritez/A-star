package astar.client;

import java.util.Set;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * A practice Aspect of the Void for single player: any item named "Aspect of the Void" (say
 * {@code /give @s diamond_shovel[custom_name="Aspect of the Void"]}) teleports like Hypixel's,
 * as the player's casts there showed, so /goto's casts can be tried with real clicks.
 *
 * <ul>
 *   <li>Sneak and right-click: etherwarp onto the top of the block the crosshair hits within
 *       {@link #ETHER_RANGE}, if there's room to stand there.</li>
 *   <li>Right-click: Instant Transmission. The eye moves along the view up to {@link
 *       #TRANSMIT_RANGE} blocks, stopping short of the first block it meets; the feet end in the
 *       middle of the block that puts them in, pushed up out of the floor if need be.</li>
 * </ul>
 * Runs on the game's own server, so it does nothing on a real server.
 */
final class PracticeAotv {
    private PracticeAotv() {}

    static final double ETHER_RANGE = 57;
    static final double TRANSMIT_RANGE = 12;
    private static final double STANDING_EYE = 1.62;

    static void register() {
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (!(player instanceof ServerPlayer p) || !(level instanceof ServerLevel server)
                    || !p.getItemInHand(hand).getHoverName().getString()
                            .contains("Aspect of the Void")) {
                return InteractionResult.PASS;
            }
            Vec3 to = p.isShiftKeyDown() ? etherwarp(server, p) : transmit(server, p);
            if (to == null) {
                p.sendSystemMessage(Component.literal("There are blocks in the way!"));
                return InteractionResult.FAIL;
            }
            System.out.printf("[astar] practice %s from %.3f,%.3f,%.3f yaw %.2f pitch %.2f to %s%n",
                    p.isShiftKeyDown() ? "etherwarp" : "transmission", p.getX(), p.getY(), p.getZ(),
                    p.getYRot(), p.getXRot(), to);
            p.setDeltaMovement(Vec3.ZERO);
            p.teleportTo(server, to.x, to.y, to.z, Set.of(Relative.Y_ROT, Relative.X_ROT), 0, 0,
                    false);
            return InteractionResult.SUCCESS;
        });
    }

    private static Vec3 etherwarp(Level level, ServerPlayer p) {
        Vec3 eye = p.getEyePosition();
        Vec3 end = eye.add(view(p).scale(ETHER_RANGE));
        BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE, p));
        if (hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        BlockPos top = hit.getBlockPos().above();
        if (!free(level, top) || !free(level, top.above())) {
            return null;
        }
        return new Vec3(top.getX() + 0.5, top.getY(), top.getZ() + 0.5);
    }

    private static Vec3 transmit(Level level, ServerPlayer p) {
        Vec3 eye = p.position().add(0, STANDING_EYE, 0);
        Vec3 dir = view(p);
        Vec3 far = eye.add(dir.scale(TRANSMIT_RANGE));
        // Blocks as they are to anyone (a sneaking player's own view of some, such as
        // scaffolding, differs).
        BlockHitResult hit = level.clip(new ClipContext(eye, far, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, CollisionContext.empty()));
        double t = hit.getType() == HitResult.Type.BLOCK
                ? Math.max(0, hit.getLocation().distanceTo(eye) - 1e-3) : TRANSMIT_RANGE;
        Vec3 end = eye.add(dir.scale(t));
        BlockPos feet = BlockPos.containing(end.x, end.y - STANDING_EYE, end.z);
        for (int up = 0; up < 3 && !(free(level, feet) && free(level, feet.above())); up++) {
            feet = feet.above();
        }
        if (!free(level, feet) || !free(level, feet.above())) {
            return null;
        }
        return new Vec3(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
    }

    /**
     * Where the player looks, from the view the client last sent (the server's own view vector
     * follows the head, which can trail a quick turn by a tick).
     */
    private static Vec3 view(ServerPlayer p) {
        return Vec3.directionFromRotation(p.getXRot(), p.getYRot());
    }

    private static boolean free(Level level, BlockPos pos) {
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }
}
