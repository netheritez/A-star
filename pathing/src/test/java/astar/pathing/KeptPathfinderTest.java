package astar.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import astar.core.BlockPoint;
import astar.core.DefaultCostModel;
import astar.core.SearchResult;
import org.junit.jupiter.api.Test;

/**
 * {@link KeptPathfinder}: one graph and one set of landmarks for many trips, the first trip
 * not waiting for either, and the same paths as a new pathfinder every time.
 */
class KeptPathfinderTest {
    private static final BlockPoint A = new BlockPoint(0, 1, 1);
    private static final BlockPoint B = new BlockPoint(19, 1, 1);
    private static final BlockPoint C = new BlockPoint(3, 1, 8);

    /** A floor 20 x 9 with a wall across it, open at one end. */
    private static ArrayBlockView world() {
        ArrayBlockView w = new ArrayBlockView(20, 4, 9);
        w.fill(0, 0, 0, 19, 0, 8, BlockType.SOLID);
        w.fill(10, 1, 0, 10, 2, 7, BlockType.SOLID);
        return w;
    }

    private static WorldPathfinder fresh(ArrayBlockView w) {
        return new WorldPathfinder(w, EntityProfile.DEFAULT, 8, DefaultCostModel.DEFAULT,
                TerrainCosts.DEFAULT, DefaultCostModel.DEFAULT.heuristic(8), 0);
    }

    private static void waitForLandmarks(KeptPathfinder kept) throws InterruptedException {
        for (int i = 0; i < 1000 && kept.buildingLandmarks(); i++) {
            Thread.sleep(10);
        }
        assertFalse(kept.buildingLandmarks());
    }

    @Test
    void firstTripDoesNotWaitAndLaterTripsReuseTheLandmarks() throws Exception {
        ArrayBlockView w = world();
        KeptPathfinder kept = new KeptPathfinder(fresh(w), 4);

        SearchResult first = kept.forStart(A.pack()).find(A, B);
        assertEquals(fresh(w).find(A, B).cost(), first.cost(), 1e-9);
        assertEquals(fresh(w).find(A, B).expanded(), first.expanded(), "the flat heuristic");
        assertEquals(1, kept.landmarkBuilds());
        waitForLandmarks(kept);
        assertNotNull(kept.landmarks());
        assertNotNull(kept.base().graph(), "the landmarks built the kept graph");

        // Trips from elsewhere: no new graph or landmarks, fewer nodes, the same costs.
        for (BlockPoint[] trip : new BlockPoint[][] {{B, C}, {C, A}, {A, B}}) {
            SearchResult r = kept.forStart(trip[0].pack()).find(trip[0], trip[1]);
            SearchResult plain = fresh(w).find(trip[0], trip[1]);
            assertEquals(plain.cost(), r.cost(), 1e-9);
            assertTrue(r.expanded() <= plain.expanded());
        }
        assertEquals(1, kept.landmarkBuilds());
        assertEquals(1, kept.base().graphBuilds());
        assertTrue(kept.forStart(A.pack()).find(A, B).expanded() < first.expanded());
    }

    @Test
    void keepsFindingTheCheapestPathAfterAnEdit() throws Exception {
        ArrayBlockView w = world();
        KeptPathfinder kept = new KeptPathfinder(fresh(w), 4);
        kept.forStart(A.pack()).find(A, B);
        waitForLandmarks(kept);

        // Open a gap in the middle of the wall: the old graph and landmarks are out of date.
        w.fill(10, 1, 4, 10, 2, 4, BlockType.AIR);
        assertNull(kept.landmarks());
        SearchResult r = kept.forStart(A.pack()).find(A, B);
        assertEquals(fresh(w).find(A, B).cost(), r.cost(), 1e-9);
        waitForLandmarks(kept);
        assertNotNull(kept.landmarks(), "rebuilt in the background");
        assertEquals(fresh(w).find(A, B).cost(), kept.forStart(A.pack()).find(A, B).cost(),
                1e-9);
    }

    @Test
    void withoutLandmarksTheGraphIsBuiltBeforeTheFirstTrip() {
        ArrayBlockView w = world();
        KeptPathfinder kept = new KeptPathfinder(fresh(w), 0);
        SearchResult r = kept.forStart(A.pack()).find(A, B);
        assertEquals(fresh(w).find(A, B).cost(), r.cost(), 1e-9);
        assertNotNull(kept.base().graph());
        kept.forStart(C.pack()).find(C, B);
        assertEquals(1, kept.base().graphBuilds());
        assertNull(kept.landmarks());
    }
}
