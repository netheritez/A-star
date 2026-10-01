package astar.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import astar.core.BlockPoint;
import astar.core.CostModel;
import astar.core.DefaultCostModel;
import astar.core.Heuristic;
import astar.core.Pos;
import astar.core.SearchResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** {@link Landmarks}: consistent on every move, same path costs, and fewer nodes. */
class LandmarksTest {
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

    private static WorldPathfinder finder(ArrayBlockView world, int directions,
            DefaultCostModel costs, Heuristic h, double turn) {
        // Exact turn costs: approximate ones can come out a little different with another
        // heuristic, as the search meets cells from other sides.
        return new WorldPathfinder(world, EntityProfile.DEFAULT, directions, costs,
                TerrainCosts.DEFAULT, h, turn, turn > 0);
    }

    /**
     * h(a) <= cost(a, b) + h(b) for every move that can still reach the goal, on random
     * worlds, costs and goals.
     */
    @Test
    void consistentOnEveryMoveOfRandomWorlds() {
        Random rng = new Random(21);
        int checked = 0;
        for (int trial = 0; trial < 150; trial++) {
            int directions = new int[] {4, 8, 16}[trial % 3];
            DefaultCostModel base = trial % 4 == 0 ? DefaultCostModel.DEFAULT : randomCosts(rng);
            ArrayBlockView world = TestWorlds.random(rng, 3 + rng.nextInt(7), 3 + rng.nextInt(7));
            WorldPathfinder plain = finder(world, directions, base, base.heuristic(directions), 0);
            List<Long> cells = standable(world, plain.validator());
            if (cells.size() < 2) {
                continue;
            }
            long seed = cells.get(rng.nextInt(cells.size()));
            Landmarks lm = Landmarks.build(plain, seed, 1 + rng.nextInt(4));
            Heuristic h = lm.heuristic(base.heuristic(directions));
            CostModel costs = plain.costs();
            List<Long> goals = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                goals.add(cells.get(rng.nextInt(cells.size())));
            }
            for (long g : goals) {
                // Only moves that can still lead to the goal: past a dead end, where no path
                // is left, a bound may drop (the search never needs it there).
                BlockPoint gp = Pos.toPoint(g);
                java.util.Set<Long> leads = new java.util.HashSet<>();
                for (long c : cells) {
                    if (plain.find(Pos.toPoint(c), gp).found()) {
                        leads.add(c);
                    }
                }
                for (long a : leads) {
                    plain.moves().moves(a, (b, type) -> {
                        if (!leads.contains(b)) {
                            return;
                        }
                        double c = costs.cost(a, b, type);
                        assertTrue(h.between(a, g) <= c + h.between(b, g) + EPS,
                                () -> base + " " + directions + "-way " + type + " "
                                        + Pos.toPoint(a) + " -> " + Pos.toPoint(b));
                    });
                }
                checked++;
            }
        }
        assertTrue(checked > 500, "checked " + checked);
    }

    /** The same path costs as the flat heuristic, turn costs too, and fewer nodes overall. */
    @Test
    void findsTheSamePathCostsWithFewerNodes() {
        Random rng = new Random(22);
        int compared = 0;
        long flatNodes = 0;
        long altNodes = 0;
        for (int trial = 0; trial < 600; trial++) {
            int directions = new int[] {4, 8, 16}[trial % 3];
            DefaultCostModel base = trial % 2 == 0 ? DefaultCostModel.DEFAULT : randomCosts(rng);
            double turn = trial % 5 == 0 ? 0.3 : 0;
            ArrayBlockView world = TestWorlds.random(rng, 4 + rng.nextInt(12), 4 + rng.nextInt(12));
            WorldPathfinder flat = finder(world, directions, base, base.heuristic(directions), turn);
            BlockPoint start = TestWorlds.surface(world, flat.validator(), 0, 0);
            BlockPoint goal = TestWorlds.surface(world, flat.validator(), world.sizeX() - 1,
                    world.sizeZ() - 1);
            if (start == null || goal == null) {
                continue;
            }
            Landmarks lm = Landmarks.build(flat, start.pack(), 4);
            WorldPathfinder alt = finder(world, directions, base,
                    lm.heuristic(base.heuristic(directions)), turn);
            SearchResult a = flat.find(start, goal);
            SearchResult b = alt.find(start, goal);
            assertEquals(a.found(), b.found());
            if (a.found()) {
                compared++;
                assertEquals(a.cost(), b.cost(), 1e-7, () -> base.toString());
                flatNodes += a.expanded();
                altNodes += b.expanded();
            }
        }
        assertTrue(compared > 80, "compared " + compared);
        assertTrue(altNodes < flatNodes, altNodes + " vs " + flatNodes);
    }

    /**
     * Rebuilt over the patched graph after edits: the same path costs as the flat heuristic,
     * turn costs too (exact ones with the turn tables).
     */
    @Test
    void rebuiltAfterEditsGiveTheSameCosts() {
        Random rng = new Random(23);
        int compared = 0;
        for (int trial = 0; trial < 400; trial++) {
            int directions = new int[] {4, 8, 16}[trial % 3];
            double turn = new double[] {0, 0.1, 0.3}[trial % 3 == 0 ? 0 : rng.nextInt(3)];
            boolean exact = turn > 0 && rng.nextBoolean();
            DefaultCostModel base = DefaultCostModel.DEFAULT;
            ArrayBlockView world = TestWorlds.random(rng, 12 + rng.nextInt(20), 12 + rng.nextInt(20));
            Heuristic flatH = base.heuristic(directions);
            WorldPathfinder flat = new WorldPathfinder(world, EntityProfile.DEFAULT, directions,
                    base, TerrainCosts.DEFAULT, flatH, turn, exact).useGraph(false);
            BlockPoint start = TestWorlds.surface(world, flat.validator(), 0, 0);
            BlockPoint goal = TestWorlds.surface(world, flat.validator(), world.sizeX() - 1,
                    world.sizeZ() - 1);
            if (start == null || goal == null) {
                continue;
            }
            WorldPathfinder f = new WorldPathfinder(world, EntityProfile.DEFAULT, directions,
                    base, TerrainCosts.DEFAULT, flatH, turn, exact);
            Landmarks lm = Landmarks.build(f, start.pack(), 3);
            int x = rng.nextInt(world.sizeX());
            int z = rng.nextInt(world.sizeZ());
            world.set(x, 1 + rng.nextInt(world.sizeY() - 1), z,
                    rng.nextBoolean() ? BlockType.SOLID : BlockType.AIR);
            if (!flat.canStand(start) || !flat.canStand(goal)) {
                continue;
            }
            NavGraph patched = lm.graph().patch();
            if (patched == null || !patched.covers(start.pack())) {
                continue;
            }
            Landmarks again = lm.rebuild(patched);
            assertTrue(!again.stale());
            assertEquals(exact, again.turns() != null);
            SearchResult a = flat.find(start, goal);
            SearchResult b = f.withHeuristic(again.heuristic(flatH)).find(start, goal);
            assertEquals(a.found(), b.found(), "trial " + trial);
            if (a.found()) {
                assertEquals(a.cost(), b.cost(), 1e-9, "trial " + trial);
                compared++;
            }
        }
        assertTrue(compared > 60, "compared " + compared);
    }

    /** After an edit the tables are stale, so it falls back to the flat heuristic. */
    @Test
    void fallsBackToTheFlatHeuristicAfterAnEdit() {
        ArrayBlockView world = new ArrayBlockView(12, 4, 3);
        world.fill(0, 0, 0, 11, 0, 2, BlockType.SOLID);
        WorldPathfinder finder = new WorldPathfinder(world, EntityProfile.DEFAULT, true);
        BlockPoint start = new BlockPoint(0, 1, 1);
        BlockPoint goal = new BlockPoint(11, 1, 1);
        Landmarks lm = Landmarks.build(finder, start.pack(), 2);
        assertTrue(lm.cells() > 30);
        Heuristic flat = DefaultCostModel.DEFAULT.heuristic(8);
        Heuristic h = lm.heuristic(flat);
        // A wall across the middle with a gap at one side: the landmarks knew the old way.
        world.fill(6, 1, 0, 6, 2, 1, BlockType.SOLID);
        assertEquals(flat.between(start, goal), h.between(start, goal), EPS);
    }
}
