package astar.mcworld;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import astar.pathing.BlockType;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BlockMappingTest {

    private static BlockType map(String id, String... props) {
        Map<String, String> p = new java.util.HashMap<>();
        for (int i = 0; i + 1 < props.length; i += 2) {
            p.put(props[i], props[i + 1]);
        }
        return BlockMapping.map(new BlockState(id, p));
    }

    @Test
    void mapsTheCommonCases() {
        assertEquals(BlockType.SOLID, map("minecraft:stone"));
        assertEquals(BlockType.PARTIAL_8, map("minecraft:oak_slab", "type", "bottom"));
        assertEquals(BlockType.SOLID, map("minecraft:oak_slab", "type", "top"));
        assertEquals(BlockType.SOLID, map("minecraft:oak_slab", "type", "double"));
        assertEquals(BlockType.STAIRS, map("minecraft:oak_stairs", "half", "bottom"));
        assertEquals(BlockType.SOLID, map("minecraft:oak_stairs", "half", "top"));
        assertEquals(BlockType.SOUL_SAND, map("minecraft:soul_sand"));
        assertEquals(BlockType.PARTIAL_14, map("minecraft:mud"));
        assertEquals(BlockType.PARTIAL_15, map("minecraft:dirt_path"));
        assertEquals(BlockType.SOLID, map("somemod:weird_block"), "unknown blocks are solid");
        assertEquals(BlockType.AIR, map("minecraft:air"));
        assertEquals(BlockType.AIR, map("minecraft:short_grass"));
        assertEquals(BlockType.AIR, map("minecraft:oak_sapling"));
        assertEquals(BlockType.PARTIAL_1, map("minecraft:red_carpet"));
        assertEquals(BlockType.AIR, map("minecraft:wall_torch"));
        assertEquals(BlockType.AIR, map("minecraft:oak_wall_sign"));
        assertEquals(BlockType.HAZARD, map("minecraft:lava"));
        assertEquals(BlockType.WATER, map("minecraft:water"));
        assertEquals(BlockType.WATER, map("minecraft:kelp_plant"));
        assertEquals(BlockType.WATER, map("minecraft:short_grass", "waterlogged", "true"));
        assertEquals(BlockType.CACTUS, map("minecraft:cactus"));
        assertEquals(BlockType.HAZARD, map("minecraft:campfire", "lit", "true"));
        assertEquals(BlockType.PARTIAL_7, map("minecraft:campfire", "lit", "false"));
        assertEquals(BlockType.CLIMBABLE, map("minecraft:ladder"));
        assertEquals(BlockType.CLIMBABLE, map("minecraft:ladder", "waterlogged", "true"));
        assertEquals(BlockType.CLIMBABLE, map("minecraft:vine"));
        assertEquals(BlockType.CLIMBABLE, map("minecraft:weeping_vines_plant"));
        assertEquals(BlockType.SCAFFOLDING, map("minecraft:scaffolding"));
    }

    @Test
    void doorsTrapdoorsAndGatesDependOnOpen() {
        assertEquals(BlockType.DOOR, map("minecraft:oak_door", "open", "false"));
        assertEquals(BlockType.DOOR, map("minecraft:exposed_copper_door", "open", "false"));
        assertEquals(BlockType.SOLID, map("minecraft:iron_door", "open", "false"), "needs redstone");
        assertEquals(BlockType.AIR, map("minecraft:oak_door", "open", "true"));
        assertEquals(BlockType.AIR, map("minecraft:iron_door", "open", "true"));
        assertEquals(BlockType.AIR, map("minecraft:iron_trapdoor", "open", "true"));
        assertEquals(BlockType.GATE, map("minecraft:spruce_fence_gate", "open", "false"));
        assertEquals(BlockType.AIR, map("minecraft:spruce_fence_gate", "open", "true"));
        assertEquals(BlockType.PARTIAL_3, map("minecraft:iron_trapdoor", "open", "false", "half", "bottom"));
        assertEquals(BlockType.SOLID, map("minecraft:iron_trapdoor", "open", "false", "half", "top"));
    }

    @Test
    void smallDecorationsAndLookalikes() {
        assertEquals(BlockType.partial(6), map("minecraft:potted_oak_sapling"));
        assertEquals(BlockType.partial(6), map("minecraft:flower_pot"));
        assertEquals(BlockType.partial(6), map("minecraft:red_candle", "candles", "3"));
        assertEquals(BlockType.partial(8), map("minecraft:red_candle_cake"));
        assertEquals(BlockType.partial(6), map("minecraft:sea_pickle", "waterlogged", "true"));
        assertEquals(BlockType.AIR, map("minecraft:mangrove_propagule"));
        assertEquals(BlockType.SOLID, map("minecraft:piston_head"));
        assertEquals(BlockType.PARTIAL_8, map("minecraft:zombie_head"));
        assertEquals(BlockType.partial(1), map("minecraft:moss_carpet"));
    }

    @Test
    void openGatesAndTrapdoorsInWaterAreWater() {
        assertEquals(BlockType.WATER, map("minecraft:oak_trapdoor", "open", "true", "waterlogged", "true"));
        assertEquals(BlockType.AIR, map("minecraft:oak_trapdoor", "open", "true", "waterlogged", "false"));
        assertEquals(BlockType.WATER, map("minecraft:oak_fence_gate", "open", "true", "waterlogged", "true"));
        assertEquals(BlockType.GATE, map("minecraft:oak_fence_gate", "open", "false", "waterlogged", "true"));
    }

    @Test
    void terrainWithACost() {
        assertEquals(BlockType.HONEY, map("minecraft:honey_block"));
        assertEquals(BlockType.COBWEB, map("minecraft:cobweb"));
        assertEquals(BlockType.MAGMA, map("minecraft:magma_block"));
        assertEquals(BlockType.BERRY_BUSH, map("minecraft:sweet_berry_bush", "age", "2"));
        assertEquals(BlockType.AIR, map("minecraft:sweet_berry_bush", "age", "0"), "too small to hurt");
        assertEquals(BlockType.POWDER_SNOW, map("minecraft:powder_snow"));
        assertEquals(BlockType.HAZARD, map("minecraft:fire"));
    }

    @Test
    void snowDependsOnLayers() {
        assertEquals(BlockType.AIR, map("minecraft:snow", "layers", "1"));
        assertEquals(BlockType.PARTIAL_8, map("minecraft:snow", "layers", "5"));
        assertEquals(BlockType.PARTIAL_14, map("minecraft:snow", "layers", "8"));
    }

    @Test
    void fencesAndWallsAreTall() {
        assertEquals(BlockType.TALL, map("minecraft:oak_fence"));
        assertEquals(BlockType.TALL, map("minecraft:cobblestone_wall"));
        assertTrue(BlockMapping.isTall(new BlockState("minecraft:oak_fence", Map.of())));
        assertTrue(BlockMapping.isTall(new BlockState("minecraft:cobblestone_wall", Map.of())));
        assertTrue(BlockMapping.isTall(new BlockState("minecraft:oak_fence_gate", Map.of("open", "false"))));
        assertFalse(BlockMapping.isTall(new BlockState("minecraft:oak_fence_gate", Map.of("open", "true"))));
        assertFalse(BlockMapping.isTall(new BlockState("minecraft:oak_wall_sign", Map.of())));
        assertFalse(BlockMapping.isTall(new BlockState("minecraft:stone", Map.of())));
    }
}
