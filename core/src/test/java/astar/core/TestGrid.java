package astar.core;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

/** A flat test world at y = 0 for core tests, which can't depend on the pathing module. */
final class TestGrid implements MoveSource {
    private static final int[][] CARDINAL = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] DIAGONAL = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    final int width;
    final int depth;
    final Set<Long> walls;
    final boolean diagonal;

    TestGrid(int width, int depth, Set<Long> walls, boolean diagonal) {
        this.width = width;
        this.depth = depth;
        this.walls = walls;
        this.diagonal = diagonal;
    }

    static TestGrid of(boolean diagonal, String... rows) {
        Set<Long> walls = new HashSet<>();
        for (int z = 0; z < rows.length; z++) {
            for (int x = 0; x < rows[z].length(); x++) {
                if (rows[z].charAt(x) == '#') {
                    walls.add(Pos.pack(x, 0, z));
                }
            }
        }
        return new TestGrid(rows[0].length(), rows.length, walls, diagonal);
    }

    static TestGrid random(Random rng, boolean diagonal) {
        int w = 2 + rng.nextInt(14);
        int d = 2 + rng.nextInt(14);
        Set<Long> walls = new HashSet<>();
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                if (rng.nextDouble() < 0.3) {
                    walls.add(Pos.pack(x, 0, z));
                }
            }
        }
        walls.remove(Pos.pack(0, 0, 0));
        walls.remove(Pos.pack(w - 1, 0, d - 1));
        return new TestGrid(w, d, walls, diagonal);
    }

    BlockPoint corner() {
        return new BlockPoint(width - 1, 0, depth - 1);
    }

    boolean open(int x, int z) {
        return x >= 0 && x < width && z >= 0 && z < depth && !walls.contains(Pos.pack(x, 0, z));
    }

    @Override
    public void moves(long from, MoveSink sink) {
        int x = Pos.x(from);
        int z = Pos.z(from);
        for (int[] d : CARDINAL) {
            if (open(x + d[0], z + d[1])) {
                sink.accept(Pos.pack(x + d[0], 0, z + d[1]), MoveType.WALK);
            }
        }
        if (diagonal) {
            for (int[] d : DIAGONAL) {
                if (open(x + d[0], z + d[1]) && open(x + d[0], z) && open(x, z + d[1])) {
                    sink.accept(Pos.pack(x + d[0], 0, z + d[1]), MoveType.DIAGONAL);
                }
            }
        }
    }
}
