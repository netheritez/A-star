package astar.movement.sim;

import java.util.List;

/**
 * What the simulator needs to know about one block.
 *
 * @param boxes the collision shape, relative to the block's corner
 * @param pointsY the heights the shape is divided at, relative to the block; stepping up tries
 *     each one (see {@code Entity.collectStepHeights})
 * @param slipperiness ground friction: 0.6 for most blocks, 0.98 for ice
 * @param velocityMultiplier 0.4 for soul sand and honey, else 1
 * @param jumpMultiplier 0.5 for honey, else 1
 * @param kind {@code "fence"}, {@code "wall"}, {@code "gate"} or {@code ""}
 * @param water whether it holds water
 * @param climbable a ladder, vine or other climbable block
 */
public record SimBlock(List<Aabb> boxes, List<Double> pointsY, float slipperiness,
        float velocityMultiplier, float jumpMultiplier, String kind, boolean water,
        boolean climbable) {

    public static final SimBlock AIR = new SimBlock(List.of(), List.of(), 0.6F, 1.0F, 1.0F, "",
            false, false);

    public SimBlock {
        boxes = List.copyOf(boxes);
        pointsY = List.copyOf(pointsY);
    }

    /** A plain solid block of one box, with the usual friction. */
    public static SimBlock solid(Aabb box) {
        return new SimBlock(List.of(box), List.of(box.minY(), box.maxY()), 0.6F, 1.0F, 1.0F, "",
                false, false);
    }

    public boolean isEmpty() {
        return boxes.isEmpty();
    }
}
