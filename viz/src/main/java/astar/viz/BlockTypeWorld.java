package astar.viz;

import astar.mcworld.BlockShapes;
import astar.mcworld.ImportedShapes;
import astar.movement.sim.Aabb;
import astar.movement.sim.SimBlock;
import astar.movement.sim.SimWorld;
import astar.pathing.BlockType;
import astar.pathing.BlockView;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The pathfinder's block world as the movement code sees it. Each {@link BlockType} becomes a
 * full-width box of its height, as the pathfinder already approximates it (so stairs are half
 * blocks and fences 1.5-high posts the width of a block). Doors and fence gates are left open,
 * marked to be opened; lava, cacti and powder snow are solid, so the body never drifts into
 * them.
 *
 * <p>Given an imported map's {@link ImportedShapes}, its stairs, slabs, fences and other
 * odd-shaped blocks get their real collision shapes (and ice its real friction) instead.
 * Doors, gates, water, ladders and the blocks kept solid above stay as they are.
 */
final class BlockTypeWorld implements SimWorld {

    private static final SimBlock[] BLOCKS = new SimBlock[BlockType.values().length];

    static {
        for (BlockType t : BlockType.values()) {
            BLOCKS[t.ordinal()] = of(t);
        }
    }

    private final BlockView view;
    private final ImportedShapes shapes;
    private final Map<BlockShapes.Shape, SimBlock> real = new IdentityHashMap<>();

    BlockTypeWorld(BlockView view) {
        this(view, null);
    }

    BlockTypeWorld(BlockView view, ImportedShapes shapes) {
        this.view = view;
        this.shapes = shapes;
    }

    @Override
    public SimBlock block(int x, int y, int z) {
        BlockType t = view.blockAt(x, y, z);
        if (shapes != null && keepsShape(t)) {
            BlockShapes.Shape shape = shapes.at(x, y, z, t);
            if (shape != null) {
                return real.computeIfAbsent(shape, BlockTypeWorld::of);
            }
        }
        return BLOCKS[t.ordinal()];
    }

    /** Whether a block of this type may take its real shape. */
    private static boolean keepsShape(BlockType t) {
        return switch (t) {
            case AIR, COBWEB, BERRY_BUSH, WATER, FLOWING_WATER, BUBBLE_UP, BUBBLE_DOWN, CLIMBABLE,
                    SCAFFOLDING, DOOR, GATE, HAZARD, VOID, CACTUS, POWDER_SNOW -> false;
            default -> true;
        };
    }

    static SimBlock of(BlockShapes.Shape shape) {
        List<Aabb> boxes = new ArrayList<>(shape.boxes().size());
        for (double[] b : shape.boxes()) {
            boxes.add(new Aabb(b[0], b[1], b[2], b[3], b[4], b[5]));
        }
        return new SimBlock(boxes, shape.pointsY(), shape.friction(), shape.speed(),
                shape.jump(), shape.kind(), false, false);
    }

    static SimBlock of(BlockType t) {
        return switch (t) {
            case AIR, COBWEB, BERRY_BUSH -> SimBlock.AIR;
            case WATER, FLOWING_WATER, BUBBLE_UP, BUBBLE_DOWN ->
                    new SimBlock(List.of(), List.of(), 0.6F, 1, 1, "", true, false);
            // (Scaffolding climbs like a ladder here; its top, which holds a body standing on
            // it, isn't modelled.)
            case CLIMBABLE, SCAFFOLDING -> new SimBlock(List.of(), List.of(), 0.6F, 1, 1, "", false, true);
            case DOOR -> new SimBlock(List.of(), List.of(), 0.6F, 1, 1, "door", false, false);
            case GATE -> new SimBlock(List.of(), List.of(), 0.6F, 1, 1, "gate", false, false);
            case SOUL_SAND -> box(t.height(), 0.4F, 1);
            case HONEY -> box(t.height(), 0.4F, 0.5F);
            case HAZARD, VOID, CACTUS, POWDER_SNOW -> box(1, 1, 1);
            default -> box(t.height(), 1, 1);
        };
    }

    private static SimBlock box(double height, float velocity, float jump) {
        return new SimBlock(List.of(new Aabb(0, 0, 0, 1, height, 1)), List.of(0.0, height), 0.6F,
                velocity, jump, "", false, false);
    }
}
