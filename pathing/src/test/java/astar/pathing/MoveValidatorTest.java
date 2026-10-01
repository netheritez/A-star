package astar.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import astar.core.BlockPoint;
import org.junit.jupiter.api.Test;

class MoveValidatorTest {
    private static final EntityProfile TALL2 = EntityProfile.DEFAULT; // 1.8 tall, jumps 1.25, drops 3

    private static MoveValidator on(ArrayBlockView world) {
        return new MoveValidator(world, TALL2);
    }

    @Test
    void standingNeedsAFloorAndHeadroom() {
        ArrayBlockView world = new ArrayBlockView(3, 4, 1);
        world.set(0, 0, 0, BlockType.SOLID);           // floor under x = 0
        world.set(1, 0, 0, BlockType.SOLID);           // floor under x = 1 ...
        world.set(1, 2, 0, BlockType.SOLID);           // ... but a ceiling at head height
        MoveValidator v = on(world);

        assertTrue(v.canStand(0, 1, 0));
        assertFalse(v.canStand(1, 1, 0), "no headroom");
        assertFalse(v.canStand(2, 1, 0), "no floor");
        assertFalse(v.canStand(0, 0, 0), "inside the floor");
    }

    @Test
    void hazardsAndVoidCannotBeStoodOn() {
        ArrayBlockView world = new ArrayBlockView(2, 3, 1);
        world.set(0, 0, 0, BlockType.HAZARD);
        MoveValidator v = on(world);
        assertFalse(v.canStand(0, 1, 0), "lava floor");
        assertFalse(v.canStand(5, 1, 0), "outside the world");
        assertFalse(v.canStand(1, 0, 0), "floor would be below the world");
    }

    @Test
    void walkingNeedsTheSameHeightCellStandable() {
        ArrayBlockView world = ArrayBlockView.flat("..#");
        MoveValidator v = on(world);
        assertTrue(v.canWalk(0, 1, 0, 1, 0));
        assertFalse(v.canWalk(1, 1, 0, 1, 0), "wall");
    }

    @Test
    void jumpingNeedsHeadroomAboveTheStart() {
        // Floor at y = 0 everywhere; a 1-high step at x = 1.
        String[] floor = {"###"};
        String[] step = {".#."};
        String[] air = {"..."};
        ArrayBlockView open = ArrayBlockView.fromLayers(floor, step, air, air, air);
        assertTrue(on(open).canJumpUp(0, 1, 0, 1, 0));

        // Same, with a block right above the entity's head at the start.
        String[] ceiling = {"#.."};
        ArrayBlockView capped = ArrayBlockView.fromLayers(floor, step, air, ceiling, air);
        assertFalse(on(capped).canJumpUp(0, 1, 0, 1, 0));

        MoveValidator noJump = new MoveValidator(open, EntityProfile.DEFAULT.withJumpHeight(0));
        assertFalse(noJump.canJumpUp(0, 1, 0, 1, 0), "profile can't jump");
    }

    @Test
    void jumpingCantClimbTwoBlocks() {
        String[] floor = {"##"};
        String[] wall = {".#"};
        String[] air = {".."};
        ArrayBlockView world = ArrayBlockView.fromLayers(floor, wall, wall, air, air, air);
        assertFalse(on(world).canJumpUp(0, 1, 0, 1, 0));
    }

    @Test
    void dropsStopAtTheFirstFloorWithinTheLimit() {
        // A ledge at x = 0 with its top at y = 3 (feet at 4); ground at y = 0 (feet at 1).
        String[] solid = {"##"};
        String[] ledge = {"#."};
        String[] air = {".."};
        ArrayBlockView world = ArrayBlockView.fromLayers(solid, ledge, ledge, ledge, air, air);
        assertEquals(1, on(world).dropLanding(0, 4, 0, 1, 0), "3-block drop is allowed");

        MoveValidator cautious = new MoveValidator(world, EntityProfile.DEFAULT.withMaxDrop(2));
        assertEquals(MoveValidator.NO_LANDING, cautious.dropLanding(0, 4, 0, 1, 0), "over the limit");
    }

    @Test
    void dropsRefuseHazardsBlockedColumnsAndPlainWalks() {
        String[] lava = {"#!"};
        String[] ledge = {"#."};
        String[] air = {".."};
        ArrayBlockView world = ArrayBlockView.fromLayers(lava, ledge, air, air);
        assertEquals(MoveValidator.NO_LANDING, on(world).dropLanding(0, 2, 0, 1, 0), "lava below");

        ArrayBlockView flat = ArrayBlockView.flat("..");
        assertEquals(MoveValidator.NO_LANDING, on(flat).dropLanding(0, 1, 0, 1, 0), "just a walk");

        ArrayBlockView walled = ArrayBlockView.flat(".#");
        assertEquals(MoveValidator.NO_LANDING, on(walled).dropLanding(0, 1, 0, 1, 0), "blocked");
    }

    @Test
    void diagonalsDontClipCorners() {
        ArrayBlockView world = ArrayBlockView.flat(
                ".#",
                "..");
        MoveValidator v = on(world);
        assertFalse(v.canWalkDiagonal(0, 1, 0, 1, 1), "corner at (1, 0)");
        assertFalse(v.canWalkDiagonal(0, 1, 1, 1, -1), "target is a wall");

        ArrayBlockView open = ArrayBlockView.flat("..", "..");
        assertTrue(on(open).canWalkDiagonal(0, 1, 0, 1, 1));
    }

    @Test
    void diagonalsDontBrushPastHazards() {
        String[] floor = {"#!", "##"};
        String[] air = {"..", ".."};
        ArrayBlockView world = ArrayBlockView.fromLayers(floor, air, air, air);
        MoveValidator v = on(world);
        assertFalse(v.canWalkDiagonal(0, 1, 1, 1, -1) , "target is above lava");
        assertFalse(v.canWalkDiagonal(0, 1, 0, 1, 1), "side cell (1, 0) is above lava");
        assertTrue(v.canWalk(0, 1, 0, 0, 1), "a straight step past it is fine");
    }

    private static BlockPoint f(int x, int z) {
        return new BlockPoint(x, 1, z); // feet level of a flat world
    }

    @Test
    void straightLinesCrossOpenFloorAtAnyAngle() {
        MoveValidator v = on(ArrayBlockView.flat(
                "......",
                "......",
                "......"));
        assertTrue(v.canWalkStraight(f(0, 0), f(5, 2)));
        assertTrue(v.canWalkStraight(f(0, 2), f(3, 0)));
        assertTrue(v.canWalkStraight(f(2, 1), f(2, 1)), "zero length");
        assertFalse(v.canWalkStraight(f(0, 0), new BlockPoint(1, 2, 0)), "different heights");
    }

    @Test
    void straightLinesDontClipWallCorners() {
        MoveValidator v = on(ArrayBlockView.flat(
                "...",
                ".#.",
                "..."));
        assertFalse(v.canWalkStraight(f(0, 0), f(2, 2)), "straight through the wall");
        assertFalse(v.canWalkStraight(f(0, 1), f(1, 0)), "diagonal past the wall's corner");
        assertTrue(v.canWalkStraight(f(0, 0), f(2, 0)), "along the edge row is fine");
    }

    @Test
    void theCentreLineNeedsAFloor() {
        // A hole at (1, 0, 1): no floor under it.
        ArrayBlockView world = ArrayBlockView.flat("...", "...", "...");
        world.set(1, 0, 1, BlockType.AIR);
        MoveValidator v = on(world);
        assertFalse(v.canWalkStraight(f(0, 1), f(2, 1)), "straight across the hole");
        assertTrue(v.canWalkStraight(f(0, 0), f(2, 0)), "beside it: the body only brushes it");
    }

    @Test
    void theBodyKeepsClearOfLava() {
        // Lava floor under (1, 1). The line from (0, 0) to (4, 1) passes 0.5 / sqrt(17) = 0.12
        // blocks from the lava cell's corner at (2, 1), without entering it.
        ArrayBlockView world = ArrayBlockView.flat(".....", ".....");
        world.set(1, 0, 1, BlockType.HAZARD);
        MoveValidator wide = on(world);                                            // 0.6 wide
        MoveValidator thin = new MoveValidator(world, EntityProfile.DEFAULT.withWidth(0.2)); // 0.2 wide

        assertFalse(wide.canWalkStraight(f(0, 0), f(4, 1)), "0.3 of body reaches the lava");
        assertTrue(thin.canWalkStraight(f(0, 0), f(4, 1)), "0.1 of body stays clear");
        assertTrue(wide.canWalkStraight(f(0, 0), f(4, 0)), "the row beside it keeps 0.5 away");
    }

    @Test
    void everySingleGridStepPassesTheStraightLineCheck() {
        // canWalkStraight generalises the walk and diagonal rules, so every raw flat move passes,
        // unless a door or special terrain is nearby: straight lines keep off those.
        java.util.Random rng = new java.util.Random(11);
        for (int trial = 0; trial < 100; trial++) {
            ArrayBlockView world = TestWorlds.random(rng, 2 + rng.nextInt(8), 2 + rng.nextInt(8));
            MoveValidator v = on(world);
            BlockMoveSource moves = new BlockMoveSource(v, true);
            for (int x = 0; x < world.sizeX(); x++) {
                for (int z = 0; z < world.sizeZ(); z++) {
                    for (int y = 1; y < world.sizeY(); y++) {
                        if (!v.canStand(x, y, z)) {
                            continue;
                        }
                        BlockPoint from = new BlockPoint(x, y, z);
                        moves.moves(from.pack(), (to, type) -> {
                            boolean level = v.elevation(astar.core.Pos.toPoint(to)) == v.elevation(from)
                                    && astar.core.Pos.y(to) == from.y();
                            if (level && (type == astar.core.MoveType.WALK
                                    || type == astar.core.MoveType.DIAGONAL)
                                    && !specialNear(world, from)) {
                                assertTrue(v.canWalkStraight(from, astar.core.Pos.toPoint(to)),
                                        type + " from " + from + " to " + astar.core.Pos.toString(to));
                            }
                        });
                    }
                }
            }
        }
    }

    private static boolean specialNear(ArrayBlockView world, BlockPoint p) {
        for (int x = p.x() - 1; x <= p.x() + 2; x++) {
            for (int z = p.z() - 1; z <= p.z() + 2; z++) {
                for (int y = p.y() - 1; y <= p.y() + 2; y++) {
                    if (world.blockAt(x, y, z).special()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** A 2 x 2 world: floor at y 0 everywhere, and a block on top at (1, 1) to jump onto. */
    private static ArrayBlockView diagonalStep() {
        ArrayBlockView world = new ArrayBlockView(2, 5, 2);
        for (int x = 0; x < 2; x++) {
            for (int z = 0; z < 2; z++) {
                world.set(x, 0, z, BlockType.SOLID);
            }
        }
        world.set(1, 1, 1, BlockType.SOLID);
        return world;
    }

    private static astar.core.MoveType move(MoveValidator v, int x, int y, int z, long to) {
        astar.core.MoveType[] found = {null};
        v.moves(x, y, z, true, (t, type) -> {
            if (t == to) {
                found[0] = type;
            }
        });
        return found[0];
    }

    @Test
    void jumpsUpAndDropsDownDiagonally() {
        ArrayBlockView world = diagonalStep();
        long top = astar.core.Pos.pack(1, 2, 1);
        long low = astar.core.Pos.pack(0, 1, 0);
        assertEquals(astar.core.MoveType.JUMP_UP, move(on(world), 0, 1, 0, top));
        assertEquals(astar.core.MoveType.DROP, move(on(world), 1, 2, 1, low));

        // Not for a profile that can't (the executor's), and not past a wall at the corner.
        MoveValidator straightOnly = new MoveValidator(world, TALL2.withDiagonalLeaps(false));
        assertEquals(null, move(straightOnly, 0, 1, 0, top));
        world.set(1, 1, 0, BlockType.SOLID);
        world.set(1, 2, 0, BlockType.SOLID);
        assertEquals(null, move(on(world), 0, 1, 0, top));
        assertEquals(null, move(on(world), 1, 2, 1, low));
    }

    @Test
    void diagonalJumpsAndDropsCostTheirLength() {
        astar.core.DefaultCostModel c = astar.core.DefaultCostModel.DEFAULT;
        long a = astar.core.Pos.pack(0, 1, 0);
        assertEquals(c.jumpUp() * Math.sqrt(2), c.cost(a, astar.core.Pos.pack(1, 2, 1),
                astar.core.MoveType.JUMP_UP), 1e-9);
        assertEquals(c.dropBase() * Math.sqrt(2) + 2 * c.dropPerBlock(),
                c.cost(astar.core.Pos.pack(0, 3, 0), astar.core.Pos.pack(1, 1, 1),
                        astar.core.MoveType.DROP), 1e-9);
    }
}
