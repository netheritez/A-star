package astar.core;

/**
 * Every move of a map worked out ahead of time: the cells as dense ids, and each cell's moves
 * with their costs already priced. {@link AStarSearch} searches it with plain arrays, with no
 * hashing and no move rules or pricing per node.
 *
 * <p>The moves of cell {@code c} are {@code moveStart()[c]} up to {@code moveStart()[c + 1]};
 * move {@code e} goes to cell {@code moveTo()[e]} as a {@code moveType()[e]} (a
 * {@link MoveType} ordinal) and costs {@code moveCost()[e]}, without any turn cost. The arrays
 * are the graph's own, handed out for speed: never change them.
 *
 * <p>The graph must be closed: every move ends in a cell of the graph.
 */
public interface MoveGraph {
    /** The cell's id, or -1 if the position isn't in the graph. */
    int cell(long pos);

    /** Each cell's position, packed, by id. */
    long[] positions();

    int[] moveStart();

    int[] moveTo();

    double[] moveCost();

    byte[] moveType();

    /**
     * Each move's horizontal heading code ({@link TurnPenalty#heading}), by move, so searches
     * can price turns from a table; null if a move steps more than two blocks across. The
     * default works it out on every call: graphs should keep it.
     */
    default byte[] moveHeading() {
        return headings(this);
    }

    /** Works out {@link #moveHeading()} for a graph. */
    static byte[] headings(MoveGraph g) {
        long[] positions = g.positions();
        int[] start = g.moveStart();
        int[] to = g.moveTo();
        byte[] heading = new byte[to.length];
        for (int c = 0; c + 1 < start.length; c++) {
            long from = positions[c];
            for (int e = start[c]; e < start[c + 1]; e++) {
                long p = positions[to[e]];
                int dx = Pos.x(p) - Pos.x(from);
                int dz = Pos.z(p) - Pos.z(from);
                if (Math.abs(dx) > 2 || Math.abs(dz) > 2) {
                    return null;
                }
                heading[e] = (byte) TurnPenalty.heading(dx, dz);
            }
        }
        return heading;
    }
}
