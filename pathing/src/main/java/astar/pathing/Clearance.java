package astar.pathing;

import java.util.concurrent.ConcurrentHashMap;

/**
 * How much room there is around a cell to stand in: how far it is, in steps, from a wall or a
 * drop. A player walks down the middle of a corridor and keeps a step back from ledges, so
 * {@link TerrainCostModel} charges a little more for steps close to them, and {@link
 * PathSmoother} keeps its straight lines from cutting back in.
 *
 * <ul>
 *   <li>0: at the edge. One of the eight cells around it has nowhere to stand within a block of
 *       its height: a wall two or more blocks high, or a drop of two or more.
 *   <li>1: next to such a cell (one of its eight neighbours, at the height a step would take).
 *   <li>2 ({@link #MAX}): further in.
 * </ul>
 *
 * <p>A step of one block up or down doesn't count as an edge: stairs, slabs and terraces are
 * ordinary ground.
 *
 * <p>Worked out when first asked and kept, until the world changes ({@link
 * ArrayBlockView#version()}). Safe to ask from several threads.
 */
public final class Clearance {
    /** The most room it measures: cells further in than this all count as {@link #MAX}. */
    public static final int MAX = 2;

    private static final int[] DX = {1, -1, 0, 0, 1, 1, -1, -1};
    private static final int[] DZ = {0, 0, 1, -1, 1, -1, 1, -1};

    private final MoveValidator validator;
    private volatile Cache cache = new Cache(-1);

    private record Cache(int version, ConcurrentHashMap<Long, Byte> map) {
        Cache(int version) {
            this(version, new ConcurrentHashMap<>());
        }
    }

    public Clearance(MoveValidator validator) {
        this.validator = validator;
    }

    /** Room around a cell someone stands in, from 0 (at an edge) to {@link #MAX}. */
    public int at(int x, int y, int z) {
        BlockView world = validator.world();
        int version = world instanceof ArrayBlockView a ? a.version() : 0;
        Cache c = cache;
        if (c.version() != version) {
            c = new Cache(version);
            cache = c;
        }
        long key = astar.core.Pos.pack(x, y, z);
        Byte known = c.map().get(key);
        if (known != null) {
            return known;
        }
        int room = measure(x, y, z);
        c.map().put(key, (byte) room);
        return room;
    }

    public int at(long packed) {
        return at(astar.core.Pos.x(packed), astar.core.Pos.y(packed), astar.core.Pos.z(packed));
    }

    private int measure(int x, int y, int z) {
        if (edge(x, y, z)) {
            return 0;
        }
        for (int d = 0; d < 8; d++) {
            int nx = x + DX[d];
            int nz = z + DZ[d];
            int ny = neighbour(nx, y, nz);
            if (ny != Integer.MIN_VALUE && edge(nx, ny, nz)) {
                return 1;
            }
        }
        return MAX;
    }

    /** Whether one of the cells around has nowhere to stand within a block of this height. */
    private boolean edge(int x, int y, int z) {
        for (int d = 0; d < 8; d++) {
            if (neighbour(x + DX[d], y, z + DZ[d]) == Integer.MIN_VALUE) {
                return true;
            }
        }
        return false;
    }

    /** Where to stand in column (x, z) within a block of y: level first, or MIN_VALUE. */
    private int neighbour(int x, int y, int z) {
        if (validator.canStand(x, y, z)) {
            return y;
        }
        if (validator.canStand(x, y + 1, z)) {
            return y + 1;
        }
        if (validator.canStand(x, y - 1, z)) {
            return y - 1;
        }
        return Integer.MIN_VALUE;
    }
}
