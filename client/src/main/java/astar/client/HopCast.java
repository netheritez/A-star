package astar.client;

import net.minecraft.client.Minecraft;

/** One teleport of a route being cast, a tick at a time ({@link WarpCast}, {@link TransmitCast}). */
interface HopCast {

    enum Status {
        RUNNING, LANDED, FAILED
    }

    /** One game tick, before the player moves. */
    Status tick(Minecraft client);

    /** Why it failed. */
    String reason();
}
