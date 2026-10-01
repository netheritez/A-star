package astar.viz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import astar.core.BlockPoint;
import astar.core.MoveType;
import astar.core.PathStep;
import astar.movement.plan.ExecutionPlan;
import astar.movement.plan.PlanBuilder;
import astar.movement.sim.SimBlock;
import astar.pathing.ArrayBlockView;
import astar.pathing.BlockType;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BlockTypeWorldTest {

    @Test
    void blockTypesBecomeBoxesOfTheirHeight() {
        assertTrue(BlockTypeWorld.of(BlockType.AIR).isEmpty());
        assertEquals(1.0, BlockTypeWorld.of(BlockType.SOLID).boxes().get(0).maxY());
        assertEquals(0.5, BlockTypeWorld.of(BlockType.partial(8)).boxes().get(0).maxY());
        assertEquals(1.5, BlockTypeWorld.of(BlockType.TALL).boxes().get(0).maxY());
        SimBlock soul = BlockTypeWorld.of(BlockType.SOUL_SAND);
        assertEquals(0.875, soul.boxes().get(0).maxY());
        assertEquals(0.4F, soul.velocityMultiplier());
        assertEquals("door", BlockTypeWorld.of(BlockType.DOOR).kind());
        assertTrue(BlockTypeWorld.of(BlockType.DOOR).isEmpty(), "doors are planned as opened");
        assertTrue(BlockTypeWorld.of(BlockType.WATER).water());
        assertTrue(BlockTypeWorld.of(BlockType.CLIMBABLE).climbable());
        assertEquals(1.0, BlockTypeWorld.of(BlockType.HAZARD).boxes().get(0).maxY());
    }

    @Test
    void planOnAPathfinderWorldFindsItsDoorAndSummarises() {
        ArrayBlockView w = new ArrayBlockView(12, 4, 3);
        for (int x = 0; x < 12; x++) {
            for (int z = 0; z < 3; z++) {
                w.set(x, 0, z, BlockType.SOLID);
            }
        }
        w.set(6, 1, 1, BlockType.DOOR);
        w.set(6, 2, 1, BlockType.DOOR);
        List<PathStep> path = new ArrayList<>();
        for (int x = 1; x <= 10; x++) {
            path.add(new PathStep(new BlockPoint(x, 1, 1), x == 1 ? null : MoveType.WALK));
        }
        ExecutionPlan plan = new PlanBuilder(new BlockTypeWorld(w)).build(path);
        assertNotNull(plan.nodes().get(5).door());
        assertEquals(1.0, plan.nodes().get(0).y());
        List<String> lines = RouteTool.describe(plan, BlockPoint::toString);
        assertTrue(lines.get(0).startsWith("plan: 10 nodes, 9.0 blocks"), lines.get(0));
        assertTrue(lines.get(1).startsWith("1 doors"), lines.get(1));
    }
}
