package astar.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import astar.core.BlockPoint;
import astar.core.DefaultCostModel;
import astar.core.MoveType;
import astar.core.PathStep;
import astar.core.Pos;
import astar.core.SearchResult;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** Water holds the entity up and is swum through; ladders hold it up and are climbed. */
class WaterAndClimbingTest {
    private static final double EPS = 1e-9;
    private static final EntityProfile PLAYER = EntityProfile.PLAYER;
    private static final double SWIM = DefaultCostModel.DEFAULT.swim();
    private static final double CLIMB = DefaultCostModel.DEFAULT.climb();

    private static BlockPoint p(int x, int y, int z) {
        return new BlockPoint(x, y, z);
    }

    /** One row of blocks along x, from ASCII layers (bottom first), 8 high. */
    private static ArrayBlockView row(String... layers) {
        String[][] ls = new String[8][];
        for (int y = 0; y < 8; y++) {
            ls[y] = new String[] {y < layers.length ? layers[y] : ".".repeat(layers[0].length())};
        }
        return ArrayBlockView.fromLayers(ls);
    }

    private static List<MoveType> moves(SearchResult r) {
        return r.path().stream().skip(1).map(PathStep::via).toList();
    }

    private static Map<BlockPoint, MoveType> movesFrom(MoveValidator v, BlockPoint from) {
        Map<BlockPoint, MoveType> out = new TreeMap<>(
                java.util.Comparator.comparingLong(BlockPoint::pack));
        v.moves(from.x(), from.y(), from.z(), true, (to, type) -> out.put(Pos.toPoint(to), type));
        return out;
    }

    @Test
    void waterAndLaddersHoldYouUpButNotWhatsAboveThem() {
        ArrayBlockView w = row("##.", "~H.", "~H.", "...");
        MoveValidator v = new MoveValidator(w, PLAYER);
        assertEquals(1.0, v.elevation(0, 1, 0), EPS, "in shallow water, on the floor");
        assertEquals(2.0, v.elevation(0, 2, 0), EPS, "floating in deeper water");
        assertEquals(2.0, v.elevation(1, 2, 0), EPS, "hanging on a ladder");
        assertTrue(Double.isNaN(v.elevation(0, 3, 0)), "not on top of the water");
        assertTrue(Double.isNaN(v.elevation(1, 3, 0)), "not on top of a ladder");
        assertTrue(v.onFoot(1, 1, 0), "a ladder's bottom block has a floor");
        assertFalse(v.onFoot(1, 2, 0));
        assertFalse(v.onFoot(0, 1, 0), "wading isn't walking");
    }

    @Test
    void swimsAcrossTheSurfaceOfADeepPoolLevelWithTheBanks() {
        // Banks with feet at y = 3, and a pool three deep between them, full to the brim.
        ArrayBlockView w = row("######", "##~~##", "##~~##", "..~~..");
        SearchResult r = new WorldPathfinder(w, PLAYER, false).find(p(0, 3, 0), p(5, 3, 0));
        assertEquals(List.of(p(0, 3, 0), p(1, 3, 0), p(2, 3, 0), p(3, 3, 0), p(4, 3, 0), p(5, 3, 0)),
                r.positions());
        assertEquals(List.of(MoveType.WALK, MoveType.SWIM, MoveType.SWIM, MoveType.SWIM,
                MoveType.WALK), moves(r));
        assertEquals(2 + 3 * SWIM, r.cost(), EPS);
    }

    @Test
    void dropsIntoALowPoolAndSwimsOut() {
        // The water is a block below the banks: step off into it, swim across and up out.
        ArrayBlockView w = row("######", "##~~##", "......");
        SearchResult r = new WorldPathfinder(w, PLAYER, false).find(p(0, 2, 0), p(5, 2, 0));
        assertEquals(List.of(p(0, 2, 0), p(1, 2, 0), p(2, 1, 0), p(3, 1, 0), p(4, 2, 0), p(5, 2, 0)),
                r.positions());
        assertEquals(List.of(MoveType.WALK, MoveType.DROP, MoveType.SWIM, MoveType.SWIM,
                MoveType.WALK), moves(r));
        assertEquals(1 + 1.5 + SWIM + SWIM * Math.sqrt(2) + 1, r.cost(), EPS);
    }

    @Test
    void walksAroundAPuddleWhenThatsCheaper() {
        // Going straight through one cell of water costs two swims (4) instead of two walks;
        // the diagonal detour round it costs 2 sqrt(2).
        ArrayBlockView w = ArrayBlockView.flat(".......", ".......");
        w.set(3, 1, 0, BlockType.WATER);
        SearchResult r = new WorldPathfinder(w, PLAYER, true).find(p(0, 1, 0), p(6, 1, 0));
        assertTrue(moves(r).stream().noneMatch(m -> m == MoveType.SWIM), moves(r).toString());
        assertEquals(4 + 2 * Math.sqrt(2), r.cost(), EPS);
    }

    @Test
    void swimsDownUnderAWallAndBackUp() {
        // A flooded channel with a wall down to two blocks above its floor: the only way past
        // is to dive, swim under and come back up.
        ArrayBlockView w = row("#######", "~~~~~~~", "~~~~~~~", "~~~#~~~", "...#...", "...#...");
        SearchResult r = new WorldPathfinder(w, PLAYER, false).find(p(0, 3, 0), p(6, 3, 0));
        assertTrue(r.found());
        assertTrue(moves(r).stream().allMatch(m -> m == MoveType.SWIM), moves(r).toString());
        assertTrue(r.positions().contains(p(3, 1, 0)), "under the wall: " + r.positions());
        assertTrue(r.path().stream().noneMatch(s -> s.pos().y() > 1 && s.pos().x() == 3));
    }

    @Test
    void climbsALadderUpAWallTooHighToJump() {
        ArrayBlockView noLadder = row("###", "..#", "..#", "..#", "..#");
        assertFalse(new WorldPathfinder(noLadder, PLAYER, false).find(p(0, 1, 0), p(2, 5, 0)).found());

        // Jump onto the ladder's second block, climb to the top one, and step off onto the wall.
        ArrayBlockView w = row("###", ".H#", ".H#", ".H#", ".H#");
        SearchResult r = new WorldPathfinder(w, PLAYER, false).find(p(0, 1, 0), p(2, 5, 0));
        assertEquals(List.of(p(0, 1, 0), p(1, 2, 0), p(1, 3, 0), p(1, 4, 0), p(2, 5, 0)),
                r.positions());
        assertEquals(List.of(MoveType.JUMP_UP, MoveType.CLIMB, MoveType.CLIMB, MoveType.JUMP_UP),
                moves(r));
        assertEquals(2 + 2 * CLIMB + 2, r.cost(), EPS);

        MoveValidator v = new MoveValidator(w, PLAYER);
        assertEquals(MoveType.WALK, movesFrom(v, p(0, 1, 0)).get(p(1, 1, 0)), "onto its foot");
        assertEquals(MoveType.CLIMB, movesFrom(v, p(1, 1, 0)).get(p(1, 2, 0)));

        SearchResult back = new WorldPathfinder(w, PLAYER, false).find(p(2, 5, 0), p(0, 1, 0));
        assertTrue(back.found(), "and back down");
    }

    @Test
    void laddersNeedHeadroom() {
        // A slab above the ladder leaves too little room to climb into its top block.
        ArrayBlockView w = row("###", ".H#", ".H#", ".H#", "._#");
        MoveValidator v = new MoveValidator(w, PLAYER);
        assertFalse(v.canStand(1, 3, 0));
        assertFalse(movesFrom(v, p(1, 2, 0)).containsKey(p(1, 3, 0)));
        assertEquals(MoveType.CLIMB, movesFrom(v, p(1, 2, 0)).get(p(1, 1, 0)));
    }

    @Test
    void steppingOffAHangingLadderSidewaysIsAClimb() {
        // A ladder hanging over a drop, next to a ledge at the same height.
        ArrayBlockView w = row("...", "..#", ".H.", ".H.");
        MoveValidator v = new MoveValidator(w, PLAYER);
        Map<BlockPoint, MoveType> m = movesFrom(v, p(1, 2, 0));
        assertEquals(MoveType.CLIMB, m.get(p(2, 2, 0)), m.toString());
        assertEquals(MoveType.CLIMB, m.get(p(1, 3, 0)), m.toString());
        assertEquals(MoveType.CLIMB, movesFrom(v, p(2, 2, 0)).get(p(1, 2, 0)), "and back on");
    }

    @Test
    void stairsUnderALadderAreStillSteppedOn() {
        // The in-place step onto the top of stairs stays a walk when a ladder is above them.
        ArrayBlockView w = row("##", ".^", ".H", ".H");
        MoveValidator v = new MoveValidator(w, PLAYER);
        assertEquals(MoveType.WALK, movesFrom(v, p(1, 1, 0)).get(p(1, 2, 0)));
        assertEquals(MoveType.CLIMB, movesFrom(v, p(1, 2, 0)).get(p(1, 3, 0)));
    }

    @Test
    void smoothingKeepsEverySwimAndClimb() {
        ArrayBlockView w = row("##..######", "##~~##..##", "..~~..HH..", "......HH..");
        w.fill(6, 1, 0, 7, 1, 0, BlockType.AIR);
        w.fill(6, 0, 0, 7, 0, 0, BlockType.SOLID);
        w.fill(8, 1, 0, 9, 3, 0, BlockType.SOLID);
        w.set(6, 1, 0, BlockType.CLIMBABLE);
        w.set(7, 1, 0, BlockType.CLIMBABLE);
        WorldPathfinder finder = new WorldPathfinder(w, PLAYER, false);
        SearchResult r = finder.find(p(0, 2, 0), p(9, 4, 0));
        assertTrue(r.found());
        List<Waypoint> wp = finder.smoother().smooth(r.path());
        List<BlockPoint> points = PathSmoother.positions(wp);
        for (int i = 1; i < r.path().size(); i++) {
            PathStep s = r.path().get(i);
            if (s.via() == MoveType.SWIM || s.via() == MoveType.CLIMB) {
                int k = points.indexOf(s.pos());
                assertTrue(k > 0, "kept: " + s);
                assertEquals(r.path().get(i - 1).pos(), points.get(k - 1), "from the step before " + s);
                assertEquals(s.via() == MoveType.SWIM ? Waypoint.Kind.SWIM : Waypoint.Kind.CLIMB,
                        wp.get(k).kind());
            }
        }
        assertTrue(moves(r).contains(MoveType.SWIM) && moves(r).contains(MoveType.CLIMB),
                moves(r).toString());
    }

    @Test
    void straightLinesDontCrossWater() {
        // Shallow water in the middle of a level floor: a walk never passes through it.
        ArrayBlockView w = ArrayBlockView.flat(".....");
        w.set(2, 1, 0, BlockType.WATER);
        MoveValidator v = new MoveValidator(w, PLAYER);
        assertFalse(v.canWalkStraight(p(0, 1, 0), p(4, 1, 0)));
        assertTrue(v.canWalkStraight(p(0, 1, 0), p(1, 1, 0)));
    }

    @Test
    void swimmingAndClimbingCostPerBlockCovered() {
        DefaultCostModel c = DefaultCostModel.DEFAULT;
        long o = Pos.pack(0, 5, 0);
        assertEquals(SWIM, c.cost(o, Pos.pack(1, 5, 0), MoveType.SWIM), EPS);
        assertEquals(SWIM * Math.sqrt(2), c.cost(o, Pos.pack(1, 5, 1), MoveType.SWIM), EPS);
        assertEquals(SWIM, c.cost(o, Pos.pack(0, 4, 0), MoveType.SWIM), EPS);
        assertEquals(SWIM * Math.sqrt(5), c.cost(o, Pos.pack(1, 3, 0), MoveType.SWIM), EPS);
        assertEquals(CLIMB, c.cost(o, Pos.pack(0, 6, 0), MoveType.CLIMB), EPS);
        assertTrue(SWIM >= 1 && CLIMB >= 1, "admissible");
    }
}
