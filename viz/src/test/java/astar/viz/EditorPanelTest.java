package astar.viz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import astar.core.BlockPoint;
import astar.pathing.BlockType;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class EditorPanelTest {
    private static final int C = 20;

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    private static BufferedImage paint(EditorPanel panel) {
        Dimension d = panel.getPreferredSize();
        panel.setSize(d);
        BufferedImage img = new BufferedImage(d.width, d.height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        panel.paint(g);
        g.dispose();
        return img;
    }

    @Test
    void paintsTheFrameAndTheHoverOutline() {
        EditorModel model = new EditorModel();
        EditorPanel panel = new EditorPanel(model, C);
        BlockPoint cell = new BlockPoint(2, 1, 3);
        model.setHovered(cell);

        BufferedImage img = paint(panel);
        GridRenderer.Layout layout = GridRenderer.layout(model.world(), model.layers(), C);
        int i = layout.index(cell.y());
        Color edge = new Color(img.getRGB(layout.originX(i) + cell.x() * C + 1,
                layout.originY(i) + cell.z() * C + C / 2));
        assertEquals(Palette.HOVER, edge);
    }

    @Test
    void resizesWhenTheViewChanges() {
        EditorModel model = new EditorModel();
        EditorPanel panel = new EditorPanel(model, C);
        int allLevels = panel.getPreferredSize().height;
        model.setView(1);
        assertTrue(panel.getPreferredSize().height < allLevels);
    }

    @Test
    void inspectorDescribesReachedAndUnreachedCells() {
        EditorModel model = new EditorModel();
        model.first();
        String early = InspectorPanel.describe(model.inspect(model.goal()), model.step());
        assertTrue(early.contains("not reached yet"), early);
        model.last();
        String late = InspectorPanel.describe(model.inspect(model.goal()), model.step());
        assertTrue(late.contains("g:") && late.contains("parent:"), late);
        assertTrue(InspectorPanel.describe(null, 0).contains("Hover"));
    }

    /** Sends a real Swing mouse event to the panel at the centre of a cell. */
    private static void mouse(EditorPanel panel, EditorModel model, int id, BlockPoint cell) {
        GridRenderer.Layout layout = GridRenderer.layout(model.world(), model.layers(), C);
        int i = layout.index(cell.y());
        int x = layout.originX(i) + cell.x() * C + C / 2;
        int y = layout.originY(i) + cell.z() * C + C / 2;
        int button = id == MouseEvent.MOUSE_MOVED ? MouseEvent.NOBUTTON : MouseEvent.BUTTON1;
        int mods = id == MouseEvent.MOUSE_DRAGGED ? MouseEvent.BUTTON1_DOWN_MASK : 0;
        panel.dispatchEvent(new MouseEvent(panel, id, System.currentTimeMillis(), mods, x, y,
                1, false, button));
    }

    @Test
    void mouseEventsReachTheModel() {
        EditorModel model = new EditorModel();
        EditorPanel panel = new EditorPanel(model, C);
        panel.setSize(panel.getPreferredSize());

        BlockPoint hover = new BlockPoint(3, 1, 4);
        mouse(panel, model, MouseEvent.MOUSE_MOVED, hover);
        assertEquals(hover, model.hovered());

        // Drag the goal marker to a new cell.
        BlockPoint newGoal = new BlockPoint(11, 1, 1);
        mouse(panel, model, MouseEvent.MOUSE_PRESSED, model.goal());
        mouse(panel, model, MouseEvent.MOUSE_DRAGGED, newGoal);
        mouse(panel, model, MouseEvent.MOUSE_RELEASED, newGoal);
        assertEquals(newGoal, model.goal());

        // Paint a stroke of lava on the ground.
        model.setTool(EditorModel.Tool.HAZARD);
        mouse(panel, model, MouseEvent.MOUSE_PRESSED, new BlockPoint(9, 1, 5));
        mouse(panel, model, MouseEvent.MOUSE_DRAGGED, new BlockPoint(10, 1, 5));
        mouse(panel, model, MouseEvent.MOUSE_RELEASED, new BlockPoint(10, 1, 5));
        assertEquals(BlockType.HAZARD, model.world().blockAt(9, 1, 5));
        assertEquals(BlockType.HAZARD, model.world().blockAt(10, 1, 5));
    }

    @Test
    void zoomedOutLevelPanelsMapTheMouseToTheRightCell() {
        EditorModel model = new EditorModel();
        EditorPanel panel = new EditorPanel(model, 0.5);
        model.setView(1);
        panel.setSize(panel.getPreferredSize());
        // Rendered at 1 pixel a block and drawn at half size.
        GridRenderer.Layout layout = GridRenderer.layout(model.world(), model.layers(), 1);
        int x = (layout.originX(0) + 6) / 2;
        int y = (layout.originY(0) + 4) / 2;
        assertEquals(new BlockPoint(6, 1, 4), panel.cellAt(x, y));
        assertTrue(panel.getPreferredSize().width <= layout.width() / 2 + 1);
    }

    @Test
    void theViewFromAboveShowsTheWholeMapWithItsLegend() {
        EditorModel model = new EditorModel();
        EditorPanel panel = new EditorPanel(model, C);
        model.setAbove(HeightMap.Style.GREY);
        Dimension d = panel.getPreferredSize();
        assertTrue(d.height >= model.world().sizeZ() * C + RouteImages.LEGEND_H, "" + d);

        // The column under the mouse means its top floor, or the marker standing there.
        int m = GridRenderer.MARGIN;
        BlockPoint goal = model.goal();
        assertEquals(goal, panel.cellAt(m + goal.x() * C + C / 2, m + goal.z() * C + C / 2));
        assertEquals(model.heightMap().top(3, 4), panel.cellAt(m + 3 * C + 1, m + 4 * C + 1));
        assertEquals(null, panel.cellAt(1, 1), "the margin");

        // The terrain is drawn: a floor column is its height-map colour. (At step 0 nothing is
        // explored yet, so no column is tinted.)
        model.first();
        BufferedImage img = paint(panel);
        BlockPoint top = model.heightMap().top(3, 4);
        assertEquals(model.heightMap().colour(3, 4, HeightMap.Style.GREY),
                new Color(img.getRGB(m + 3 * C + 2, m + 4 * C + 2)), "at " + top);

        model.setAbove(HeightMap.Style.HEIGHT);
        img = paint(panel);
        assertEquals(model.heightMap().colour(3, 4, HeightMap.Style.HEIGHT),
                new Color(img.getRGB(m + 3 * C + 2, m + 4 * C + 2)));
    }

    @Test
    void zoomLabelsReadAsPixelsPerBlock() {
        assertEquals("1/8 px", ControlsBar.zoomLabel(0.125));
        assertEquals("1/2 px", ControlsBar.zoomLabel(0.5));
        assertEquals("3/4 px", ControlsBar.zoomLabel(0.75));
        assertEquals("1.5 px", ControlsBar.zoomLabel(1.5));
        assertEquals("32 px", ControlsBar.zoomLabel(32));
    }
}
