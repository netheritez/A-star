package astar.mcworld;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BlockShapesTest {

    private static final BlockShapes SHAPES = BlockShapes.builtIn();

    private static BlockState state(String name, String... props) {
        Map<String, String> p = new LinkedHashMap<>();
        for (int i = 0; i < props.length; i += 2) {
            p.put(props[i], props[i + 1]);
        }
        return new BlockState(name, p);
    }

    @Test
    void theBuiltInTableComesFromTheGame() {
        assertEquals("26.3", SHAPES.version());
        assertTrue(SHAPES.size() > 10_000, "stairs, slabs, fences... in every state: " + SHAPES.size());
    }

    @Test
    void stairsAreASlabAndAFullHeightBackHalf() {
        // Properties in any order, as a chunk palette gives them.
        BlockShapes.Shape s = SHAPES.shape(state("minecraft:stone_brick_stairs",
                "waterlogged", "false", "shape", "straight", "half", "bottom", "facing", "east"));
        assertNotNull(s);
        assertEquals(2, s.boxes().size());
        double top = 0;
        for (double[] b : s.boxes()) {
            top = Math.max(top, b[4]);
        }
        assertEquals(1.0, top);
        assertEquals(List.of(0.0, 0.5, 1.0), s.pointsY());
        assertEquals("", s.kind());
    }

    @Test
    void slabsFencesWallsAndIce() {
        BlockShapes.Shape slab = SHAPES.shape(state("minecraft:smooth_stone_slab", "type",
                "bottom", "waterlogged", "false"));
        assertEquals(1, slab.boxes().size());
        assertEquals(0.5, slab.boxes().get(0)[4]);
        BlockShapes.Shape fence = SHAPES.shape(state("minecraft:oak_fence", "east", "false",
                "north", "false", "south", "false", "west", "false", "waterlogged", "false"));
        assertEquals("fence", fence.kind());
        assertEquals(1.5, fence.boxes().get(0)[4]);
        BlockShapes.Shape wall = SHAPES.shape(state("minecraft:cobblestone_wall", "east", "none",
                "north", "none", "south", "none", "west", "none", "up", "true",
                "waterlogged", "false"));
        assertEquals("wall", wall.kind());
        assertEquals(0.98F, SHAPES.shape(state("minecraft:ice")).friction());
        assertEquals(0.4F, SHAPES.shape(state("minecraft:soul_sand")).speed());
    }

    @Test
    void plainCubesAndBlocksWithRandomOffsetsAreLeftOut() {
        assertNull(SHAPES.shape(state("minecraft:stone")));
        assertNull(SHAPES.shape(state("minecraft:air")));
        assertNull(SHAPES.shape(state("minecraft:bamboo", "age", "0", "leaves", "none",
                "stage", "0")));
    }

    @Test
    void readsTheTableFormat() throws IOException {
        BlockShapes t = BlockShapes.read(new BufferedReader(new StringReader("""
                # collision shapes from Minecraft 1.0
                shape 0 0.6 1.0 1.0 0.0,0.5 0.0,0.0,0.0,1.0,0.5,1.0
                shape 1 0.98 1.0 1.0 - -
                minecraft:x_slab[type=bottom] 0
                minecraft:y_fence 0
                minecraft:z 1
                """)));
        assertEquals("1.0", t.version());
        assertEquals(3, t.size());
        assertEquals("minecraft:x_slab[type=bottom]",
                BlockShapes.key("x_slab", Map.of("type", "bottom")));
        BlockShapes.Shape slab = t.shape(state("minecraft:x_slab", "type", "bottom"));
        assertSame(slab, t.shape(state("x_slab", "type", "bottom")), "the namespace is optional");
        assertEquals("fence", t.shape(state("minecraft:y_fence")).kind());
        assertTrue(t.shape(state("minecraft:z")).boxes().isEmpty());
        assertEquals(0.98F, t.shape(state("minecraft:z")).friction());
    }
}
