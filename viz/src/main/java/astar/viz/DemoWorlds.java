package astar.viz;

import astar.core.BlockPoint;
import astar.pathing.ArrayBlockView;
import astar.pathing.BlockType;

/** The hardcoded demo worlds shared by the console demo and the PNG renders. */
public final class DemoWorlds {
    private DemoWorlds() {}

    /** The original 2D map, ported onto a flat floor (feet at y = 1). */
    public static final String[] FLAT_ROWS = {
        "..........",
        "..######..",
        "........#.",
        ".######.#.",
        "......#...",
        ".####.###.",
        "..........",
    };
    public static final BlockPoint FLAT_START = new BlockPoint(0, 1, 0);
    public static final BlockPoint FLAT_GOAL = new BlockPoint(9, 1, 6);

    public static ArrayBlockView flat() {
        return ArrayBlockView.flat(FLAT_ROWS);
    }

    public static final BlockPoint TERRAIN_START = new BlockPoint(0, 1, 0);
    public static final BlockPoint TERRAIN_GOAL = new BlockPoint(10, 1, 0);

    /**
     * A 4-block-tall plateau (x = 5..7) splits the ground in two:
     *
     * <ul>
     *   <li>a staircase at z = 0 (x = 1..4) climbs onto it;
     *   <li>its east edge is a 4-block cliff, too tall to drop, except at z = 6 where a 1-high
     *       ledge turns it into a 3-block drop;
     *   <li>a lava strip on the far side (x = 9..11, z = 3) forces a detour on the way back.
     * </ul>
     */
    public static ArrayBlockView terrain() {
        ArrayBlockView w = new ArrayBlockView(12, 7, 7);
        w.fill(0, 0, 0, 11, 0, 6, BlockType.SOLID);   // ground
        w.fill(5, 1, 0, 7, 4, 6, BlockType.SOLID);    // plateau
        for (int step = 1; step <= 4; step++) {       // staircase
            w.fill(step, 1, 0, step, step, 0, BlockType.SOLID);
        }
        w.set(8, 1, 6, BlockType.SOLID);              // ledge
        w.fill(9, 0, 3, 11, 0, 3, BlockType.HAZARD);  // lava
        return w;
    }

    public static final BlockPoint HEIGHTS_START = new BlockPoint(0, 1, 0);
    public static final BlockPoint HEIGHTS_GOAL = new BlockPoint(12, 3, 3);

    /**
     * Partial blocks: the goal is on a 2-block platform (x = 9..13) that is too tall to jump,
     * and it takes no jumps at all to get there.
     *
     * <ul>
     *   <li>a carpet strip (x = 2..3) is walked over like the ground;
     *   <li>a fence line at x = 5 can't be jumped (it's 1.5 tall), except through the gap at
     *       z = 6;
     *   <li>two ways up: a slab staircase at z = 0 (slab, block, block with a slab on top) and a
     *       pair of stairs at z = 6, both climbed by walking.
     * </ul>
     */
    public static ArrayBlockView heights() {
        ArrayBlockView w = new ArrayBlockView(14, 6, 7);
        w.fill(0, 0, 0, 13, 0, 6, BlockType.SOLID);           // ground
        w.fill(2, 1, 0, 3, 1, 6, BlockType.PARTIAL_1);        // carpet
        w.fill(5, 1, 0, 5, 1, 5, BlockType.TALL);             // fence, gap at z = 6
        w.fill(9, 1, 0, 13, 2, 6, BlockType.SOLID);           // platform
        w.set(6, 1, 0, BlockType.PARTIAL_8);                  // slab staircase
        w.fill(7, 1, 0, 8, 1, 0, BlockType.SOLID);
        w.set(8, 2, 0, BlockType.PARTIAL_8);
        w.set(7, 1, 6, BlockType.STAIRS);                     // stairs
        w.set(8, 1, 6, BlockType.SOLID);
        w.set(8, 2, 6, BlockType.STAIRS);
        return w;
    }
}
