package astar.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import astar.core.BlockPoint;
import astar.core.CostModel;
import astar.core.DefaultCostModel;
import astar.core.Heuristics;
import astar.core.MoveType;
import astar.core.SearchResult;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Doors and gates open for entities that can open them; soul sand, honey and cobwebs slow;
 * magma and berry bushes hurt; cactus and powder snow are avoided.
 */
class DoorsAndTerrainTest {
    private static final double EPS = 1e-9;
    private static final EntityProfile PLAYER = EntityProfile.PLAYER;
    private static final EntityProfile NO_DOORS = PLAYER.withOpensDoors(false);
    private static final TerrainCosts T = TerrainCosts.DEFAULT;

    private static BlockPoint p(int x, int y, int z) {
        return new BlockPoint(x, y, z);
    }

    private static SearchResult find(ArrayBlockView w, EntityProfile profile, boolean diagonal,
            TerrainCosts terrain, BlockPoint from, BlockPoint to) {
        return new WorldPathfinder(w, profile, diagonal, DefaultCostModel.DEFAULT, terrain,
                diagonal ? Heuristics.OCTILE_XZ : Heuristics.MANHATTAN_XZ).find(from, to);
    }

    private static boolean crosses(ArrayBlockView w, SearchResult r, BlockType type) {
        return r.path().stream().anyMatch(s -> w.blockAt(s.pos().x(), s.pos().y(), s.pos().z()) == type
                || w.blockAt(s.pos().x(), s.pos().y() - 1, s.pos().z()) == type);
    }

    /** A 7 x 5 field (feet at y = 1) with a wall across x = 3, and one gap at z = 2. */
    private static ArrayBlockView walled(BlockType gapLow, BlockType gapHigh) {
        ArrayBlockView w = ArrayBlockView.flat(
                "...#...",
                "...#...",
                "...#...",
                "...#...",
                "...#...");
        w.set(3, 1, 2, gapLow);
        w.set(3, 2, 2, gapHigh);
        return w;
    }

    @Test
    void aDoorOpensForAPlayerButNotAZombie() {
        ArrayBlockView w = walled(BlockType.DOOR, BlockType.DOOR);
        SearchResult r = find(w, PLAYER, false, T, p(1, 1, 2), p(5, 1, 2));
        assertTrue(r.found());
        assertTrue(r.positions().contains(p(3, 1, 2)), "through the doorway");
        assertEquals(4 + T.door(), r.cost(), EPS, "four steps, one door opened");

        assertFalse(find(w, NO_DOORS, false, T, p(1, 1, 2), p(5, 1, 2)).found(), "a wall to it");
        assertFalse(find(w, EntityProfile.ZOMBIE, true, T, p(1, 1, 2), p(5, 1, 2)).found());
    }

    @Test
    void aGateOpensButIsAFenceOtherwise() {
        ArrayBlockView w = walled(BlockType.GATE, BlockType.AIR);
        SearchResult r = find(w, PLAYER, true, T, p(1, 1, 2), p(5, 1, 2));
        assertTrue(r.found());
        assertEquals(4 + T.door(), r.cost(), EPS);

        MoveValidator closed = new MoveValidator(w, NO_DOORS);
        assertFalse(closed.canJumpUp(2, 1, 2, 1, 0), "1.5 tall: can't be jumped");
        assertFalse(find(w, NO_DOORS, true, T, p(1, 1, 2), p(5, 1, 2)).found());
    }

    @Test
    void doorCostIsChargedOnceWhenWalkingThroughADoubleDoorSideways() {
        ArrayBlockView w = walled(BlockType.DOOR, BlockType.DOOR);
        w.set(3, 1, 1, BlockType.DOOR);
        w.set(3, 2, 1, BlockType.DOOR);
        CostModel c = new WorldPathfinder(w, PLAYER, false).costs();
        assertEquals(1 + T.door(), c.cost(p(2, 1, 2).pack(), p(3, 1, 2).pack(), MoveType.WALK), EPS);
        assertEquals(1, c.cost(p(3, 1, 2).pack(), p(3, 1, 1).pack(), MoveType.WALK), EPS, "already in");
        assertEquals(1, c.cost(p(3, 1, 2).pack(), p(4, 1, 2).pack(), MoveType.WALK), EPS, "leaving");
    }

    /**
     * A 7 x 5 open field with {@code type} over x = 2..4, z = 0..3: at feet level (y = 1), or
     * as the floor (y = 0). Soul sand and honey floors are stood in, at 14/16 and 15/16.
     */
    private static ArrayBlockView field(BlockType type, boolean asFloor) {
        ArrayBlockView w = ArrayBlockView.flat(".......", ".......", ".......", ".......", ".......");
        w.fill(2, asFloor ? 0 : 1, 0, 4, asFloor ? 0 : 1, 3, type);
        return w;
    }

    @Test
    void slowAndHurtingGroundIsWalkedAroundWhenThatsCheaper() {
        Object[][] cases = {
            {BlockType.SOUL_SAND, true}, {BlockType.HONEY, true}, {BlockType.COBWEB, false},
            {BlockType.BERRY_BUSH, false}, {BlockType.MAGMA, true},
        };
        for (Object[] c : cases) {
            BlockType type = (BlockType) c[0];
            ArrayBlockView w = field(type, (Boolean) c[1]);
            SearchResult r = find(w, PLAYER, true, T, p(0, 1, 2), p(6, 1, 2));
            assertTrue(r.found(), type.toString());
            assertFalse(crosses(w, r, type), type + ": around it, " + r.positions());
            assertEquals(4 * Math.sqrt(2) + 2, r.cost(), EPS, type + ": the detour through z = 4");

            SearchResult plain = find(w, PLAYER, true, TerrainCosts.NONE, p(0, 1, 2), p(6, 1, 2));
            assertEquals(6, plain.cost(), EPS, type + ": straight across, costs off");
            assertTrue(crosses(w, plain, type), type.toString());
        }
    }

    @Test
    void slowdownsAreChargedWhereAMoveEnds() {
        ArrayBlockView w = field(BlockType.SOUL_SAND, true);
        CostModel c = new WorldPathfinder(w, PLAYER, true).costs();
        assertEquals(T.slow(), c.cost(p(1, 1, 2).pack(), p(2, 0, 2).pack(), MoveType.WALK), EPS);
        assertEquals(1, c.cost(p(2, 0, 2).pack(), p(1, 1, 2).pack(), MoveType.WALK), EPS, "off it");
        assertEquals(T.slow(), c.cost(p(2, 0, 2).pack(), p(3, 0, 2).pack(), MoveType.WALK), EPS);
        assertEquals(Math.sqrt(2) * T.slow(),
                c.cost(p(2, 0, 2).pack(), p(3, 0, 3).pack(), MoveType.DIAGONAL), EPS);
        assertEquals(14 / 16.0, new MoveValidator(w, PLAYER).elevation(2, 0, 2), EPS,
                "soul sand is a 14/16 floor, stood in");

        ArrayBlockView m = field(BlockType.MAGMA, true);
        CostModel cm = new WorldPathfinder(m, PLAYER, true).costs();
        assertEquals(1 + T.damage(), cm.cost(p(1, 1, 2).pack(), p(2, 1, 2).pack(), MoveType.WALK), EPS);
        assertEquals(1, cm.cost(p(2, 1, 2).pack(), p(1, 1, 2).pack(), MoveType.WALK), EPS,
                "stepping off doesn't hurt");
    }

    @Test
    void aCobwebAtHeadHeightIsChargedOnTopOfAFence() {
        // On a fence the feet are half a block up, so the body reaches into the third cell up.
        ArrayBlockView w = new ArrayBlockView(3, 6, 1);
        for (int x = 0; x < 3; x++) {
            w.set(x, 0, 0, BlockType.SOLID);
        }
        w.set(1, 1, 0, BlockType.TALL);
        CostModel clear = new WorldPathfinder(w.copy(), PLAYER, false).costs();
        w.set(1, 4, 0, BlockType.COBWEB);
        CostModel webbed = new WorldPathfinder(w, PLAYER, false).costs();
        long from = p(0, 1, 0).pack();
        long onFence = p(1, 2, 0).pack();
        assertEquals(T.web() * clear.cost(from, onFence, MoveType.JUMP_UP),
                webbed.cost(from, onFence, MoveType.JUMP_UP), EPS);
    }

    @Test
    void theOnlyWayAcrossIsTakenAndPaidFor() {
        // A corridor one block wide, entirely over magma in the middle.
        ArrayBlockView w = ArrayBlockView.flat("#######", ".......", "#######");
        w.fill(2, 0, 1, 4, 0, 1, BlockType.MAGMA);
        SearchResult r = find(w, PLAYER, true, T, p(0, 1, 1), p(6, 1, 1));
        assertTrue(r.found());
        assertEquals(6 + 3 * T.damage(), r.cost(), EPS);
    }

    @Test
    void cactusIsNeverEnteredStoodOnOrBrushed() {
        ArrayBlockView w = ArrayBlockView.flat(".....", ".....", ".....", ".....", ".....");
        w.set(2, 1, 2, BlockType.CACTUS);
        MoveValidator v = new MoveValidator(w, PLAYER);
        assertFalse(v.canStand(2, 1, 2));
        assertFalse(v.canStand(2, 2, 2), "not on top either");
        assertFalse(v.canWalkDiagonal(1, 1, 2, 1, 1), "passing its corner");
        assertTrue(v.canWalkDiagonal(1, 1, 3, 1, 1), "a block away");
        assertFalse(v.canWalk(1, 1, 2, 1, 0));
        assertFalse(v.canWalkStraight(p(0, 1, 2), p(4, 1, 2)));
    }

    @Test
    void powderSnowCantBeStoodOnOrWalkedInto() {
        ArrayBlockView w = ArrayBlockView.flat(".....", ".....", ".....");
        w.set(2, 0, 1, BlockType.POWDER_SNOW);
        MoveValidator v = new MoveValidator(w, PLAYER);
        assertFalse(v.canStand(2, 1, 1), "you'd sink in");
        assertFalse(v.canStand(2, 0, 1));
        SearchResult r = new WorldPathfinder(w, PLAYER, true).find(p(0, 1, 1), p(4, 1, 1));
        assertTrue(r.found());
        assertFalse(r.positions().contains(p(2, 1, 1)));
    }

    @Test
    void lilyPadsAreAFloorOnTheWater() {
        ArrayBlockView w = ArrayBlockView.fromLayers(
                new String[] {"#######"},
                new String[] {"#~~~~~#"},
                new String[] {".-----."},
                new String[] {"......."},
                new String[] {"......."});
        SearchResult r = new WorldPathfinder(w, PLAYER, false).find(p(0, 2, 0), p(6, 2, 0));
        assertTrue(r.found());
        assertTrue(r.path().stream().skip(1).allMatch(s -> s.via() == MoveType.WALK), "" + r.path());
    }

    @Test
    void straightLinesKeepOffSpecialBlocks() {
        for (BlockType type : List.of(BlockType.COBWEB, BlockType.BERRY_BUSH, BlockType.MAGMA)) {
            ArrayBlockView w = ArrayBlockView.flat(".......", ".......", ".......");
            w.set(3, type == BlockType.MAGMA ? 0 : 1, 1, type);
            MoveValidator v = new MoveValidator(w, PLAYER);
            assertFalse(v.canWalkStraight(p(0, 1, 1), p(6, 1, 1)), type + " on the line");
            assertTrue(v.canWalkStraight(p(0, 1, 0), p(6, 1, 0)), type + " is out of reach");
        }
        // A soul sand floor: level enough for a line (1/8 lower), but slow.
        ArrayBlockView w = ArrayBlockView.flat(".......", ".......", ".......");
        w.fill(0, 0, 0, 6, 0, 2, BlockType.PARTIAL_15);
        w.set(3, 0, 1, BlockType.PARTIAL_14);
        assertTrue(new MoveValidator(w, PLAYER).canWalkStraight(p(0, 0, 1), p(6, 0, 1)));
        w.set(3, 0, 1, BlockType.SOUL_SAND);
        assertFalse(new MoveValidator(w, PLAYER).canWalkStraight(p(0, 0, 1), p(6, 0, 1)));

        ArrayBlockView d = walled(BlockType.DOOR, BlockType.DOOR);
        WorldPathfinder f = new WorldPathfinder(d, PLAYER, true);
        SearchResult r = f.find(p(0, 1, 2), p(6, 1, 2));
        List<BlockPoint> waypoints = PathSmoother.positions(f.smoother().smooth(r.path()));
        assertTrue(waypoints.contains(p(3, 1, 2)) || waypoints.contains(p(2, 1, 2)),
                "a waypoint at the door: " + waypoints);
        assertFalse(f.validator().canWalkStraight(p(0, 1, 2), p(6, 1, 2)));
    }

    @Test
    void terrainCostsRefuseCheapening() {
        assertThrows(IllegalArgumentException.class, () -> new TerrainCosts(-1, 2, 2, 0));
        assertThrows(IllegalArgumentException.class, () -> new TerrainCosts(0, 0.5, 2, 0));
        assertThrows(IllegalArgumentException.class, () -> new TerrainCosts(0, 2, 0.9, 0));
        assertThrows(IllegalArgumentException.class, () -> new TerrainCosts(0, 2, 2, -3));
    }

    @Test
    void terrainNeverMakesAMoveCheaperOnRandomWorlds() {
        Random rng = new Random(5);
        int checked = 0;
        for (int trial = 0; trial < 200; trial++) {
            ArrayBlockView world = TestWorlds.random(rng, 2 + rng.nextInt(8), 2 + rng.nextInt(8));
            TestWorlds.terrain(rng, world, 0.3);
            WorldPathfinder finder = new WorldPathfinder(world, PLAYER, true);
            MoveValidator v = finder.validator();
            for (int x = 0; x < world.sizeX(); x++) {
                for (int z = 0; z < world.sizeZ(); z++) {
                    for (int y = 0; y < world.sizeY(); y++) {
                        long from = p(x, y, z).pack();
                        v.moves(x, y, z, true, (to, type) -> {
                            double base = DefaultCostModel.DEFAULT.cost(from, to, type);
                            assertTrue(finder.costs().cost(from, to, type) >= base - EPS);
                        });
                        checked++;
                    }
                }
            }
        }
        assertTrue(checked > 10_000);
    }

    @Test
    void shapesDependOnlyOnOpeningDoors() {
        for (BlockType b : BlockType.values()) {
            assertTrue(plain(b.shape(true)), b + " opener");
            assertTrue(plain(b.shape(false)), b + " other");
            if (plain(b)) {
                assertEquals(b, b.shape(true));
                assertEquals(b, b.shape(false));
            }
        }
        assertEquals(BlockType.AIR, BlockType.DOOR.shape(true));
        assertEquals(BlockType.SOLID, BlockType.DOOR.shape(false));
        assertEquals(BlockType.TALL, BlockType.GATE.shape(false));
    }

    /** The kinds that are their own shape: AIR to CLIMBABLE, and scaffolding. */
    private static boolean plain(BlockType b) {
        return b.ordinal() <= BlockType.CLIMBABLE.ordinal() || b == BlockType.SCAFFOLDING;
    }
}
