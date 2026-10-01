package astar.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import astar.core.BlockPoint;
import astar.core.DefaultCostModel;
import astar.core.Heuristic;
import astar.core.SearchResult;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** {@link AutoLandmarks}: built when first needed, rebuilt after edits, same paths throughout. */
class AutoLandmarksTest {
    private static final BlockPoint START = new BlockPoint(0, 1, 1);
    private static final BlockPoint GOAL = new BlockPoint(19, 1, 1);

    /** A floor 20 x 9 with a wall across it, open at one end. */
    private static ArrayBlockView world() {
        ArrayBlockView w = new ArrayBlockView(20, 4, 9);
        w.fill(0, 0, 0, 19, 0, 8, BlockType.SOLID);
        w.fill(10, 1, 0, 10, 2, 7, BlockType.SOLID);
        return w;
    }

    private static WorldPathfinder finder(ArrayBlockView w, Heuristic h) {
        return new WorldPathfinder(w, EntityProfile.DEFAULT, 8, DefaultCostModel.DEFAULT,
                TerrainCosts.DEFAULT, h, 0);
    }

    @Test
    void buildsOnFirstSearchAndAgainAfterAnEdit() {
        ArrayBlockView w = world();
        Heuristic flat = DefaultCostModel.DEFAULT.heuristic(8);
        AutoLandmarks auto = new AutoLandmarks(() -> finder(w, flat), flat);
        WorldPathfinder withLandmarks = finder(w, auto);
        WorldPathfinder plain = finder(w, flat);
        assertNull(auto.landmarks());

        SearchResult a = withLandmarks.find(START, GOAL);
        Landmarks first = auto.landmarks();
        assertNotNull(first);
        assertEquals(1, auto.builds());
        assertEquals(plain.find(START, GOAL).cost(), a.cost(), 1e-9);
        assertTrue(a.expanded() < plain.find(START, GOAL).expanded());

        withLandmarks.find(START, GOAL);
        assertEquals(1, auto.builds(), "kept between searches");

        // Open a gap in the middle of the wall: the old costs are wrong now.
        w.fill(10, 1, 4, 10, 2, 4, BlockType.AIR);
        assertNull(auto.landmarks(), "stale after the edit");
        SearchResult b = withLandmarks.find(START, GOAL);
        assertEquals(2, auto.builds());
        assertNotSame(first, auto.landmarks());
        assertEquals(plain.find(START, GOAL).cost(), b.cost(), 1e-9);
    }

    @Test
    void slowRebuildsRunInTheBackgroundWithTheFlatHeuristicMeanwhile() throws Exception {
        ArrayBlockView w = world();
        Heuristic flat = DefaultCostModel.DEFAULT.heuristic(8);
        CountDownLatch ready = new CountDownLatch(1);
        // Every build counts as slow, so all but the first go to the background.
        AutoLandmarks auto = new AutoLandmarks(() -> finder(w, flat), flat, 4, -1,
                ready::countDown);
        WorldPathfinder withLandmarks = finder(w, auto);
        WorldPathfinder plain = finder(w, flat);
        withLandmarks.find(START, GOAL);

        w.fill(10, 1, 4, 10, 2, 4, BlockType.AIR);
        SearchResult meanwhile = withLandmarks.find(START, GOAL);
        assertEquals(plain.find(START, GOAL).expanded(), meanwhile.expanded(),
                "the flat heuristic while it builds");
        assertTrue(ready.await(10, TimeUnit.SECONDS), "told when it's ready");

        SearchResult after = withLandmarks.find(START, GOAL);
        assertNotNull(auto.landmarks());
        assertEquals(plain.find(START, GOAL).cost(), after.cost(), 1e-9);
        assertTrue(after.expanded() < meanwhile.expanded());
    }
}
