package astar.movement.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import astar.movement.Keys;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SimulatedPlayerTest {

    private static final Keys W = Keys.parse("W");
    private static final Keys SPRINT = Keys.parse("WR");
    private static final Keys JUMP = Keys.parse("WJ");
    private static final Keys SNEAK = Keys.parse("WN");
    /** Yaw -90 faces +x. */
    private static final float EAST = -90;

    /** A floor at y -1 everywhere, plus whatever blocks a test adds. */
    private static final class World implements SimWorld {
        final Map<String, SimBlock> blocks = new HashMap<>();

        World put(int x, int y, int z, SimBlock b) {
            blocks.put(x + "," + y + "," + z, b);
            return this;
        }

        World cube(int x, int y, int z) {
            return put(x, y, z, SimBlock.solid(new Aabb(0, 0, 0, 1, 1, 1)));
        }

        @Override
        public SimBlock block(int x, int y, int z) {
            SimBlock b = blocks.get(x + "," + y + "," + z);
            if (b != null) {
                return b;
            }
            return y == -1 ? SimBlock.solid(new Aabb(0, 0, 0, 1, 1, 1)) : SimBlock.AIR;
        }
    }

    private static SimulatedPlayer player(SimWorld world, double x, double y) {
        return new SimulatedPlayer(world, SimulatedPlayer.Attributes.PLAYER, x, y, 0.5, EAST,
                true);
    }

    private static double run(SimulatedPlayer p, Keys keys, int ticks) {
        double before = p.x();
        for (int i = 0; i < ticks; i++) {
            before = p.x();
            p.tick(keys, EAST, 0);
        }
        return p.x() - before;
    }

    @Test
    void walkingAndSprintingReachTheGamesTopSpeeds() {
        assertEquals(4.317 / 20, run(player(new World(), 0.5, 0), W, 60), 0.0005);
        assertEquals(5.612 / 20, run(player(new World(), 0.5, 0), SPRINT, 60), 0.0005);
        assertEquals(1.295 / 20, run(player(new World(), 0.5, 0), SNEAK, 60), 0.0005);
    }

    @Test
    void stepsOntoASlabButNotAFullBlock() {
        World slab = new World();
        for (int x = 3; x < 20; x++) {
            slab.put(x, 0, 0, new SimBlock(List.of(new Aabb(0, 0, 0, 1, 0.5, 1)),
                    List.of(0.0, 0.5, 1.0), 0.6F, 1, 1, "", false, false));
        }
        SimulatedPlayer p = player(slab, 0.5, 0);
        run(p, W, 40);
        assertEquals(0.5, p.y(), 1e-9);
        assertTrue(p.x() > 3.5);

        SimulatedPlayer q = player(new World().cube(3, 0, 0), 0.5, 0);
        run(q, W, 40);
        assertEquals(0.0, q.y(), 1e-9);
        assertEquals(2.7, q.x(), 1e-6, "stops with its side against the block");
        assertTrue(q.horizontalCollision());
    }

    @Test
    void aJumpRisesAboutOnePointTwoFiveBlocks() {
        SimulatedPlayer p = player(new World(), 0.5, 0);
        double top = 0;
        p.tick(JUMP, EAST, 0);
        for (int i = 0; i < 20; i++) {
            top = Math.max(top, p.y());
            p.tick(Keys.NONE, EAST, 0);
        }
        assertEquals(1.2522, top, 0.0001);
        assertTrue(p.onGround());
        assertEquals(0.0, p.y(), 1e-9);
    }

    @Test
    void sneakingStopsAtAnEdge() {
        // A platform one block up, ending at x = 5.
        World w = new World();
        for (int x = -2; x < 5; x++) {
            w.cube(x, 0, 0);
        }
        SimulatedPlayer p = player(w, 0.5, 1);
        run(p, SNEAK, 100);
        assertEquals(1.0, p.y(), 1e-9);
        assertTrue(p.x() > 5.0 && p.x() < 5.3, "hangs over the edge but not off it: " + p.x());

        SimulatedPlayer q = player(w, 0.5, 1);
        run(q, W, 100);
        assertEquals(0.0, q.y(), 1e-9, "walks off without sneaking");
    }

    @Test
    void reportsWaterAsUnsupported() {
        World w = new World().put(0, 0, 0, new SimBlock(List.of(),
                List.of(), 0.6F, 1, 1, "", true, false));
        SimulatedPlayer p = player(w, 0.5, 0);
        p.tick(Keys.NONE, EAST, 0);
        assertEquals("in water", p.unsupported());
    }

    @Test
    void sineTableMatchesTheRealSine() {
        for (float a = -7; a < 7; a += 0.01F) {
            assertEquals(Math.sin(a), GameMath.sin(a), 1e-4);
            assertEquals(Math.cos(a), GameMath.cos(a), 1e-4);
        }
    }
}
