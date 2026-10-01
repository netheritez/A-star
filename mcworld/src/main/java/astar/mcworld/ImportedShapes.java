package astar.mcworld;

import astar.pathing.BlockType;
import java.util.HashMap;
import java.util.Map;

/**
 * The real shapes ({@link BlockShapes}) of an imported world's blocks, for the cells whose
 * block has one. Each is kept with the {@link BlockType} the cell was imported as, so a cell
 * that was edited since (in the editor, or by sealing the roof) falls back to its type.
 */
public final class ImportedShapes {

    private record Entry(BlockType type, BlockShapes.Shape shape) {}

    private final Map<Long, Entry> cells = new HashMap<>();

    void put(int x, int y, int z, BlockType type, BlockShapes.Shape shape) {
        cells.put(key(x, y, z), new Entry(type, shape));
    }

    /**
     * The cell's real shape, or null if it has none or isn't {@code type} (what the world holds
     * there now) any more.
     */
    public BlockShapes.Shape at(int x, int y, int z, BlockType type) {
        Entry e = cells.get(key(x, y, z));
        return e != null && e.type() == type ? e.shape() : null;
    }

    /** How many cells have a real shape. */
    public int size() {
        return cells.size();
    }

    private static long key(int x, int y, int z) {
        return ((long) x & 0x1FFFFF) << 42 | ((long) y & 0x1FFFFF) << 21 | ((long) z & 0x1FFFFF);
    }
}
