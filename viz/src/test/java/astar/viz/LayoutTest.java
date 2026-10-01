package astar.viz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import astar.core.BlockPoint;
import astar.pathing.ArrayBlockView;
import java.util.List;
import org.junit.jupiter.api.Test;

class LayoutTest {
    private static final int C = 10;

    @Test
    void cellAtInvertsTheCellOrigins() {
        ArrayBlockView world = new ArrayBlockView(5, 6, 4);
        GridRenderer.Layout layout = GridRenderer.layout(world, List.of(1, 2, 3, 4), C);
        for (int i = 0; i < 4; i++) {
            int y = layout.layers().get(i);
            for (int x = 0; x < 5; x++) {
                for (int z = 0; z < 4; z++) {
                    int px = layout.originX(i) + x * C;
                    int py = layout.originY(i) + z * C;
                    BlockPoint expected = new BlockPoint(x, y, z);
                    assertEquals(expected, layout.cellAt(px, py), "top-left of " + expected);
                    assertEquals(expected, layout.cellAt(px + C - 1, py + C - 1), "bottom-right");
                }
            }
        }
    }

    @Test
    void cellAtIsNullOutsidePanels() {
        ArrayBlockView world = new ArrayBlockView(5, 6, 4);
        GridRenderer.Layout layout = GridRenderer.layout(world, List.of(1, 2, 3, 4), C);
        assertNull(layout.cellAt(layout.originX(1) - 1, layout.originY(0) + 5), "gap between panels");
        assertNull(layout.cellAt(layout.originX(0) + 2, layout.originY(0) - 5), "panel label");
        assertNull(layout.cellAt(layout.originX(0) + 5 * C, layout.originY(0)), "just past the edge");
        assertNull(layout.cellAt(5, layout.gridBottom() + 10), "legend");
        assertNull(layout.cellAt(-1, -1));
    }
}
