package astar.pathing;

import astar.core.MoveSource;
import astar.core.Pos;

/**
 * Implements the core's {@link MoveSource} over a block world, using the rules in
 * {@link MoveValidator#moves}: walks (including steps up and down of up to 0.6), jumps and drops
 * into each neighbouring column, level diagonals if enabled (and with 16 directions, level
 * moves one across and two along), and stepping on stairs.
 */
public final class BlockMoveSource implements MoveSource {
    private final MoveValidator validator;
    private final int directions;

    public BlockMoveSource(MoveValidator validator, boolean diagonal) {
        this(validator, diagonal ? 8 : 4);
    }

    /** @param directions 4, 8 or 16 */
    public BlockMoveSource(MoveValidator validator, int directions) {
        if (directions != 4 && directions != 8 && directions != 16) {
            throw new IllegalArgumentException("directions must be 4, 8 or 16: " + directions);
        }
        this.validator = validator;
        this.directions = directions;
    }

    @Override
    public void moves(long from, MoveSink sink) {
        validator.moves(Pos.x(from), Pos.y(from), Pos.z(from), directions, sink);
    }
}
