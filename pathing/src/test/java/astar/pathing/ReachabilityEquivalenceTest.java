package astar.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import astar.core.BlockPoint;
import astar.core.CostModel;
import astar.core.DefaultCostModel;
import astar.core.Pos;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link Reachability} keeps the world's moves in flat arrays; it must pick exactly what the
 * straightforward version (maps of positions, moves worked out from every seed) picks: the same
 * route, the same cost to the last bit, and the same main area.
 */
class ReachabilityEquivalenceTest {

    @Test
    void sameRoutesAndAreasAsTheReference() {
        Random rng = new Random(57);
        EntityProfile[] profiles = {
            EntityProfile.PLAYER, EntityProfile.PLAYER.withMaxDrop(8), EntityProfile.ZOMBIE,
        };
        int routes = 0;
        for (int trial = 0; trial < 150; trial++) {
            ArrayBlockView world = trial % 10 == 0
                    ? new ArrayBlockView(1 + rng.nextInt(4), 4, 1 + rng.nextInt(4)) // often empty
                    : TestWorlds.random(rng, 2 + rng.nextInt(30), 2 + rng.nextInt(30));
            EntityProfile profile = profiles[trial % profiles.length];
            boolean diagonal = trial % 2 == 0;
            Optional<Reachability.Route> expected = referenceRoute(world, profile, diagonal);
            assertEquals(expected, Reachability.longestRoute(world, profile, diagonal),
                    "trial " + trial);
            assertEquals(referenceArea(world, profile, diagonal),
                    Reachability.mainArea(world, profile, diagonal), "trial " + trial);
            routes += expected.isPresent() ? 1 : 0;
        }
        assertTrue(routes > 100, "only " + routes + " routes compared");
    }

    @Test
    void sameOnCrossesWhereCostsTie() {
        // A cross of floor: from the first cell (the west arm's end) the north and south ends
        // cost the same, and so do the arms from either of them, so the picks come down to the
        // tie-breaks.
        for (int arm = 1; arm <= 6; arm++) {
            int size = 2 * arm + 1;
            ArrayBlockView world = new ArrayBlockView(size, 4, size);
            world.fill(0, 0, arm, size - 1, 0, arm, BlockType.SOLID);
            world.fill(arm, 0, 0, arm, 0, size - 1, BlockType.SOLID);
            for (boolean diagonal : new boolean[] {true, false}) {
                assertEquals(referenceRoute(world, EntityProfile.DEFAULT, diagonal),
                        Reachability.longestRoute(world, EntityProfile.DEFAULT, diagonal),
                        "arms of " + arm + (diagonal ? " with diagonals" : ""));
            }
        }
    }

    // ---- The straightforward version ---------------------------------------------------------

    private record Area(BlockPoint seed, Map<Long, Double> reach, Set<Long> roundTrip) {}

    private static Optional<Reachability.Route> referenceRoute(ArrayBlockView world,
            EntityProfile profile, boolean diagonal) {
        MoveValidator v = new MoveValidator(world, profile);
        Area area = referenceMainArea(world, v, diagonal);
        if (area == null) {
            return Optional.empty();
        }
        CostModel costs = DefaultCostModel.DEFAULT;
        BlockPoint b = farthest(area.reach(), area.seed(), area.roundTrip());
        Map<Long, Double> fromB = Reachability.costsFrom(v, diagonal, costs, b);
        BlockPoint c = farthest(fromB, b, area.roundTrip());
        return Optional.of(new Reachability.Route(b, c, fromB.get(c.pack())));
    }

    private static List<BlockPoint> referenceArea(ArrayBlockView world, EntityProfile profile,
            boolean diagonal) {
        Area area = referenceMainArea(world, new MoveValidator(world, profile), diagonal);
        return area == null ? List.of()
                : area.roundTrip().stream().sorted().map(Pos::toPoint).toList();
    }

    private static Area referenceMainArea(ArrayBlockView world, MoveValidator v,
            boolean diagonal) {
        List<BlockPoint> standable = new ArrayList<>();
        for (int x = 0; x < world.sizeX(); x++) {
            for (int z = 0; z < world.sizeZ(); z++) {
                for (int y = 1; y < world.sizeY(); y++) {
                    if (v.canStand(x, y, z)) {
                        standable.add(new BlockPoint(x, y, z));
                    }
                }
            }
        }
        if (standable.isEmpty()) {
            return null;
        }
        Area best = null;
        Set<Long> measured = new HashSet<>();
        int seeds = Math.min(Reachability.SEEDS, standable.size());
        for (int i = 0; i < seeds; i++) {
            BlockPoint seed = standable.get((int) ((long) i * standable.size() / seeds));
            if (measured.contains(seed.pack())) {
                continue;
            }
            Map<Long, Double> reach =
                    Reachability.costsFrom(v, diagonal, DefaultCostModel.DEFAULT, seed);
            Set<Long> roundTrip = canReturn(v, diagonal, reach.keySet(), seed.pack());
            measured.addAll(roundTrip);
            if (best == null || roundTrip.size() > best.roundTrip().size()) {
                best = new Area(seed, reach, roundTrip);
            }
        }
        return best;
    }

    private static Set<Long> canReturn(MoveValidator v, boolean diagonal, Set<Long> reached,
            long target) {
        BlockMoveSource moves = new BlockMoveSource(v, diagonal);
        Map<Long, List<Long>> incoming = new HashMap<>();
        for (long from : reached) {
            moves.moves(from, (to, type) ->
                    incoming.computeIfAbsent(to, k -> new ArrayList<>()).add(from));
        }
        Set<Long> back = new HashSet<>();
        ArrayDeque<Long> queue = new ArrayDeque<>();
        back.add(target);
        queue.add(target);
        while (!queue.isEmpty()) {
            for (long prev : incoming.getOrDefault(queue.poll(), List.of())) {
                if (back.add(prev)) {
                    queue.add(prev);
                }
            }
        }
        back.retainAll(reached);
        return back;
    }

    private static BlockPoint farthest(Map<Long, Double> costs, BlockPoint fallback,
            Set<Long> allowed) {
        long bestPos = fallback.pack();
        double bestCost = 0;
        for (Map.Entry<Long, Double> e : costs.entrySet()) {
            if (!allowed.contains(e.getKey())) {
                continue;
            }
            if (e.getValue() > bestCost || (e.getValue() == bestCost && e.getKey() < bestPos)) {
                bestCost = e.getValue();
                bestPos = e.getKey();
            }
        }
        return Pos.toPoint(bestPos);
    }
}
