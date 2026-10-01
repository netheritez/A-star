package astar.viz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import astar.core.BlockPoint;
import astar.core.MoveType;
import astar.core.PathStep;
import astar.core.SearchResult;
import astar.core.SearchResult.Status;
import astar.pathing.ArrayBlockView;
import astar.pathing.BlockType;
import astar.pathing.Waypoint;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class GridRendererTest {
    private static final int CELL = 20;

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    private static BlockPoint p(int x, int y, int z) {
        return new BlockPoint(x, y, z);
    }

    private static SearchResult found(List<PathStep> path, Set<BlockPoint> closed,
            Set<BlockPoint> open) {
        return new SearchResult(Status.FOUND, path, path.size() - 1, closed, open, closed.size());
    }

    /** Samples a cell away from its centre (line, markers) and its edges (grid lines). */
    private static Color corner(BufferedImage img, GridRenderer.Layout layout, int y, int x, int z) {
        int i = layout.index(y);
        return new Color(img.getRGB(layout.originX(i) + x * CELL + CELL / 5,
                layout.originY(i) + z * CELL + CELL / 5));
    }

    private static Color centre(BufferedImage img, GridRenderer.Layout layout, int y, int x, int z) {
        int i = layout.index(y);
        return new Color(img.getRGB(layout.originX(i) + x * CELL + CELL / 2,
                layout.originY(i) + z * CELL + CELL / 2));
    }

    @Test
    void colourCodesEachLayerOfAFlatWorld() {
        ArrayBlockView world = ArrayBlockView.flat(
                ".....",
                ".#...",
                ".....");
        List<PathStep> path = List.of(
                new PathStep(p(0, 1, 0), null),
                new PathStep(p(1, 1, 0), MoveType.WALK),
                new PathStep(p(2, 1, 0), MoveType.WALK));
        SearchResult r = found(path, Set.of(p(0, 1, 0), p(1, 1, 0), p(0, 1, 1)), Set.of(p(0, 1, 2)));

        BufferedImage img = GridRenderer.render(world, r, p(0, 1, 0), p(2, 1, 0), CELL, null);
        GridRenderer.Layout layout = GridRenderer.layout(world, List.of(1), CELL);

        assertEquals(List.of(1), GridRenderer.layers(r, p(0, 1, 0), p(2, 1, 0)));
        assertEquals(Palette.WALL, corner(img, layout, 1, 1, 1));
        assertEquals(Palette.CLOSED, corner(img, layout, 1, 0, 1));
        assertEquals(Palette.OPEN, corner(img, layout, 1, 0, 2));
        assertEquals(Palette.PATH, corner(img, layout, 1, 1, 0));
        assertEquals(Palette.CELL, corner(img, layout, 1, 4, 2));
    }

    @Test
    void drawsOnePanelPerLevelWithGapsHazardsAndMarkers() {
        // Ground at y = 0 with lava at x = 3, and a 1-high step at x = 1..2.
        ArrayBlockView world = new ArrayBlockView(5, 4, 1);
        world.fill(0, 0, 0, 4, 0, 0, BlockType.SOLID);
        world.set(3, 0, 0, BlockType.HAZARD);
        world.fill(1, 1, 0, 2, 1, 0, BlockType.SOLID);
        List<PathStep> path = List.of(
                new PathStep(p(0, 1, 0), null),
                new PathStep(p(1, 2, 0), MoveType.JUMP_UP),
                new PathStep(p(2, 2, 0), MoveType.WALK));
        SearchResult r = found(path, Set.of(), Set.of());
        BlockPoint start = p(0, 1, 0);
        BlockPoint goal = p(2, 2, 0);

        BufferedImage img = GridRenderer.render(world, r, start, goal, CELL, "test");
        List<Integer> layers = GridRenderer.layers(r, start, goal);
        GridRenderer.Layout layout = GridRenderer.layout(world, layers, CELL);

        assertEquals(List.of(1, 2), layers);
        assertTrue(layout.originX(1) > layout.originX(0), "panels side by side");
        assertEquals(Palette.WALL, corner(img, layout, 1, 1, 0), "step block at y = 1");
        assertEquals(Palette.HAZARD, corner(img, layout, 1, 3, 0), "air above lava");
        assertEquals(Palette.PATH, corner(img, layout, 2, 2, 0), "path on top of the step");
        assertEquals(Palette.GAP, corner(img, layout, 2, 4, 0), "nothing under y = 2 at x = 4");
        assertEquals(Palette.JUMP, centre(img, layout, 2, 1, 0), "jump marker");
    }

    @Test
    void drawsTheSmoothedRouteAndItsWaypoints() {
        ArrayBlockView world = ArrayBlockView.flat(".....", ".....", ".....");
        List<PathStep> path = List.of(
                new PathStep(p(0, 1, 0), null),
                new PathStep(p(1, 1, 1), MoveType.DIAGONAL),
                new PathStep(p(2, 1, 1), MoveType.WALK),
                new PathStep(p(4, 1, 2), MoveType.WALK)); // not a real path; just for drawing
        SearchResult r = found(path, Set.of(), Set.of());
        List<Waypoint> smoothed = List.of(
                new Waypoint(p(0, 1, 0), Waypoint.Kind.START),
                new Waypoint(p(2, 1, 1), Waypoint.Kind.STRAIGHT),
                new Waypoint(p(4, 1, 2), Waypoint.Kind.STRAIGHT));

        BufferedImage img = GridRenderer.render(world, r, smoothed, p(0, 1, 0), p(4, 1, 2), CELL, null);
        GridRenderer.Layout layout = GridRenderer.layout(world, List.of(1), CELL);
        assertEquals(Palette.SMOOTH, centre(img, layout, 1, 2, 1), "waypoint marker");

        BufferedImage plain = GridRenderer.render(world, r, p(0, 1, 0), p(4, 1, 2), CELL, null);
        assertTrue(!Palette.SMOOTH.equals(centre(plain, layout, 1, 2, 1)), "not drawn by default");
    }

    @Test
    void partialAndTallBlocksHaveTheirOwnColours() {
        ArrayBlockView w = DemoWorlds.heights();
        assertEquals(Palette.PARTIAL, GridRenderer.blockColour(w, 2, 1, 0), "carpet");
        assertEquals(Palette.PARTIAL, GridRenderer.blockColour(w, 6, 1, 0), "slab");
        assertEquals(Palette.STAIRS, GridRenderer.blockColour(w, 7, 1, 6), "stairs");
        assertEquals(Palette.CELL, GridRenderer.blockColour(w, 7, 2, 6), "on top of the stairs");
        assertEquals(Palette.TALL, GridRenderer.blockColour(w, 5, 1, 0), "fence");
        assertEquals(Palette.TALL, GridRenderer.blockColour(w, 5, 2, 0), "the fence's top half");
        assertEquals(Palette.GAP, GridRenderer.blockColour(w, 2, 2, 0), "above carpet");
        assertEquals(Palette.CELL, GridRenderer.blockColour(w, 0, 1, 0), "on the ground");
    }
}
