package astar.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

/** {@link DefaultCostModel#heuristic(int)} stays exact for any positive step costs. */
class ScaledHeuristicTest {
    private static final double EPS = 1e-9;

    @Test
    void defaultCostsGiveTheStandardHeuristics() {
        DefaultCostModel d = DefaultCostModel.DEFAULT;
        assertSame(Heuristics.MANHATTAN_XZ, d.heuristic(4));
        assertSame(Heuristics.OCTILE_XZ, d.heuristic(8));
        assertSame(Heuristics.SIXTEEN_XZ, d.heuristic(16));
        assertSame(Heuristics.OCTILE_XZ, Heuristics.octile(1, Math.sqrt(2)));
    }

    @Test
    void octileForCheapDiagonalsIsChebyshev() {
        // Diagonals as cheap as straight steps: 5 along and 2 across is 5 moves.
        assertEquals(5, Heuristics.octile(1, 1).estimate(5, 0, 2), EPS);
        // Diagonals dearer than two straight steps are never worth it: Manhattan.
        assertEquals(7, Heuristics.octile(1, 3).estimate(5, 0, 2), EPS);
        // Cheaper than straight: zigzagging diagonals cover the long side too.
        assertEquals(5 * 0.5, Heuristics.octile(1, 0.5).estimate(5, 0, 2), EPS);
    }

    /** One move of each shape: {dx, dy, dz, type}. */
    private static final int[][] MOVES = {
        {1, 0, 0, MoveType.WALK.ordinal()}, {1, 0, 1, MoveType.DIAGONAL.ordinal()},
        {2, 0, 1, MoveType.DIAGONAL.ordinal()}, {1, 1, 0, MoveType.JUMP_UP.ordinal()},
        {1, -3, 0, MoveType.DROP.ordinal()}, {1, 0, 0, MoveType.SWIM.ordinal()},
        {1, 0, 1, MoveType.SWIM.ordinal()}, {0, 1, 0, MoveType.SWIM.ordinal()},
        {1, 0, 1, MoveType.CLIMB.ordinal()}, {0, 1, 0, MoveType.CLIMB.ordinal()},
        {1, 0, 0, MoveType.CLIMB.ordinal()}, {0, 0, 0, MoveType.WALK.ordinal()},
        {1, 1, 1, MoveType.JUMP_UP.ordinal()}, {1, -2, 1, MoveType.DROP.ordinal()},
    };

    /**
     * Consistency, checked directly: for random costs, every move shape in every direction,
     * and goals all around, h(a) never exceeds the move's cost plus h(b).
     */
    @Test
    void consistentForRandomCosts() {
        Random rng = new Random(7);
        for (int trial = 0; trial < 300; trial++) {
            DefaultCostModel costs = new DefaultCostModel(0.1 + 3 * rng.nextDouble(),
                    0.1 + 4 * rng.nextDouble(), 0.1 + 3 * rng.nextDouble(),
                    0.1 + 3 * rng.nextDouble(), rng.nextDouble(), rng.nextDouble(),
                    0.1 + 3 * rng.nextDouble(), 0.1 + 3 * rng.nextDouble());
            for (int directions : new int[] {4, 8, 16}) {
                Heuristic h = costs.heuristic(directions);
                for (int[] m : MOVES) {
                    MoveType type = MoveType.values()[m[3]];
                    if (directions == 4 && m[0] != 0 && m[2] != 0) {
                        continue; // no diagonal moves at all in 4 directions
                    }
                    if (directions == 8 && Math.abs(m[0]) + Math.abs(m[2]) == 3) {
                        continue;
                    }
                    for (int flip = 0; flip < 8; flip++) {
                        int dx = (flip & 1) == 0 ? m[0] : -m[0];
                        int dz = (flip & 2) == 0 ? m[2] : -m[2];
                        if ((flip & 4) != 0) {
                            int t = dx;
                            dx = dz;
                            dz = t;
                        }
                        long a = Pos.pack(0, 10, 0);
                        long b = Pos.pack(dx, 10 + m[1], dz);
                        double cost = costs.cost(a, b, type);
                        for (int gx = -6; gx <= 6; gx++) {
                            for (int gz = -6; gz <= 6; gz++) {
                                long goal = Pos.pack(gx, 10, gz);
                                assertTrue(h.between(a, goal) <= cost + h.between(b, goal) + EPS,
                                        () -> costs + " " + directions + "-way " + type);
                            }
                        }
                    }
                }
            }
        }
    }

    /** With any step costs, A* with the scaled heuristic finds paths as cheap as Dijkstra's. */
    @Test
    void findsTheCheapestPathForAnyStepCosts() {
        Random rng = new Random(3);
        for (int trial = 0; trial < 400; trial++) {
            boolean diagonal = rng.nextBoolean();
            TestGrid grid = TestGrid.random(rng, diagonal);
            DefaultCostModel costs = DefaultCostModel.DEFAULT.withSteps(
                    0.1 + 3 * rng.nextDouble(), 0.1 + 4 * rng.nextDouble());
            BlockPoint start = new BlockPoint(0, 0, 0);
            SearchResult astar = AStar.findPath(start, grid.corner(), grid, costs,
                    costs.heuristic(diagonal ? 8 : 4));
            SearchResult dijkstra = AStar.findPath(start, grid.corner(), grid, costs,
                    Heuristics.ZERO);
            assertEquals(dijkstra.found(), astar.found());
            if (astar.found()) {
                assertEquals(dijkstra.cost(), astar.cost(), 1e-7, costs::toString);
            }
        }
    }
}
