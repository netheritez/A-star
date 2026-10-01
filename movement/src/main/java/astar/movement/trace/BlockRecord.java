package astar.movement.trace;

import java.util.List;

/**
 * One block near the player when a trace was recorded, with what movement needs from it.
 * Plain air is left out of traces.
 *
 * @param state the block state as the game writes it, e.g. {@code minecraft:oak_slab[type=bottom,waterlogged=false]}
 * @param boxes the collision shape, as boxes relative to the block's corner
 * @param slipperiness ground friction (0.6 for most blocks, 0.98 for ice)
 * @param velocityMultiplier how much the block slows movement (0.4 for soul sand, else 1)
 * @param jumpMultiplier how much the block weakens jumps (0.5 for honey, else 1)
 * @param fluid {@code "water"}, {@code "lava"} or {@code ""}
 * @param fluidHeight how high the fluid fills the block, 0 to 1
 * @param climbable whether it's a ladder, vine or another climbable block
 * @param kind {@code "fence"}, {@code "wall"} or {@code "gate"} for fences, walls and fence
 *     gates, which change which block counts as the one underfoot; otherwise {@code ""}
 * @param pointsY the heights the game's collision shape is divided at (its grid, which can
 *     include heights no box ends at, such as 1.0 for a bottom slab), relative to the block.
 *     Stepping up tries each of them. Empty when not recorded: the boxes' own heights are used.
 */
public record BlockRecord(int x, int y, int z, String state, List<Box> boxes, float slipperiness,
        float velocityMultiplier, float jumpMultiplier, String fluid, double fluidHeight,
        boolean climbable, String kind, List<Double> pointsY) {

    /** An axis-aligned box, in blocks. */
    public record Box(double minX, double minY, double minZ, double maxX, double maxY,
            double maxZ) {}

    public BlockRecord {
        boxes = List.copyOf(boxes);
        pointsY = List.copyOf(pointsY);
    }

    public BlockRecord(int x, int y, int z, String state, List<Box> boxes, float slipperiness,
            float velocityMultiplier, float jumpMultiplier, String fluid, double fluidHeight,
            boolean climbable, String kind) {
        this(x, y, z, state, boxes, slipperiness, velocityMultiplier, jumpMultiplier, fluid,
                fluidHeight, climbable, kind, List.of());
    }

    /** A block that isn't a fence, wall or gate. */
    public BlockRecord(int x, int y, int z, String state, List<Box> boxes, float slipperiness,
            float velocityMultiplier, float jumpMultiplier, String fluid, double fluidHeight,
            boolean climbable) {
        this(x, y, z, state, boxes, slipperiness, velocityMultiplier, jumpMultiplier, fluid,
                fluidHeight, climbable, "");
    }
}
