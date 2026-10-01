package astar.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import astar.core.BlockPoint;
import astar.core.CostModel;
import astar.core.DefaultCostModel;
import astar.core.Heuristic;
import astar.core.Heuristics;
import astar.core.Pos;
import astar.core.SearchResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** {@link DefaultCostModel#heightAwareHeuristic} on real moves: consistent, and never worse. */
class HeightHeuristicTest {
    private static final double EPS = 1e-9;

    private static DefaultCostModel randomCosts(Random rng) {
        return new DefaultCostModel(0.1 + 3 * rng.nextDouble(), 0.1 + 4 * rng.nextDouble(),
                0.1 + 3 * rng.nextDouble(), 0.1 + 3 * rng.nextDouble(), rng.nextDouble(),
                rng.nextDouble(), 0.1 + 3 * rng.nextDouble(), 0.1 + 3 * rng.nextDouble());
    }

    private static List<Long> standable(ArrayBlockView world, MoveValidator v) {
        List<Long> out = new ArrayList<>();
        for (int x = 0; x < world.sizeX(); x++) {
            for (int y = 0; y < world.sizeY(); y++) {
                for (int z = 0; z < world.sizeZ(); z++) {
                    if (v.canStand(x, y, z)) {
                        out.add(Pos.pack(x, y, z));
                    }
                }
            }
        }
        return out;
    }

    /**
     * Every move out of every cell, on random worlds with slabs, stairs, water, ladders and
     * drops, with random costs and goals at every height: h(a) <= cost(a, b) + h(b).
     */
    @Test
    void consistentOnEveryMoveOfRandomWorlds() {
        Random rng = new Random(11);
        for (int trial = 0; trial < 120; trial++) {
            int directions = new int[] {4, 8, 16}[trial % 3];
            DefaultCostModel base = trial % 4 == 0 ? DefaultCostModel.DEFAULT : randomCosts(rng);
            ArrayBlockView world = TestWorlds.random(rng, 3 + rng.nextInt(6), 3 + rng.nextInt(6));
            WorldPathfinder finder = new WorldPathfinder(world, EntityProfile.DEFAULT, directions,
                    base, TerrainCosts.DEFAULT, base.heightAwareHeuristic(directions));
            Heuristic h = finder.heuristic();
            CostModel costs = finder.costs();
            List<Long> cells = standable(world, finder.validator());
            if (cells.isEmpty()) {
                continue;
            }
            List<Long> goals = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                goals.add(cells.get(rng.nextInt(cells.size())));
            }
            for (long a : cells) {
                finder.moves().moves(a, (b, type) -> {
                    double c = costs.cost(a, b, type);
                    for (long g : goals) {
                        assertTrue(h.between(a, g) <= c + h.between(b, g) + EPS,
                                () -> base + " " + directions + "-way " + type + " "
                                        + Pos.toPoint(a) + " -> " + Pos.toPoint(b));
                    }
                });
            }
        }
    }

    /** The same cost as the flat heuristic, never more nodes, and at least as high everywhere. */
    @Test
    void findsTheSamePathCostsWithNoMoreSearching() {
        Random rng = new Random(12);
        int compared = 0;
        for (int trial = 0; trial < 400; trial++) {
            int directions = new int[] {4, 8, 16}[trial % 3];
            DefaultCostModel base = trial % 2 == 0 ? DefaultCostModel.DEFAULT : randomCosts(rng);
            ArrayBlockView world = TestWorlds.random(rng, 2 + rng.nextInt(10), 2 + rng.nextInt(10));
            WorldPathfinder flat = new WorldPathfinder(world, EntityProfile.DEFAULT, directions,
                    base, TerrainCosts.DEFAULT, base.heuristic(directions));
            WorldPathfinder tall = new WorldPathfinder(world, EntityProfile.DEFAULT, directions,
                    base, TerrainCosts.DEFAULT, base.heightAwareHeuristic(directions));
            BlockPoint start = TestWorlds.surface(world, flat.validator(), 0, 0);
            BlockPoint goal = TestWorlds.surface(world, flat.validator(), world.sizeX() - 1,
                    world.sizeZ() - 1);
            if (start == null || goal == null) {
                continue;
            }
            compared++;
            assertTrue(tall.heuristic().between(start, goal)
                    >= flat.heuristic().between(start, goal) - EPS);
            SearchResult a = flat.find(start, goal);
            SearchResult b = tall.find(start, goal);
            assertEquals(a.found(), b.found());
            if (a.found()) {
                assertEquals(a.cost(), b.cost(), 1e-7, () -> base.toString());
            }
        }
        assertTrue(compared > 250, "compared " + compared);
    }

    /** Straight up a shaft of ladders: height is all there is to go, and it counts. */
    @Test
    void countsHeightWhenTheGoalIsStraightAbove() {
        Heuristic h = DefaultCostModel.DEFAULT.heightAwareHeuristic(8);
        long a = Pos.pack(0, 0, 0);
        long up = Pos.pack(1, 20, 0);
        assertEquals(Heuristics.OCTILE_XZ.between(a, up), 1, EPS);
        // Default costs: 0.5 per block up (the in-place step on stairs) on half the flat part.
        assertEquals(0.5 * 1 + 0.5 * 20, h.between(a, up), EPS);
        assertEquals(0.5 * 1 + 0.5 * 20, h.between(up, a), EPS);
        // Far away on the level: exactly the flat estimate.
        long far = Pos.pack(30, 2, 10);
        assertEquals(Heuristics.OCTILE_XZ.between(a, far), h.between(a, far), EPS);
    }
}
