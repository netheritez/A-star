package astar.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import astar.core.MoveSource;
import astar.core.MoveType;
import astar.core.Pos;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * {@link MoveCache} must give exactly the moves of its source: on a first look, when it answers
 * from memory, after the world changes, and with several threads at once.
 */
class MoveCacheTest {
    private static final int[] DIRECTIONS = {4, 8, 16};

    @Test
    void sameMovesAsTheSourceBeforeAndAfterChanges() {
        Random rng = new Random(91);
        EntityProfile[] profiles = {
            EntityProfile.PLAYER, EntityProfile.PLAYER.withMaxDrop(8),
            EntityProfile.PLAYER.withHeight(0.9), EntityProfile.ZOMBIE,
        };
        BlockType[] types = BlockType.values();
        int moves = 0;
        for (int trial = 0; trial < 200; trial++) {
            ArrayBlockView world = TestWorlds.random(rng, 2 + rng.nextInt(20), 2 + rng.nextInt(20));
            MoveSource direct = new BlockMoveSource(
                    new MoveValidator(world, profiles[trial % profiles.length]),
                    DIRECTIONS[trial % DIRECTIONS.length]);
            MoveCache cached = new MoveCache(direct, world);
            for (int round = 0; round < 3; round++) {
                for (int look = 0; look < 2; look++) {
                    for (int x = -1; x <= world.sizeX(); x++) {
                        for (int z = -1; z <= world.sizeZ(); z++) {
                            for (int y = -1; y <= world.sizeY(); y++) {
                                long p = Pos.pack(x, y, z);
                                TreeMap<Long, MoveType> a = moves(cached, p);
                                assertEquals(moves(direct, p), a, "moves from " + Pos.toString(p)
                                        + ", trial " + trial + ", round " + round + ", look " + look);
                                moves += a.size();
                            }
                        }
                    }
                }
                // Change a few blocks: the cache must start again.
                for (int k = 0; k < 4; k++) {
                    world.set(rng.nextInt(world.sizeX()), rng.nextInt(world.sizeY()),
                            rng.nextInt(world.sizeZ()), types[rng.nextInt(types.length)]);
                }
            }
        }
        assertTrue(moves > 40_000, "only " + moves + " moves compared");
    }

    @Test
    void threadsShareIt() {
        ArrayBlockView world = TestWorlds.random(new Random(5), 48, 48);
        MoveSource direct = new BlockMoveSource(new MoveValidator(world, EntityProfile.PLAYER), true);
        MoveCache cached = new MoveCache(direct, world);
        List<Long> cells = IntStream.range(0, world.sizeX() * world.sizeZ() * world.sizeY())
                .mapToObj(i -> Pos.pack(i % world.sizeX(), i / (world.sizeX() * world.sizeZ()),
                        i / world.sizeX() % world.sizeZ()))
                .toList();
        for (int pass = 0; pass < 3; pass++) {
            long wrong = cells.parallelStream()
                    .filter(p -> !moves(direct, p).equals(moves(cached, p)))
                    .count();
            assertEquals(0, wrong, "pass " + pass);
        }
        assertTrue(cached.cells() > 0 && cached.bytes() > 0);
        cached.clear();
        assertEquals(0, cached.cells());
    }

    private static TreeMap<Long, MoveType> moves(MoveSource source, long from) {
        TreeMap<Long, MoveType> out = new TreeMap<>();
        source.moves(from, out::put);
        return out;
    }
}
