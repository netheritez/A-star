package astar.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import astar.core.BlockPoint;
import astar.core.MoveType;
import astar.core.PathStep;
import astar.core.SearchResult;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Partial blocks: slabs, stairs, carpet, fences, and the headroom they leave. */
class HeightsTest {
    private static final double EPS = 1e-9;
    private static final EntityProfile PLAYER = EntityProfile.PLAYER;

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

    @Test
    void standingHeightsFollowTheBlock() {
        ArrayBlockView w = row("#####", "._-sf");
        MoveValidator v = new MoveValidator(w, PLAYER);
        assertEquals(1.0, v.elevation(0, 1, 0), EPS, "on a full block");
        assertEquals(1.5, v.elevation(1, 1, 0), EPS, "on a slab");
        assertEquals(1 + 1 / 16.0, v.elevation(2, 1, 0), EPS, "on carpet");
        assertEquals(1.875, v.elevation(3, 1, 0), EPS, "on soul sand");
        assertTrue(Double.isNaN(v.elevation(4, 1, 0)), "inside a fence");
        assertTrue(Double.isNaN(v.elevation(4, 2, 0)), "on top of a fence: too thin to stand on");
    }

    @Test
    void slabsAndCarpetAreWalkedOntoNotJumped() {
        // Floor, carpet, a slab, then a full block: rises of 1/16, 7/16 and 0.5, all within the
        // 0.6 step, so it's all walking.
        ArrayBlockView w = row("#####", ".-_#.");
        SearchResult r = new WorldPathfinder(w, PLAYER, false).find(p(0, 1, 0), p(3, 2, 0));
        assertEquals(List.of(p(0, 1, 0), p(1, 1, 0), p(2, 1, 0), p(3, 2, 0)), r.positions());
        assertTrue(moves(r).stream().allMatch(m -> m == MoveType.WALK), moves(r).toString());
    }

    @Test
    void aSlabStaircaseNeedsNoJumps() {
        // Heights 1, 1.5, 2, 2.5, 3, 3.5: slab, block, slab on block, and so on.
        ArrayBlockView w = row("######", "._####", "..._##", "....._");
        SearchResult r = new WorldPathfinder(w, PLAYER, false).find(p(0, 1, 0), p(5, 3, 0));
        assertTrue(r.found());
        assertTrue(moves(r).stream().noneMatch(m -> m == MoveType.JUMP_UP), "all walking: " + moves(r));
    }

    @Test
    void aFullBlockNeedsAJumpAndAFenceCantBeJumped() {
        ArrayBlockView block = row("###", "..#");
        MoveValidator v = new MoveValidator(block, PLAYER);
        assertFalse(v.canWalk(1, 1, 0, 1, 0));
        assertTrue(v.canJumpUp(1, 1, 0, 1, 0));

        ArrayBlockView fence = row("###", "..f");
        MoveValidator fv = new MoveValidator(fence, PLAYER);
        assertFalse(fv.canJumpUp(1, 1, 0, 1, 0), "1.5 is higher than a 1.25 jump");
        assertFalse(fv.canWalk(1, 1, 0, 1, 0));
    }

    @Test
    void stairsAreClimbedByWalking() {
        // A proper staircase of stairs blocks, each resting on the one below, up to a landing.
        ArrayBlockView w = row("######", ".^###.", "..^##.", "...^#.", ".....#");
        SearchResult r = new WorldPathfinder(w, PLAYER, false).find(p(0, 1, 0), p(4, 4, 0));
        assertTrue(r.found(), "up the stairs");
        assertTrue(moves(r).stream().allMatch(m -> m == MoveType.WALK), "no jumps: " + moves(r));
    }

    @Test
    void theTopOfStairsHelpsReachAHigherBlock() {
        // Floor at 1, stairs, then a platform whose top is at 3: from the low step (1.5) that's
        // a 1.5 rise, too high; from the top of the stairs (2) it's a 1-block jump.
        ArrayBlockView w = row("####", ".^##", "..##");
        SearchResult r = new WorldPathfinder(w, PLAYER, false).find(p(0, 1, 0), p(2, 3, 0));
        assertTrue(r.found(), r.status().toString());
        assertEquals(List.of(p(0, 1, 0), p(1, 1, 0), p(1, 2, 0), p(2, 3, 0)), r.positions(),
                "floor, low step, top of the stairs, then one jump");
        assertEquals(List.of(MoveType.WALK, MoveType.WALK, MoveType.JUMP_UP), moves(r));

        // The same with a slab instead of stairs: 1.5 to 3 is too high, so there's no way up.
        ArrayBlockView slab = row("####", "._##", "..##");
        assertFalse(new WorldPathfinder(slab, PLAYER, false).find(p(0, 1, 0), p(2, 3, 0)).found());
    }

    @Test
    void aSlabUnderALowCeilingLeavesNoHeadroom() {
        // Ceiling at y = 3 over x = 1: a 1.8-tall player fits on the floor (1 to 2.8) but not on a
        // slab (1.5 to 3.3).
        ArrayBlockView floor = row("###", "...", "...", ".#.");
        ArrayBlockView slab = row("###", "._.", "...", ".#.");
        assertTrue(new MoveValidator(floor, PLAYER).canStand(1, 1, 0));
        assertFalse(new MoveValidator(slab, PLAYER).canStand(1, 1, 0));
        assertTrue(new MoveValidator(slab, PLAYER.withHeight(1.4)).canStand(1, 1, 0),
                "a shorter entity fits");
    }

    @Test
    void droppingOffASlabCountsTheRealFall() {
        // A slab on a 2-high pillar at x = 1: feet at 3.5, ground at 1: a 2.5 fall.
        ArrayBlockView w = row("####", ".#..", ".#..", "._..");
        MoveValidator v = new MoveValidator(w, PLAYER);
        assertEquals(3.5, v.elevation(1, 3, 0), EPS);
        assertEquals(1, v.dropLanding(1, 3, 0, 1, 0), "down to the ground");
        MoveValidator cautious = new MoveValidator(w, PLAYER.withMaxDrop(2));
        assertEquals(MoveValidator.NO_LANDING, cautious.dropLanding(1, 3, 0, 1, 0), "2.5 > 2");
        MoveValidator enough = new MoveValidator(w, PLAYER.withMaxDrop(2.5));
        assertEquals(1, enough.dropLanding(1, 3, 0, 1, 0), "exactly the limit is fine");
    }

    @Test
    void straightLinesCrossCarpetButNotSlabs() {
        String[] floor = {"#####", "#####", "#####"};
        String[] top = {".....", "..-..", ".._.."};
        ArrayBlockView w = ArrayBlockView.fromLayers(floor, top, new String[] {".....", ".....", "....."},
                new String[] {".....", ".....", "....."});
        MoveValidator v = new MoveValidator(w, PLAYER);
        assertTrue(v.canWalkStraight(p(0, 1, 1), p(4, 1, 1)), "carpet is level enough");
        assertTrue(v.canWalkStraight(p(0, 1, 1), p(2, 1, 1)), "ending on the carpet");
        assertFalse(v.canWalkStraight(p(0, 1, 2), p(4, 1, 2)), "a slab is a step");
        assertTrue(v.canWalkStraight(p(0, 1, 0), p(4, 1, 0)), "the row beside them");
    }

    @Test
    void importedStairsNoLongerForceJumps() {
        // The same shape as a staircase imported before this change (full blocks), now as stairs:
        // the full-block version needs jumps, the stairs version doesn't.
        ArrayBlockView blocks = row("#####", ".####", "..###", "...##", "....#");
        ArrayBlockView stairs = row("#####", ".^###", "..^##", "...^#", "....#");
        SearchResult a = new WorldPathfinder(blocks, PLAYER, false).find(p(0, 1, 0), p(4, 5, 0));
        SearchResult b = new WorldPathfinder(stairs, PLAYER, false).find(p(0, 1, 0), p(4, 5, 0));
        assertEquals(4, moves(a).stream().filter(m -> m == MoveType.JUMP_UP).count());
        assertTrue(b.found());
        assertTrue(moves(b).stream().filter(m -> m == MoveType.JUMP_UP).count() <= 1, moves(b).toString());
    }
}
