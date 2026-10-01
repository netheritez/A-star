package astar.movement.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import astar.core.BlockPoint;
import astar.core.MoveType;
import astar.core.PathStep;
import astar.movement.exec.Executor;
import astar.movement.exec.SimRun;
import astar.movement.plan.ExecutionPlan.Edge;
import astar.movement.plan.ExecutionPlan.Kind;
import astar.movement.plan.ExecutionPlan.Node;
import astar.movement.sim.Aabb;
import astar.movement.sim.SimBlock;
import astar.movement.sim.SimWorld;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlanBuilderTest {

    private static final SimBlock CUBE = SimBlock.solid(new Aabb(0, 0, 0, 1, 1, 1));
    private static final SimBlock SLAB = new SimBlock(List.of(new Aabb(0, 0, 0, 1, 0.5, 1)),
            List.of(0.0, 0.5, 1.0), 0.6F, 1, 1, "", false, false);
    /** A stair going up towards +z: a bottom slab, and its back half a full block high. */
    private static final SimBlock STAIR = new SimBlock(List.of(new Aabb(0, 0, 0, 1, 0.5, 1),
            new Aabb(0, 0.5, 0.5, 1, 1, 1)), List.of(0.0, 0.5, 1.0), 0.6F, 1, 1, "", false, false);
    private static final SimBlock DOOR = new SimBlock(List.of(), List.of(), 0.6F, 1, 1, "door",
            false, false);

    /** Blocks placed one by one; everything else is air. */
    private static final class World implements SimWorld {
        final Map<List<Integer>, SimBlock> blocks = new HashMap<>();

        World put(int x, int y, int z, SimBlock b) {
            blocks.put(List.of(x, y, z), b);
            return this;
        }

        /** A floor of cubes at y -1 from (x0, z0) to (x1, z1). */
        World floor(int x0, int z0, int x1, int z1) {
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    put(x, -1, z, CUBE);
                }
            }
            return this;
        }

        @Override
        public SimBlock block(int x, int y, int z) {
            return blocks.getOrDefault(List.of(x, y, z), SimBlock.AIR);
        }
    }

    /** A path along x at y 0 and z 0, from x0 to x1, walking. */
    private static List<PathStep> straight(int x0, int x1) {
        List<PathStep> path = new ArrayList<>();
        for (int x = x0; x <= x1; x++) {
            path.add(new PathStep(new BlockPoint(x, 0, 0), x == x0 ? null : MoveType.WALK));
        }
        return path;
    }

    @Test
    void openFloorIsOneStraightRunAtSprintSpeedThatStopsAtTheGoal() {
        ExecutionPlan plan = new PlanBuilder(new World().floor(-10, -10, 30, 10))
                .build(straight(0, 20));
        assertEquals(List.of(new ExecutionPlan.Segment(0, 20, Kind.STRAIGHT)), plan.segments());
        Node first = plan.nodes().get(0);
        assertEquals(0.0, first.y());
        assertTrue(first.floor());
        assertEquals(Edge.OPEN, first.left().edge());
        assertEquals(PlanBuilder.MAX_WIDTH, first.right().width());
        assertEquals(ExecutionPlan.SPRINT_SPEED, first.speedLimit());
        assertEquals(0.0, plan.nodes().get(20).speedLimit());
        assertTrue(plan.nodes().get(19).speedLimit() <= 0.3 + 1e-9);
        assertEquals(20.0, plan.length());
        for (int i = 1; i < 21; i++) {
            assertTrue(plan.nodes().get(i).speedLimit() <= plan.nodes().get(i - 1).speedLimit());
        }
    }

    @Test
    void aOneBlockBridgeHasDropsOnBothSides() {
        ExecutionPlan plan = new PlanBuilder(new World().floor(0, 0, 10, 0)).build(straight(0,
                10));
        Node n = plan.nodes().get(5);
        assertEquals(Edge.DROP, n.left().edge());
        assertEquals(Edge.DROP, n.right().edge());
        // The body is 0.6 wide and falls once none of it is over the bridge.
        assertEquals(0.75, n.left().width(), 1e-9);
        assertEquals(0.75, n.right().width(), 1e-9);
    }

    @Test
    void aCorridorBetweenWallsLeavesTheBodyALittleRoom() {
        World w = new World().floor(0, -3, 10, 3);
        for (int x = 0; x <= 10; x++) {
            for (int y = 0; y < 2; y++) {
                w.put(x, y, -1, CUBE).put(x, y, 1, CUBE);
            }
        }
        Node n = new PlanBuilder(w).build(straight(0, 10)).nodes().get(5);
        assertEquals(Edge.WALL, n.left().edge());
        assertEquals(0.2, n.left().width(), 1e-9);
        assertEquals(Edge.WALL, n.right().edge());
    }

    @Test
    void floorsAreWhereTheFeetReallyStand() {
        World w = new World().floor(0, -2, 10, 2).put(3, 0, 0, SLAB);
        ExecutionPlan plan = new PlanBuilder(w).build(straight(0, 6));
        assertEquals(0.5, plan.nodes().get(3).y());
        assertEquals(0.0, plan.nodes().get(4).y());
        // The slab is its own run: the floor changes height there.
        assertEquals(3, plan.segments().size());
    }

    @Test
    void sharpCornersAreWalked() {
        // An L-shaped bridge: east along z 0, then south along x 5.
        World w = new World().floor(0, 0, 5, 0).floor(5, 0, 5, 10);
        List<PathStep> path = new ArrayList<>(straight(0, 5));
        for (int z = 1; z <= 10; z++) {
            path.add(new PathStep(new BlockPoint(5, 0, z), MoveType.WALK));
        }
        ExecutionPlan plan = new PlanBuilder(w).roundCorners(false).build(path);
        Node corner = plan.nodes().get(5);
        assertEquals(ExecutionPlan.WALK_SPEED, corner.speedLimit(), 1e-9);
        // Leaving south, the outside of the corner (straight on, east) is on the left.
        assertEquals(Edge.DROP, corner.left().edge());
        assertEquals(ExecutionPlan.SPRINT_SPEED, plan.nodes().get(1).speedLimit());
        assertEquals(ExecutionPlan.SPRINT_SPEED, plan.nodes().get(8).speedLimit());
    }

    @Test
    void aCornerWithLittleRoomBeforeADropIsSlowerStill() {
        // A walkway of thin rails (like the top of a wall): drops start close to the route.
        SimBlock rail = new SimBlock(List.of(new Aabb(0.4, 0, 0.4, 0.6, 1, 0.6)),
                List.of(0.0, 1.0), 0.6F, 1, 1, "", false, false);
        World w = new World();
        List<PathStep> path = new ArrayList<>();
        for (int i = 0; i <= 10; i++) {
            w.put(i, -1, i, rail).put(i + 1, -1, i, rail);
            path.add(new PathStep(new BlockPoint(i, 0, i), i == 0 ? null : MoveType.WALK));
            if (i < 10) {
                path.add(new PathStep(new BlockPoint(i + 1, 0, i), MoveType.WALK));
            }
        }
        ExecutionPlan plan = new PlanBuilder(w).build(path);
        Node corner = plan.nodes().get(5);
        assertEquals(Edge.DROP, corner.left().edge());
        assertTrue(corner.speedLimit() < ExecutionPlan.WALK_SPEED, "limit " + corner.speedLimit());
    }

    @Test
    void doorsAreFoundAndWalkedThrough() {
        World w = new World().floor(0, -2, 10, 2).put(4, 0, 0, DOOR).put(4, 1, 0, DOOR);
        ExecutionPlan plan = new PlanBuilder(w).build(straight(0, 10));
        assertNotNull(plan.nodes().get(4).door());
        assertEquals(new BlockPoint(4, 0, 0), plan.nodes().get(4).door().pos());
        assertNull(plan.nodes().get(5).door());
        assertTrue(plan.nodes().get(3).speedLimit() <= ExecutionPlan.WALK_SPEED);
    }

    @Test
    void jumpsAndDropsAreSegmentsOfTheirOwn() {
        World w = new World().floor(0, -2, 10, 2).put(3, 0, 0, CUBE).put(4, 0, 0, CUBE);
        List<PathStep> path = List.of(
                new PathStep(new BlockPoint(1, 0, 0), null),
                new PathStep(new BlockPoint(2, 0, 0), MoveType.WALK),
                new PathStep(new BlockPoint(3, 1, 0), MoveType.JUMP_UP),
                new PathStep(new BlockPoint(4, 1, 0), MoveType.WALK),
                new PathStep(new BlockPoint(5, 0, 0), MoveType.DROP),
                new PathStep(new BlockPoint(6, 0, 0), MoveType.WALK));
        ExecutionPlan plan = new PlanBuilder(w).build(path);
        assertEquals(List.of(Kind.STRAIGHT, Kind.JUMP_UP, Kind.STRAIGHT, Kind.DROP,
                Kind.STRAIGHT), plan.segments().stream().map(ExecutionPlan.Segment::kind).toList());
        assertEquals(1.0, plan.nodes().get(2).y());
        assertEquals(0.0, plan.nodes().get(4).y());
    }

    @Test
    void aWalkUpMoreThanAStepIsAJump() {
        // Stairs the grid walks up, but whose floors (as slabs here) are a block apart.
        World w = new World();
        for (int x = 0; x <= 6; x++) {
            w.put(x, -1, 0, SLAB);
        }
        w.put(3, 0, 0, SLAB).put(4, 0, 0, SLAB).put(4, 1, 0, SLAB);
        List<PathStep> path = List.of(
                new PathStep(new BlockPoint(1, 0, 0), null),
                new PathStep(new BlockPoint(2, 0, 0), MoveType.WALK),
                new PathStep(new BlockPoint(3, 0, 0), MoveType.WALK),
                new PathStep(new BlockPoint(4, 1, 0), MoveType.WALK));
        ExecutionPlan plan = new PlanBuilder(w).build(path);
        assertEquals(-0.5, plan.nodes().get(1).y());
        assertEquals(0.5, plan.nodes().get(2).y());
        assertEquals(1.5, plan.nodes().get(3).y());
        assertEquals(List.of(Kind.STRAIGHT, Kind.JUMP_UP, Kind.JUMP_UP),
                plan.segments().stream().map(ExecutionPlan.Segment::kind).toList());
    }

    @Test
    void aStairSteppedOntoFromItsSideIsCrossedOnItsLowHalf() {
        World w = new World().floor(-2, -2, 6, 2).put(2, 0, 0, STAIR);
        ExecutionPlan plan = new PlanBuilder(w).build(straight(0, 4));
        // The stair's back half is a block high: the body passes beside it, not through it,
        // on the line from each node to the next.
        assertTrue(plan.nodes().get(2).z() <= 0.2 + 1e-9, "on the stair");
        for (int i = 1; i <= 2; i++) {
            assertTrue((plan.nodes().get(i).z() + plan.nodes().get(i + 1).z()) / 2 <= 0.2 + 1e-9,
                    "from node " + i);
        }
        assertEquals(0.5, plan.nodes().get(2).y());
        assertTrue(SimRun.run(w, plan, Executor.Settings.DEFAULT, 400).failures().isEmpty());
        assertEquals(List.of(Kind.STRAIGHT, Kind.STRAIGHT, Kind.STRAIGHT),
                plan.segments().stream().map(ExecutionPlan.Segment::kind).toList());
    }

    @Test
    void theBackOfAStairTakesAJump() {
        World w = new World().floor(-2, -2, 2, 5).put(0, 0, 1, STAIR);
        List<PathStep> path = List.of(
                new PathStep(new BlockPoint(0, 0, 3), null),
                new PathStep(new BlockPoint(0, 0, 2), MoveType.WALK),
                new PathStep(new BlockPoint(0, 0, 1), MoveType.WALK),
                new PathStep(new BlockPoint(0, 0, 0), MoveType.WALK));
        ExecutionPlan plan = new PlanBuilder(w).build(path);
        assertEquals(List.of(Kind.STRAIGHT, Kind.JUMP_UP, Kind.STRAIGHT),
                plan.segments().stream().map(ExecutionPlan.Segment::kind).toList());
    }

    @Test
    void theTopOfAStairIsItsBackHalf() {
        World w = new World().floor(-2, -2, 2, 5).put(0, 0, 1, STAIR).put(0, 0, 2, CUBE);
        List<PathStep> path = List.of(
                new PathStep(new BlockPoint(0, 0, 0), null),
                new PathStep(new BlockPoint(0, 0, 1), MoveType.WALK),
                new PathStep(new BlockPoint(0, 1, 1), MoveType.WALK),
                new PathStep(new BlockPoint(0, 1, 2), MoveType.WALK));
        ExecutionPlan plan = new PlanBuilder(w).build(path);
        Node low = plan.nodes().get(1);
        Node top = plan.nodes().get(2);
        assertEquals(0.5, low.y());
        assertTrue(low.z() <= 1.2 + 1e-9, "stands clear of the back half: " + low.z());
        assertEquals(1.0, top.y());
        assertEquals(1.75, top.z(), 1e-9);
        assertEquals(0.5, top.x(), 1e-9);
    }

    @Test
    void aStaircaseThatTurnsUnderALowCeilingIsWalkedWithoutAJump() {
        // Two stairs going up towards +z, side by side, with a ceiling over the cell north of
        // the second: with the head under it, the body can't turn onto the second stair from
        // the first one's low half. The line has to climb the first stair's back half before
        // it turns.
        World w = new World().floor(-2, -2, 4, 2).put(1, 0, 0, STAIR).put(0, 0, 0, STAIR)
                .put(0, 2, -1, CUBE);
        List<PathStep> path = List.of(
                new PathStep(new BlockPoint(1, 0, -1), null),
                new PathStep(new BlockPoint(1, 0, 0), MoveType.WALK),
                new PathStep(new BlockPoint(0, 1, 0), MoveType.WALK));
        ExecutionPlan plan = new PlanBuilder(w).build(path);
        assertTrue(plan.segments().stream().allMatch(g -> g.kind() == Kind.STRAIGHT),
                "no jump");
        assertTrue(plan.nodes().get(2).z() > 0.5, "on the second stair's back half");
        assertTrue(SimRun.run(w, plan, Executor.Settings.DEFAULT, 400).failures().isEmpty());
    }

    @Test
    void aStairsLowHalfWithNoRoomToStandIsStoodOnAtItsBackHalf() {
        World w = new World().floor(-2, -2, 4, 2).put(0, 0, 0, STAIR).put(0, 0, -1, CUBE)
                .put(0, 1, -1, CUBE).put(1, 0, 0, CUBE);
        List<PathStep> path = List.of(
                new PathStep(new BlockPoint(0, 0, 0), null),
                new PathStep(new BlockPoint(1, 1, 0), MoveType.WALK));
        ExecutionPlan plan = new PlanBuilder(w).build(path);
        // The low half is half a block deep against a wall: the body doesn't fit on it.
        assertEquals(1.0, plan.nodes().get(0).y());
    }

    @Test
    void aLadderHasNoFloorAndIsClimbedSlowly() {
        World w = new World().floor(0, -2, 10, 2);
        List<PathStep> path = List.of(
                new PathStep(new BlockPoint(1, 0, 0), null),
                new PathStep(new BlockPoint(1, 1, 0), MoveType.CLIMB),
                new PathStep(new BlockPoint(1, 2, 0), MoveType.CLIMB));
        ExecutionPlan plan = new PlanBuilder(w).build(path);
        assertFalse(plan.nodes().get(2).floor());
        assertEquals(2.0, plan.nodes().get(2).y());
        assertTrue(plan.nodes().get(0).speedLimit() <= 0.12);
        assertEquals(2.0, plan.length());
    }

    @Test
    void stepsMustBeNextToEachOther() {
        assertThrows(IllegalArgumentException.class, () -> new PlanBuilder(new World()).build(
                List.of(new PathStep(new BlockPoint(0, 0, 0), null),
                        new PathStep(new BlockPoint(2, 0, 0), MoveType.WALK))));
        assertTrue(new PlanBuilder(new World()).build(List.of()).nodes().isEmpty());
    }
}
