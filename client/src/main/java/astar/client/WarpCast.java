package astar.client;

import astar.movement.Keys;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Casts one etherwarp, a tick at a time, once /goto has walked to where it's cast from: stand
 * still, sneak, turn the view onto the aim point, check that the crosshair is on the target
 * block, right-click the Aspect of the Void and wait to land.
 *
 * <p>The aim is worked out again each tick from where the eye really is, and the click only
 * comes once the game's own crosshair ray (block outlines, so grass and flowers count) hits
 * the block the hop lands on, within range. In single player, where there's no Aspect of the
 * Void, the click is a {@code /tp} onto that block instead, to try routes out.
 */
final class WarpCast implements HopCast {

    /** Etherwarp's reach, from the eye. */
    private static final double RANGE = 57;
    /** Ticks to come to a stop before giving up. */
    private static final int STOP_TICKS = 30;
    /** Ticks to line the crosshair up before giving up. */
    private static final int AIM_TICKS = 40;
    /** Ticks to wait for the teleport after a click, and clicks to try. */
    private static final int LAND_TICKS = 20;
    private static final int CLICKS = 3;

    private enum Phase {
        STOP, AIM, WAIT
    }

    private final Navigator.GameController controller;
    private final BlockPos target;
    private Vec3 aim;
    /** Whether the aim was already moved to another point of the block the crosshair reaches. */
    private boolean reaimed;
    private final Vec3 landing;
    /** The view to turn toward once clicked (the next hop or walk), or null to hold still. */
    private final float[] after;
    private Phase phase = Phase.STOP;
    private int ticks;
    private int clicks;
    /** Ticks the view has been on the aim; the click waits for {@link #AIM_SETTLE_TICKS}. */
    private int settled;
    private static final int AIM_SETTLE_TICKS = 3;
    private String reason = "";
    /** Turns the view onto the aim, and on to what's next once clicked. */
    private final HandAim hand = new HandAim();
    private final HandAim onward = new HandAim();

    /**
     * @param target the block the hop lands on top of, in the world
     * @param aim the point on it to aim at, in the world
     */
    WarpCast(Navigator.GameController controller, BlockPos target, Vec3 aim, float[] after) {
        this.controller = controller;
        this.after = after;
        this.target = target;
        this.aim = aim;
        this.landing = new Vec3(target.getX() + 0.5, target.getY() + 1, target.getZ() + 0.5);
    }

    @Override
    public String reason() {
        return reason;
    }

    @Override
    public Status tick(Minecraft client) {
        LocalPlayer player = client.player;
        ticks++;
        float yaw = player.getYRot();
        float pitch = player.getXRot();
        switch (phase) {
            case STOP -> {
                // Sneak from the start: it slows the player down, and the eye is at sneaking
                // height by the time the view is on target.
                controller.tick(new Keys(false, false, false, false, false, true, false), yaw,
                        pitch);
                Vec3 v = player.getDeltaMovement();
                if (player.onGround() && v.x * v.x + v.z * v.z < 1e-4) {
                    next(Phase.AIM);
                } else if (ticks > STOP_TICKS) {
                    return fail("couldn't stand still to etherwarp");
                }
            }
            case AIM -> {
                Vec3 eye = player.getEyePosition();
                Vec3 d = aim.subtract(eye);
                float wantYaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
                float wantPitch = (float) -Math.toDegrees(Math.atan2(d.y,
                        Math.sqrt(d.x * d.x + d.z * d.z)));
                // A hand's sweep onto the aim, then a beat with the view still before the
                // click: the server casts along the view it was last sent, a tick behind.
                float[] v = hand.step(yaw, pitch, wantYaw, wantPitch, false);
                settled = hand.left() < 1F ? settled + 1 : 0;
                Keys sneak = new Keys(false, false, false, false, false, true, false);
                controller.tick(sneak, v[0], v[1]);
                boolean on = settled > AIM_SETTLE_TICKS;
                if (on && player.isCrouching() && !crosshairOnTarget(player) && !reaimed) {
                    // Something the map didn't know of (grass, a torch) is in the way of that
                    // point: aim at another point of the block the crosshair can reach.
                    reaimed = true;
                    Vec3 other = reachable(client, player);
                    System.out.printf("[astar] etherwarp to %s: crosshair blocked (%s); %s%n",
                            target.toShortString(), inTheWay(client, player),
                            other == null ? "no other point reaches it" : "aiming at " + other);
                    if (other != null) {
                        aim = other;
                        settled = 0;
                        return Status.RUNNING;
                    }
                }
                if (on && player.isCrouching() && crosshairOnTarget(player)) {
                    if (!click(client, player)) {
                        return fail("there's no Aspect of the Void in the hotbar");
                    }
                    next(Phase.WAIT);
                } else if (ticks > AIM_TICKS) {
                    return fail("the crosshair wouldn't land on " + target.toShortString()
                            + " (" + inTheWay(client, player) + ")");
                }
            }
            case WAIT -> {
                // Already turning on to what comes next, as a player does once the click is
                // in: the server has the cast's view from before the click.
                float[] v = after == null ? new float[] {yaw, pitch}
                        : onward.step(yaw, pitch, after[0], after[1], false);
                controller.tick(new Keys(false, false, false, false, false, true, false), v[0],
                        v[1]);
                if (player.position().distanceToSqr(landing) < 1) {
                    controller.tick(Keys.NONE, v[0], v[1]);
                    return Status.LANDED;
                }
                if (ticks > LAND_TICKS) {
                    if (clicks >= CLICKS) {
                        return fail("the etherwarp to " + target.toShortString()
                                + " didn't happen (out of mana?)");
                    }
                    settled = 0;
                    next(Phase.AIM); // check the aim again and click once more
                }
            }
        }
        return Status.RUNNING;
    }

    private void next(Phase p) {
        phase = p;
        ticks = 0;
    }

    private Status fail(String why) {
        reason = why;
        return Status.FAILED;
    }

    /** Whether the game's crosshair ray, from the eye as it is now, first hits the target. */
    private boolean crosshairOnTarget(LocalPlayer player) {
        HitResult hit = player.pick(RANGE + 2, 1.0F, false);
        return hit instanceof BlockHitResult b && hit.getType() == HitResult.Type.BLOCK
                && b.getBlockPos().equals(target)
                && b.getLocation().distanceTo(player.getEyePosition()) <= RANGE;
    }

    /**
     * A point on the target block's faces that the crosshair ray from the eye reaches first,
     * within range, the nearest to the middle of a face; null if there's none.
     */
    private Vec3 reachable(Minecraft client, LocalPlayer player) {
        Vec3 eye = player.getEyePosition();
        double x = target.getX(), y = target.getY(), z = target.getZ();
        double[] at = {0.5, 0.3, 0.7, 0.15, 0.85};
        Vec3 best = null;
        for (int face = 0; face < 6 && best == null; face++) {
            for (double u : at) {
                for (double v : at) {
                    Vec3 p = switch (face) {
                        case 0 -> new Vec3(x + u, y + 0.98, z + v);
                        case 1 -> new Vec3(x + 0.02, y + u, z + v);
                        case 2 -> new Vec3(x + 0.98, y + u, z + v);
                        case 3 -> new Vec3(x + u, y + v, z + 0.02);
                        case 4 -> new Vec3(x + u, y + v, z + 0.98);
                        default -> new Vec3(x + u, y + 0.02, z + v);
                    };
                    if (hits(client, eye, p)) {
                        return p;
                    }
                }
            }
        }
        return best;
    }

    /**
     * Whether an etherwarp from {@code eye} aimed at {@code p} reaches block {@code target}:
     * the crosshair ray hits it first, within range.
     */
    static boolean reaches(Minecraft client, Vec3 eye, Vec3 p, BlockPos target) {
        if (eye.distanceTo(p) > RANGE - 1) {
            return false;
        }
        Vec3 end = eye.add(p.subtract(eye).normalize().scale(RANGE));
        BlockHitResult b = client.level.clip(new net.minecraft.world.level.ClipContext(eye, end,
                net.minecraft.world.level.ClipContext.Block.OUTLINE,
                net.minecraft.world.level.ClipContext.Fluid.NONE, client.player));
        return b.getType() == HitResult.Type.BLOCK && b.getBlockPos().equals(target);
    }

    /** Whether the crosshair ray from {@code eye} through {@code p} first hits the target. */
    private boolean hits(Minecraft client, Vec3 eye, Vec3 p) {
        Vec3 end = eye.add(p.subtract(eye).normalize().scale(RANGE));
        BlockHitResult b = client.level.clip(new net.minecraft.world.level.ClipContext(eye, end,
                net.minecraft.world.level.ClipContext.Block.OUTLINE,
                net.minecraft.world.level.ClipContext.Fluid.NONE, client.player));
        return b.getType() == HitResult.Type.BLOCK && b.getBlockPos().equals(target);
    }

    /** What the crosshair hits instead of the target, for the message. */
    private String inTheWay(Minecraft client, LocalPlayer player) {
        HitResult hit = player.pick(RANGE + 2, 1.0F, false);
        if (hit instanceof BlockHitResult b && hit.getType() == HitResult.Type.BLOCK) {
            return client.level.getBlockState(b.getBlockPos()).getBlock().getName().getString()
                    + " at " + b.getBlockPos().toShortString() + " is in the way";
        }
        return "nothing in reach";
    }

    /** Right-clicks the Aspect of the Void; false if there's none to click. */
    private boolean click(Minecraft client, LocalPlayer player) {
        clicks++;
        int slot = aotvSlot(player);
        if (slot >= 0) {
            player.getInventory().setSelectedSlot(slot);
            client.gameMode.useItem(player, InteractionHand.MAIN_HAND);
        } else if (client.hasSingleplayerServer()) {
            // Trying routes out in single player: land where Hypixel would put the player.
            client.getConnection().sendCommand(String.format(java.util.Locale.ROOT,
                    "tp @s %.3f %.3f %.3f", landing.x, landing.y, landing.z));
        } else {
            return false;
        }
        return true;
    }

    /** The hotbar slot holding an Aspect of the Void, or -1. */
    static int aotvSlot(LocalPlayer player) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getHoverName().getString()
                    .contains("Aspect of the Void")) {
                return i;
            }
        }
        return -1;
    }

    private static float wrap(float degrees) {
        float d = degrees % 360;
        if (d >= 180) {
            d -= 360;
        } else if (d < -180) {
            d += 360;
        }
        return d;
    }

}
