package astar.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import astar.core.BlockPoint;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ReachabilityTest {

    @Test
    void aCorridorsLongestRouteRunsEndToEnd() {
        ArrayBlockView world = ArrayBlockView.flat("..........");
        Reachability.Route r = Reachability.longestRoute(world, EntityProfile.DEFAULT, true).orElseThrow();
        assertEquals(9, r.cost(), 1e-9);
        assertEquals(java.util.Set.of(new BlockPoint(0, 1, 0), new BlockPoint(9, 1, 0)),
                java.util.Set.of(r.start(), r.goal()));
    }

    @Test
    void theRouteIsReallyReachableAndCostsWhatItSays() {
        ArrayBlockView world = ArrayBlockView.flat(
                "......#...",
                "..###.#.#.",
                "....#...#.");
        Reachability.Route r = Reachability.longestRoute(world, EntityProfile.DEFAULT, true).orElseThrow();
        var found = new WorldPathfinder(world, EntityProfile.DEFAULT, true).find(r.start(), r.goal());
        assertTrue(found.found());
        assertEquals(r.cost(), found.cost(), 1e-9);
        assertTrue(r.cost() > 9);
    }

    @Test
    void emptyWorldsHaveNoRoute() {
        assertEquals(Optional.empty(),
                Reachability.longestRoute(new ArrayBlockView(3, 3, 3), EntityProfile.DEFAULT, true));
    }

    @Test
    void avoidsOneWayPits() {
        // A long ledge at y = 4 (feet 5) running east, and a pit far to the west that you can
        // drop 3 into from the ledge's end but never climb out of. The longest round trip is
        // along the ledge, not into the pit.
        ArrayBlockView world = new ArrayBlockView(20, 8, 1);
        world.fill(4, 0, 0, 19, 4, 0, BlockType.SOLID);   // ledge top at y = 4
        world.fill(0, 0, 0, 3, 1, 0, BlockType.SOLID);    // pit floor at y = 1 (feet 2)
        Reachability.Route r = Reachability.longestRoute(world, EntityProfile.DEFAULT, false).orElseThrow();
        WorldPathfinder finder = new WorldPathfinder(world, EntityProfile.DEFAULT, false);
        assertTrue(finder.find(r.start(), r.goal()).found());
        assertTrue(finder.find(r.goal(), r.start()).found(), "you can walk back too");
        assertTrue(r.start().y() == 5 && r.goal().y() == 5, "both ends on the ledge: " + r);
        assertEquals(15, r.cost(), 1e-9);
    }

    @Test
    void cropCopiesABoxAndClampsIt() {
        ArrayBlockView world = new ArrayBlockView(6, 4, 5);
        world.set(2, 1, 3, BlockType.HAZARD);
        world.set(5, 3, 4, BlockType.SOLID);
        ArrayBlockView c = world.crop(2, 1, 3, 99, 99, 99);
        assertEquals(java.util.List.of(4, 3, 2), java.util.List.of(c.sizeX(), c.sizeY(), c.sizeZ()));
        assertEquals(BlockType.HAZARD, c.blockAt(0, 0, 0));
        assertEquals(BlockType.SOLID, c.blockAt(3, 2, 1));
        c.set(1, 1, 1, BlockType.SOLID);
        assertEquals(BlockType.AIR, world.blockAt(3, 2, 4), "a crop is a copy");
    }
}
