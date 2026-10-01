package astar.client;

import astar.mcworld.BlockMapping;
import astar.pathing.BlockType;
import astar.pathing.BlockView;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * The pathfinder's view of a whole map, in game coordinates, straight from chunks: those the
 * game had loaded when the view was made (copied then) and those {@link Places} saved. Nothing
 * is copied up front, so it can span a map far bigger than a {@link LiveWorld}; outside what's
 * known, and above and below the world, is a wall. Safe to read from any thread.
 */
final class SavedView implements BlockView {
    private static final Map<Integer, BlockType> TYPES = new ConcurrentHashMap<>();

    private final Map<Long, SavedChunk> loaded;
    private final Places.Place place;
    private final int minY;
    private final int maxY;

    SavedView(Map<Long, SavedChunk> loaded, Places.Place place, int minY, int maxY) {
        this.loaded = loaded;
        this.place = place;
        this.minY = minY;
        this.maxY = maxY;
    }

    static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    private record Last(long key, SavedChunk chunk) {}

    // Searches read the same chunk many times in a row.
    private volatile Last last = new Last(Long.MIN_VALUE, null);

    /** The chunk it knows at (cx, cz), loaded or saved, or null. */
    SavedChunk chunk(int cx, int cz) {
        long k = key(cx, cz);
        Last l = last;
        if (l.key() == k) {
            return l.chunk();
        }
        SavedChunk c = loaded.get(k);
        if (c == null && place != null) {
            c = place.chunk(cx, cz);
        }
        last = new Last(k, c);
        return c;
    }

    @Override
    public BlockType blockAt(int x, int y, int z) {
        if (y < minY || y > maxY) {
            return BlockType.VOID;
        }
        SavedChunk c = chunk(x >> 4, z >> 4);
        if (c == null) {
            return BlockType.VOID;
        }
        int id = c.stateId(x, y, z);
        return id == 0 ? BlockType.AIR : TYPES.computeIfAbsent(id, SavedView::type);
    }

    /** How the pathfinder sees a block state, as {@link LiveWorld} maps it. */
    private static BlockType type(int id) {
        BlockState state = Block.stateById(id);
        List<double[]> boxes = new ArrayList<>();
        for (AABB b : state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO,
                CollisionContext.empty()).toAabbs()) {
            boxes.add(new double[] {b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ});
        }
        Map<String, String> props = new java.util.HashMap<>();
        for (Property<?> p : state.getProperties()) {
            props.put(p.getName(), value(state, p));
        }
        return BlockMapping.refine(BlockMapping.map(new astar.mcworld.BlockState(
                BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), props)), boxes);
    }

    private static <T extends Comparable<T>> String value(BlockState state, Property<T> p) {
        return p.getName(state.getValue(p));
    }
}
