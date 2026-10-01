package astar.viz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import astar.core.BlockPoint;
import astar.core.NodeView;
import astar.core.PathStep;
import astar.core.SearchResult;
import astar.pathing.ArrayBlockView;
import astar.pathing.BlockType;
import astar.pathing.EntityProfile;
import astar.pathing.WorldPathfinder;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RecordingTest {
    private static final double EPS = 1e-9;

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    private record Run(SearchResult result, Recording recording) {}

    private static Run record(ArrayBlockView world, boolean diagonal, BlockPoint start,
            BlockPoint goal) {
        SearchRecorder recorder = new SearchRecorder();
        SearchResult r = new WorldPathfinder(world, EntityProfile.DEFAULT, diagonal)
                .find(start, goal, recorder);
        return new Run(r, recorder.recording());
    }

    private static Run terrain() {
        return record(DemoWorlds.terrain(), true, DemoWorlds.TERRAIN_START, DemoWorlds.TERRAIN_GOAL);
    }

    @Test
    void finalFrameMatchesTheResult() {
        Run run = terrain();
        FrameState last = run.recording().frame(run.recording().lastStep());
        assertTrue(last.finished());
        assertEquals(run.result(), last.result());
        assertEquals(run.result().closed(), last.closed());
        assertEquals(run.result().open(), last.open());
        assertEquals(run.result().path(), last.path());
        assertEquals(run.result().expanded(), run.recording().lastStep());
    }

    @Test
    void eachFrameHasOneMoreExpansion() {
        Run run = terrain();
        Recording rec = run.recording();
        for (int k = 0; k <= rec.lastStep(); k++) {
            FrameState f = rec.frame(k);
            assertEquals(k, f.closed().size(), "frame " + k);
            assertEquals(k, f.step());
            for (BlockPoint p : f.open()) {
                assertFalse(f.closed().contains(p), "in both sets at frame " + k);
            }
        }
        FrameState first = rec.frame(0);
        assertEquals(java.util.Set.of(DemoWorlds.TERRAIN_START), first.open());
        assertNull(first.current());
        assertEquals(List.of(), first.path());
    }

    @Test
    void steppingBackAndForthGivesIdenticalFrames() {
        Recording rec = terrain().recording();
        FrameState forward = rec.frame(20);
        rec.frame(40);
        rec.frame(5);
        assertEquals(forward, rec.frame(20));
    }

    @Test
    void costsOnlyGoDownOverTime() {
        Random rng = new Random(3);
        for (int trial = 0; trial < 20; trial++) {
            ArrayBlockView world = new ArrayBlockView(12, 4, 12);
            world.fill(0, 0, 0, 11, 0, 11, BlockType.SOLID);
            for (int i = 0; i < 40; i++) {
                int x = rng.nextInt(12), z = rng.nextInt(12);
                world.fill(x, 1, z, x, 2, z, BlockType.SOLID);
            }
            world.fill(0, 1, 0, 0, 2, 0, BlockType.AIR);
            world.fill(11, 1, 11, 11, 2, 11, BlockType.AIR);
            Run run = record(world, true, new BlockPoint(0, 1, 0), new BlockPoint(11, 1, 11));
            Recording rec = run.recording();
            FrameState end = rec.frame(rec.lastStep());
            for (int k = 0; k <= rec.lastStep(); k += 7) {
                for (NodeView earlier : rec.frame(k).nodes().values()) {
                    NodeView later = end.inspect(earlier.pos());
                    assertTrue(later.g() <= earlier.g() + EPS, "g went up at " + earlier.pos());
                }
            }
        }
    }

    @Test
    void inProgressPathLeadsFromStartToTheCurrentNode() {
        Recording rec = terrain().recording();
        FrameState f = rec.frame(rec.lastStep() / 2);
        List<PathStep> path = f.path();
        assertFalse(f.finished());
        assertEquals(DemoWorlds.TERRAIN_START, path.get(0).pos());
        assertEquals(f.current(), path.get(path.size() - 1).pos());
        assertEquals(f.inspect(f.current()).g(),
                f.inspect(path.get(path.size() - 1).pos()).g(), EPS);
    }

    @Test
    void layersAreStableAcrossTheReplay() {
        Run run = terrain();
        List<Integer> layers = GridRenderer.layers(run.recording());
        assertEquals(List.of(1, 2, 3, 4, 5), layers);
        assertTrue(layers.containsAll(GridRenderer.layers(run.result(),
                DemoWorlds.TERRAIN_START, DemoWorlds.TERRAIN_GOAL)));
    }

    @Test
    void rendersTheNodeBeingExpanded() {
        Run run = terrain();
        Recording rec = run.recording();
        List<Integer> layers = GridRenderer.layers(rec);
        FrameState f = rec.frame(10);
        int cell = 20;
        BufferedImage img = GridRenderer.render(DemoWorlds.terrain(), f, layers, cell, null);
        GridRenderer.Layout layout = GridRenderer.layout(DemoWorlds.terrain(), layers, cell);

        BlockPoint c = f.current();
        int i = layout.index(c.y());
        Color colour = new Color(img.getRGB(layout.originX(i) + c.x() * cell + cell / 5,
                layout.originY(i) + c.z() * cell + cell / 5));
        assertEquals(Palette.CURRENT, colour);
    }

    @Test
    void rejectsBadInput() {
        Recording rec = terrain().recording();
        assertThrows(IndexOutOfBoundsException.class, () -> rec.frame(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> rec.frame(rec.lastStep() + 1));
        assertThrows(IllegalArgumentException.class, () -> new Recording(List.of()));
    }

    @Test
    void refusedSearchesRecordNothing() {
        SearchRecorder recorder = new SearchRecorder();
        ArrayBlockView world = ArrayBlockView.flat("..#");
        SearchResult r = new WorldPathfinder(world, EntityProfile.DEFAULT, false)
                .find(new BlockPoint(0, 1, 0), new BlockPoint(2, 1, 0), recorder); // goal in a wall
        assertFalse(r.found());
        assertTrue(recorder.isEmpty());
        assertThrows(IllegalStateException.class, recorder::recording);
    }
}
