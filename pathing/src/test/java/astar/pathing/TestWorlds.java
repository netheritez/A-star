package astar.pathing;

import astar.core.BlockPoint;
import java.util.Random;

/** Random terrain shared by the property tests. */
final class TestWorlds {
    private TestWorlds() {}

    /**
     * Column heights 0..4, some lava floors, some overhangs that limit headroom, partial blocks
     * on top, and in some worlds a flooded area (a pool over the low ground), ladders, and
     * doors and terrain with a cost.
     */
    static ArrayBlockView random(Random rng, int w, int d) {
        ArrayBlockView world = new ArrayBlockView(w, 9, d);
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                int top = rng.nextInt(5);
                world.fill(x, 0, z, x, top, z, BlockType.SOLID);
                if (rng.nextDouble() < 0.08) {
                    world.set(x, top, z, BlockType.HAZARD);
                }
                if (rng.nextDouble() < 0.1) {
                    world.set(x, top + 3, z, BlockType.SOLID);
                }
                // Partial blocks on top: slabs, stairs, carpet, soul sand, fences.
                double r = rng.nextDouble();
                if (r < 0.10) {
                    world.set(x, top + 1, z, BlockType.PARTIAL_8);
                } else if (r < 0.17) {
                    world.set(x, top + 1, z, BlockType.STAIRS);
                } else if (r < 0.21) {
                    world.set(x, top + 1, z, BlockType.PARTIAL_1);
                } else if (r < 0.24) {
                    world.set(x, top + 1, z, BlockType.PARTIAL_14);
                } else if (r < 0.28) {
                    world.set(x, top + 1, z, BlockType.TALL);
                }
            }
        }
        if (rng.nextBoolean()) {
            flood(rng, world);
        }
        if (rng.nextBoolean()) {
            ladders(rng, world, 0.05 + 0.1 * rng.nextDouble());
        }
        if (rng.nextBoolean()) {
            terrain(rng, world, 0.05 + 0.15 * rng.nextDouble());
        }
        return world;
    }

    private static final BlockType[] ON_FLOOR = {
        BlockType.DOOR, BlockType.GATE, BlockType.COBWEB, BlockType.BERRY_BUSH, BlockType.CACTUS,
    };
    private static final BlockType[] AS_FLOOR = {
        BlockType.SOUL_SAND, BlockType.HONEY, BlockType.MAGMA, BlockType.POWDER_SNOW,
    };

    /**
     * Special blocks: in some columns, a door (two high), gate, cobweb, berry bush or cactus in
     * the first air cell, or the top block turned into soul sand, honey, magma or powder snow.
     */
    static void terrain(Random rng, ArrayBlockView world, double chance) {
        for (int x = 0; x < world.sizeX(); x++) {
            for (int z = 0; z < world.sizeZ(); z++) {
                if (rng.nextDouble() >= chance) {
                    continue;
                }
                int ground = 0;
                while (ground < world.sizeY() && world.blockAt(x, ground, z) != BlockType.AIR) {
                    ground++;
                }
                if (ground >= world.sizeY()) {
                    continue;
                }
                if (rng.nextBoolean()) {
                    BlockType b = ON_FLOOR[rng.nextInt(ON_FLOOR.length)];
                    world.set(x, ground, z, b);
                    if (b == BlockType.DOOR && ground + 1 < world.sizeY()
                            && world.blockAt(x, ground + 1, z) == BlockType.AIR) {
                        world.set(x, ground + 1, z, b);
                    }
                } else if (ground > 0) {
                    world.set(x, ground - 1, z, AS_FLOOR[rng.nextInt(AS_FLOOR.length)]);
                }
            }
        }
    }

    /**
     * A pool: in a random rectangle, every air cell up to a water level becomes water, so low
     * ground floods to varied depths, over floors, slabs, stairs and lava alike.
     */
    private static void flood(Random rng, ArrayBlockView world) {
        int x0 = rng.nextInt(world.sizeX());
        int z0 = rng.nextInt(world.sizeZ());
        int x1 = x0 + rng.nextInt(world.sizeX() - x0);
        int z1 = z0 + rng.nextInt(world.sizeZ() - z0);
        int level = 1 + rng.nextInt(5);
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = 0; y <= level; y++) {
                    if (world.blockAt(x, y, z) == BlockType.AIR) {
                        world.set(x, y, z, BlockType.WATER);
                    }
                }
            }
        }
    }

    /**
     * Ladders (or vines): runs of 1 to 4 climbable cells in some columns, from the ground or
     * hanging a block above it. Only air is replaced, so a run can stop under an overhang.
     */
    private static void ladders(Random rng, ArrayBlockView world, double chance) {
        for (int x = 0; x < world.sizeX(); x++) {
            for (int z = 0; z < world.sizeZ(); z++) {
                if (rng.nextDouble() >= chance) {
                    continue;
                }
                int ground = 0;
                while (ground < world.sizeY() && world.blockAt(x, ground, z) != BlockType.AIR) {
                    ground++;
                }
                int from = ground + (rng.nextDouble() < 0.3 ? 1 : 0);
                int to = Math.min(world.sizeY() - 1, from + rng.nextInt(4));
                for (int y = from; y <= to && world.blockAt(x, y, z) == BlockType.AIR; y++) {
                    world.set(x, y, z, BlockType.CLIMBABLE);
                }
            }
        }
    }

    /** The highest standable cell in a column, or null. */
    static BlockPoint surface(ArrayBlockView world, MoveValidator v, int x, int z) {
        for (int y = world.sizeY() - 1; y > 0; y--) {
            if (v.canStand(x, y, z)) {
                return new BlockPoint(x, y, z);
            }
        }
        return null;
    }
}
