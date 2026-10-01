package astar.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import astar.core.BlockPoint;
import astar.core.DefaultCostModel;
import astar.core.Heuristic;
import astar.core.PathStep;
import astar.core.Pos;
import astar.core.SearchResult;
import astar.core.TurnPenalty;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** With exact turns, A* returns the cheapest path counting turns, whatever the heuristic. */
class ExactTurnCostTest {

    /** Dijkstra over (cell, step into it): the true cheapest cost counting turns. */
    private static double dijkstra(WorldPathfinder f, long start, long goal, double perTurn) {
        TurnPenalty turns = (TurnPenalty) f.costs();
        record State(long pos, int dx, int dz) {}
        record Item(double d, State s) {}
        Map<State, Double> dist = new HashMap<>();
        PriorityQueue<Item> heap = new PriorityQueue<>((a, b) -> Double.compare(a.d(), b.d()));
        State s0 = new State(start, 0, 0);
        dist.put(s0, 0.0);
        heap.add(new Item(0, s0));
        while (!heap.isEmpty()) {
            Item it = heap.poll();
            State s = it.s();
            if (it.d() > dist.get(s)) {
                continue;
            }
            if (s.pos() == goal) {
                return it.d();
            }
            long before = Pos.offset(s.pos(), -s.dx(), 0, -s.dz());
            f.moves().moves(s.pos(), (to, type) -> {
                double c = turns.base().cost(s.pos(), to, type)
                        + perTurn * TurnPenalty.turn(before, s.pos(), to);
                State n = new State(to, Pos.x(to) - Pos.x(s.pos()), Pos.z(to) - Pos.z(s.pos()));
                double nd = it.d() + c;
                if (nd < dist.getOrDefault(n, Double.POSITIVE_INFINITY)) {
                    dist.put(n, nd);
                    heap.add(new Item(nd, n));
                }
            });
        }
        return Double.POSITIVE_INFINITY;
    }

    /** What a path costs, turns included, step by step. */
    private static double price(WorldPathfinder f, List<PathStep> path) {
        TurnPenalty turns = (TurnPenalty) f.costs();
        double total = 0;
        for (int i = 1; i < path.size(); i++) {
            long from = path.get(i - 1).pos().pack();
            long to = path.get(i).pos().pack();
            total += i == 1 ? turns.cost(from, to, path.get(i).via())
                    : turns.cost(path.get(i - 2).pos().pack(), from, to, path.get(i).via());
        }
        return total;
    }

    @Test
    void cheapestCountingTurnsOnRandomWorlds() {
        Random rng = new Random(31);
        int compared = 0;
        long withTurns = 0;
        long withoutTurns = 0;
        for (int trial = 0; trial < 500; trial++) {
            int directions = new int[] {4, 8, 16}[trial % 3];
            double perTurn = new double[] {0.1, 0.5, 2}[trial % 3 == 0 ? 0 : rng.nextInt(3)];
            DefaultCostModel costs = DefaultCostModel.DEFAULT;
            ArrayBlockView world = TestWorlds.random(rng, 3 + rng.nextInt(10), 3 + rng.nextInt(10));
            Heuristic flat = costs.heuristic(directions);
            WorldPathfinder f = new WorldPathfinder(world, EntityProfile.DEFAULT, directions,
                    costs, TerrainCosts.DEFAULT, flat, perTurn, true);
            BlockPoint start = TestWorlds.surface(world, f.validator(), 0, 0);
            BlockPoint goal = TestWorlds.surface(world, f.validator(), world.sizeX() - 1,
                    world.sizeZ() - 1);
            if (start == null || goal == null) {
                continue;
            }
            double best = dijkstra(f, start.pack(), goal.pack(), perTurn);
            SearchResult r = f.find(start, goal);
            assertEquals(Double.isFinite(best), r.found(), "trial " + trial);
            if (!r.found()) {
                continue;
            }
            compared++;
            assertEquals(best, r.cost(), 1e-9, "trial " + trial);
            assertEquals(r.cost(), price(f, r.path()), 1e-9, "the path costs what it says");

            // Landmarks, with tables that count turns: the same answer, in fewer nodes.
            Landmarks lm = Landmarks.build(f, start.pack(), 4);
            assertNotNull(lm.turns(), "exact turn costs get turn tables");
            WorldPathfinder alt = f.withHeuristic(lm.heuristic(flat));
            SearchResult a = alt.find(start, goal);
            assertEquals(best, a.cost(), 1e-9, "landmarks, trial " + trial);
            // Against landmarks that leave turns out.
            Landmarks plain = Landmarks.build(new WorldPathfinder(world, EntityProfile.DEFAULT,
                    directions, costs, TerrainCosts.DEFAULT, flat, perTurn, false),
                    start.pack(), 4);
            assertNull(plain.turns());
            SearchResult b = f.withHeuristic(plain.heuristic(flat)).find(start, goal);
            assertEquals(best, b.cost(), 1e-9, "cell landmarks, trial " + trial);
            withTurns += a.expanded();
            withoutTurns += b.expanded();
        }
        assertTrue(compared > 80, "compared " + compared);
        assertTrue(withTurns < withoutTurns, withTurns + " vs " + withoutTurns);
    }

    /**
     * The bounds that count turns are consistent over search nodes: for every node (cell and
     * heading into it) and every move out of it, h(node) <= move cost + turn + h(next node),
     * wherever the next node can still reach the goal.
     */
    @Test
    void turnBoundsAreConsistentOverHeadings() {
        Random rng = new Random(32);
        int checked = 0;
        for (int trial = 0; trial < 120; trial++) {
            int directions = new int[] {4, 8, 16}[trial % 3];
            double perTurn = new double[] {0.1, 0.5, 2}[rng.nextInt(3)];
            DefaultCostModel costs = DefaultCostModel.DEFAULT;
            ArrayBlockView world = TestWorlds.random(rng, 3 + rng.nextInt(8), 3 + rng.nextInt(8));
            Heuristic flat = costs.heuristic(directions);
            WorldPathfinder f = new WorldPathfinder(world, EntityProfile.DEFAULT, directions,
                    costs, TerrainCosts.DEFAULT, flat, perTurn, true);
            BlockPoint seed = TestWorlds.surface(world, f.validator(), 0, 0);
            if (seed == null) {
                continue;
            }
            Landmarks lm = Landmarks.build(f, seed.pack(), 1 + rng.nextInt(4));
            Heuristic h = lm.heuristic(flat);
            TurnPenalty turns = (TurnPenalty) f.costs();
            NavGraph g = lm.graph();
            long[] pos = g.positions();
            long goal = pos[rng.nextInt(pos.length)];
            h.prepare(seed.pack(), goal);
            Map<Long, Boolean> leads = new HashMap<>();
            for (int a = 0; a < pos.length; a++) {
                long from = pos[a];
                // Every heading the cell is entered by.
                java.util.Set<Integer> heads = new java.util.HashSet<>();
                for (int v = 0; v < pos.length; v++) {
                    for (int e = g.moveStart()[v]; e < g.moveStart()[v + 1]; e++) {
                        if (g.moveTo()[e] == a) {
                            heads.add(TurnPenalty.heading(Pos.x(from) - Pos.x(pos[v]),
                                    Pos.z(from) - Pos.z(pos[v])));
                        }
                    }
                }
                for (int head : heads) {
                    double ha = h.between(from, head, goal);
                    long before = Pos.offset(from, -TurnPenalty.headingX(head), 0,
                            -TurnPenalty.headingZ(head));
                    for (int e = g.moveStart()[a]; e < g.moveStart()[a + 1]; e++) {
                        long to = pos[g.moveTo()[e]];
                        if (!leads.computeIfAbsent(to,
                                t -> Double.isFinite(dijkstra(f, t, goal, perTurn)))) {
                            continue;
                        }
                        double c = head == 0 ? g.moveCost()[e]
                                : g.moveCost()[e] + turns.turnCost(before, from, to, g.type(e));
                        int next = TurnPenalty.heading(Pos.x(to) - Pos.x(from),
                                Pos.z(to) - Pos.z(from));
                        assertTrue(ha <= c + h.between(to, next, goal) + 1e-9,
                                "trial " + trial + " " + Pos.toString(from) + " -> "
                                        + Pos.toString(to));
                        checked++;
                    }
                }
            }
        }
        assertTrue(checked > 2000, "checked " + checked);
    }
}
