package astar.client;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Writes down every Aspect of the Void cast the player makes, so where Hypixel really puts the
 * player can be checked against a model: at the click, the feet, eye height, view, sneaking,
 * standing and movement; the positions the player had on the ticks after it; where the
 * teleport landed (or the server's refusal); and every block that isn't air in a box around it.
 * One line per cast, in {@code astar-casts.log} in the game folder.
 */
final class CastLog {
    private CastLog() {}

    /** Ticks to wait for the teleport after a click. */
    private static final int WAIT = 30;
    /** Ticks to keep waiting once the server said blocks are in the way. */
    private static final int WAIT_BLOCKED = 10;
    /**
     * A move this far from where the player's own motion would take it in one tick is the
     * teleport (a short one can be under two blocks).
     */
    private static final double JUMP = 1.0;
    /** Blocks around the start and the landing that go in the line. */
    private static final int BOX = 3;

    private static StringBuilder pending;
    private static Vec3 clickFeet;
    /** Where the view pointed at the click, 20 blocks out from the feet. */
    private static Vec3 reach;
    private static Vec3 last;
    private static Vec3 lastMotion;
    private static int ticks;
    /** The tick the server said blocks are in the way, or -1. */
    private static int blockedAt;
    private static int count;

    static void register() {
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (level.isClientSide() && player instanceof LocalPlayer p && isAotv(p)) {
                clicked(p);
            }
            return InteractionResult.PASS;
        });
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (pending != null && !overlay
                    && message.getString().contains("There are blocks in the way")) {
                blockedAt = ticks;
            }
        });
    }

    private static boolean isAotv(LocalPlayer p) {
        String name = p.getMainHandItem().getHoverName().getString();
        return name.contains("Aspect of the Void") || name.contains("Aspect of the End");
    }

    private static void clicked(LocalPlayer p) {
        if (pending != null) {
            finish(Minecraft.getInstance(), "clicked again before landing");
        }
        Vec3 v = p.getDeltaMovement();
        clickFeet = p.position();
        last = clickFeet;
        lastMotion = v;
        reach = clickFeet.add(p.getViewVector(1.0F).scale(20));
        ticks = 0;
        blockedAt = -1;
        pending = new StringBuilder();
        // The previous tick's position and view too: the server may still hold those when the
        // click reaches it.
        pending.append(String.format(Locale.ROOT, "cast feet=%s eye=%.4f yaw=%.3f pitch=%.3f"
                + " prev=%s prevYaw=%.3f prevPitch=%.3f sneak=%b ground=%b motion=%s"
                + " item=\"%s\"", vec(clickFeet), p.getEyeHeight(), p.getYRot(), p.getXRot(),
                vec(new Vec3(p.xo, p.yo, p.zo)), p.yRotO, p.xRotO, p.isCrouching(),
                p.onGround(), vec(v), p.getMainHandItem().getHoverName().getString()));
        pending.append(" ticks=[");
    }

    /** One game tick. */
    static void tick(Minecraft client) {
        if (pending == null) {
            return;
        }
        LocalPlayer p = client.player;
        if (p == null) {
            pending = null;
            return;
        }
        ticks++;
        Vec3 now = p.position();
        if (now.distanceTo(last.add(lastMotion)) > JUMP) {
            pending.append("] landed=").append(vec(now)).append(String.format(Locale.ROOT,
                    " after=%d ground=%b blocked=%b", ticks, p.onGround(), blockedAt >= 0));
            blocks(client, BlockPos.containing(now));
            write(client);
            return;
        }
        pending.append(vec(now)).append(' ');
        last = now;
        lastMotion = p.getDeltaMovement();
        if (blockedAt >= 0 && ticks >= blockedAt + WAIT_BLOCKED) {
            finish(client, "refused: There are blocks in the way! (no teleport)");
        } else if (ticks >= WAIT) {
            finish(client, "no teleport");
        }
    }

    private static void finish(Minecraft client, String why) {
        pending.append("] ").append(why);
        blocks(client, BlockPos.containing(clickFeet));
        write(client);
    }

    /**
     * Every block that isn't air in the box around the start, where the view pointed 20 blocks
     * out and {@code landing}, grown by {@link #BOX}.
     */
    private static void blocks(Minecraft client, BlockPos landing) {
        BlockPos a = BlockPos.containing(clickFeet), r = BlockPos.containing(reach);
        int x0 = Math.min(a.getX(), Math.min(r.getX(), landing.getX())) - BOX;
        int x1 = Math.max(a.getX(), Math.max(r.getX(), landing.getX())) + BOX;
        int y0 = Math.min(a.getY(), Math.min(r.getY(), landing.getY())) - BOX;
        int y1 = Math.max(a.getY(), Math.max(r.getY(), landing.getY())) + BOX + 2;
        int z0 = Math.min(a.getZ(), Math.min(r.getZ(), landing.getZ())) - BOX;
        int z1 = Math.max(a.getZ(), Math.max(r.getZ(), landing.getZ())) + BOX;
        pending.append(" blocks=");
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int y = y0; y <= y1; y++) {
            for (int z = z0; z <= z1; z++) {
                for (int x = x0; x <= x1; x++) {
                    BlockState s = client.level.getBlockState(at.set(x, y, z));
                    if (!s.isAir()) {
                        pending.append(x).append(',').append(y).append(',').append(z).append('=')
                                .append(BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath());
                        String all = s.toString();
                        int props = all.indexOf('[');
                        if (props >= 0) {
                            pending.append(all.substring(props).replace(" ", ""));
                        }
                        pending.append(';');
                    }
                }
            }
        }
    }

    private static void write(Minecraft client) {
        Path file = client.gameDirectory.toPath().resolve("astar-casts.log");
        String line = pending.append('\n').toString();
        pending = null;
        count++;
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            w.write(line);
        } catch (IOException e) {
            System.out.println("[astar] couldn't write the cast log: " + e);
            return;
        }
        int end = line.indexOf(" blocks=");
        System.out.println("[astar] cast " + count + ": " + line.substring(0, end < 0
                ? Math.min(line.length(), 300) : end).replaceAll(" ticks=\\[[^\\]]*\\]", ""));
    }

    private static String vec(Vec3 v) {
        return String.format(Locale.ROOT, "%.4f,%.4f,%.4f", v.x, v.y, v.z);
    }
}
