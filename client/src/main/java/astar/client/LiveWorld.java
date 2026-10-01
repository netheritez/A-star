package astar.client;

import astar.mcworld.BlockMapping;
import astar.movement.sim.Aabb;
import astar.movement.sim.SimBlock;
import astar.movement.sim.SimWorld;
import astar.pathing.ArrayBlockView;
import astar.pathing.BlockType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A copy of the blocks in a box of the loaded world, taken on the game thread so the route can
 * be found and planned on another. Each block state is looked at once: the pathfinder gets its
 * {@link BlockType} (as the map importer would give it) and the planner and simulator its real
 * collision shape and friction, read from the game.
 *
 * <p>Coordinates are relative to {@link #origin()}, as the pathfinder's array world wants.
 * Columns whose chunk isn't loaded are solid, so no route goes through what the game can't
 * see.
 */
final class LiveWorld implements SimWorld {

    /**
     * The most blocks a copy may hold (about 100 MB with its block view): enough for a trip
     * across the whole Mines that swings 130 blocks out to get round and dips 45 blocks under
     * both ends.
     */
    static final int MAX_VOLUME = 32_000_000;

    private final BlockPos origin;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final short[] cells;
    private final List<BlockType> types = new ArrayList<>();
    private final List<SimBlock> blocks = new ArrayList<>();
    /** The game's block state for each palette entry past the first two. */
    private final List<BlockState> states = new ArrayList<>();
    /** Where each palette entry past the first two was first seen (its shape may depend on it). */
    private final List<BlockPos> seenAt = new ArrayList<>();
    private final Map<BlockState, Short> palette = new java.util.concurrent.ConcurrentHashMap<>();
    private ArrayBlockView view;
    /**
     * The view's cells as {@link #read} laid them out with the blocks, until {@link #blockView}
     * takes them up; dropped when a block changes before then.
     */
    private byte[] readView;

    private LiveWorld(BlockPos origin, int sizeX, int sizeY, int sizeZ) {
        this.origin = origin;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        cells = new short[sizeX * sizeY * sizeZ];
        // Palette entry 0 is air, 1 is an unloaded column.
        types.add(BlockType.AIR);
        blocks.add(SimBlock.AIR);
        types.add(BlockType.SOLID);
        blocks.add(SimBlock.solid(new Aabb(0, 0, 0, 1, 1, 1)));
        states.add(null);
        states.add(null);
        seenAt.add(null);
        seenAt.add(null);
    }

    /**
     * The loaded chunks around the player, copied quickly on the game thread ({@link
     * SavedChunk#copy}), so a copy of the world can be made from them on another thread.
     */
    record Snapshot(Level level, Map<Long, SavedChunk.Copied> chunks, int minY, int maxY) {
        static Snapshot of(Level level, int cx0, int cz0, int cx1, int cz1) {
            Map<Long, SavedChunk.Copied> chunks = new java.util.HashMap<>();
            for (int cx = cx0; cx <= cx1; cx++) {
                for (int cz = cz0; cz <= cz1; cz++) {
                    if (level.getChunkSource().hasChunk(cx, cz)) {
                        chunks.put(SavedView.key(cx, cz), SavedChunk.copy(level.getChunk(cx, cz)));
                    }
                }
            }
            return new Snapshot(level, chunks, level.getMinY(), level.getMaxY());
        }
    }

    /** {@link #copy(Level, BlockPos, BlockPos)} from a snapshot, on any thread. */
    static LiveWorld copy(Snapshot snap, BlockPos min, BlockPos max) {
        return copy(snap, min, max, MAX_VOLUME);
    }

    /**
     * Checks that a copy from {@code min} to {@code max}, its height clamped to {@code minY}
     * to {@code maxY}, holds at most {@code limit} blocks.
     *
     * @throws IllegalArgumentException if it holds more
     */
    static void checkSize(BlockPos min, BlockPos max, int minY, int maxY, long limit) {
        int y0 = Math.max(min.getY(), minY);
        int y1 = Math.min(max.getY(), maxY);
        long volume = (long) (max.getX() - min.getX() + 1) * (y1 - y0 + 1)
                * (max.getZ() - min.getZ() + 1);
        if (volume > limit) {
            throw new IllegalArgumentException("that's too far to plan in one go (" + volume
                    + " blocks to read, at most " + limit + ")");
        }
    }

    /** The same, holding up to {@code limit} blocks (a whole map, say). */
    static LiveWorld copy(Snapshot snap, BlockPos min, BlockPos max, long limit) {
        checkSize(min, max, snap.minY(), snap.maxY(), limit);
        int y0 = Math.max(min.getY(), snap.minY());
        int y1 = Math.min(max.getY(), snap.maxY());
        LiveWorld w = new LiveWorld(new BlockPos(min.getX(), y0, min.getZ()),
                max.getX() - min.getX() + 1, y1 - y0 + 1, max.getZ() - min.getZ() + 1);
        List<Column> columns = new ArrayList<>();
        for (int cx = min.getX() >> 4; cx <= max.getX() >> 4; cx++) {
            for (int cz = min.getZ() >> 4; cz <= max.getZ() >> 4; cz++) {
                SavedChunk.Copied c = snap.chunks().get(SavedView.key(cx, cz));
                columns.add(c != null ? new Column(cx, cz, c.states(), c.minSection(), null)
                        : new Column(cx, cz, null, 0, Places.saved(snap.level(), cx, cz)));
            }
        }
        w.read(EmptyBlockGetter.INSTANCE, columns, min.getX(), y0, min.getZ(), max.getX(), y1,
                max.getZ(), true);
        return w;
    }

    /** One chunk's column to read: its sections (null ones all air), or what was saved of it. */
    private record Column(int cx, int cz, PalettedContainer<BlockState>[] sections,
            int minSection, SavedChunk saved) {}

    /**
     * How many chunks touching the box, within {@code view} chunks of {@code center}, aren't
     * loaded yet. Chunks further out won't load however long it waits.
     */
    static int unloadedChunks(Level level, BlockPos min, BlockPos max, BlockPos center,
            int view) {
        int n = 0;
        for (int cx = min.getX() >> 4; cx <= max.getX() >> 4; cx++) {
            for (int cz = min.getZ() >> 4; cz <= max.getZ() >> 4; cz++) {
                // The game sends the chunks in a circle round the player, not a square: the
                // corners of the square never come, so they mustn't be waited for.
                boolean inView = ChunkTrackingView.isInViewDistance(center.getX() >> 4,
                        center.getZ() >> 4, view, cx, cz);
                n += inView && !level.getChunkSource().hasChunk(cx, cz) ? 1 : 0;
            }
        }
        return n;
    }

    private int read(BlockGetter getter, List<Column> columns, int x0, int y0, int z0, int x1,
            int y1, int z1, boolean fresh) {
        readView = null;
        // The columns are read in parallel: each writes only its own blocks. The pathfinder's
        // view isn't safe to change from several threads, so what changes in it is set
        // afterwards, column by column, in the order a one-thread read would have set it.
        Changes[] later = new Changes[columns.size()];
        int changed = java.util.stream.IntStream.range(0, columns.size()).parallel()
                .map(i -> column(getter, columns.get(i), x0, y0, z0, x1, y1, z1, fresh,
                        later[i] = new Changes()))
                .sum();
        for (Changes c : later) {
            for (int k = 0; k < c.size; k++) {
                int i = c.at[k];
                int x = i % sizeX;
                int z = i / sizeX % sizeZ;
                int y = i / sizeX / sizeZ;
                view.set(x, y, z, types.get(cells[i]));
            }
        }
        return changed;
    }

    /** The blocks whose kind changed in the pathfinder's view, by index, to set there. */
    private static final class Changes {
        int[] at = new int[16];
        int size;

        void add(int i) {
            if (size == at.length) {
                at = java.util.Arrays.copyOf(at, 2 * size);
            }
            at[size++] = i;
        }
    }

    /** {@link #read} for one chunk's column; how many blocks changed. */
    private int column(BlockGetter level, Column col, int x0, int y0, int z0, int x1, int y1,
            int z1, boolean fresh, Changes later) {
        int cx = col.cx();
        int cz = col.cz();
        SavedChunk saved = col.saved();
        int ox = origin.getX();
        int oy = origin.getY();
        int oz = origin.getZ();
        int ax = Math.max(x0, cx << 4);
        int bx = Math.min(x1, (cx << 4) + 15);
        int az = Math.max(z0, cz << 4);
        int bz = Math.min(z1, (cz << 4) + 15);
        if (col.sections() == null) {
            if (saved != null) {
                readSaved(level, saved, ax, bx, y0, y1, az, bz);
            } else if (fresh) {
                for (int y = y0; y <= y1; y++) {
                    for (int z = az; z <= bz; z++) {
                        for (int x = ax; x <= bx; x++) {
                            cells[index(x - ox, y - oy, z - oz)] = 1;
                        }
                    }
                }
            }
            return 0;
        }
        int changed = 0;
        BlockState lastState = null;
        short lastId = 0;
        for (int y = y0; y <= y1; y++) {
            int si = (y >> 4) - col.minSection();
            PalettedContainer<BlockState> section = si >= 0 && si < col.sections().length
                    ? col.sections()[si] : null;
            if (section == null) {
                // The rest of this section is air, entry 0.
                int top = Math.min(y1, y | 15);
                if (!fresh) {
                    for (int yy = y; yy <= top; yy++) {
                        for (int z = az; z <= bz; z++) {
                            for (int x = ax; x <= bx; x++) {
                                changed += put(x - ox, yy - oy, z - oz, (short) 0, later);
                            }
                        }
                    }
                }
                y = top;
                continue;
            }
            // x innermost, as both the section and the copy store it; neighbouring blocks are
            // mostly the same, so the last one's entry is kept at hand.
            for (int z = az; z <= bz; z++) {
                for (int x = ax; x <= bx; x++) {
                    BlockState state = section.get(x & 15, y & 15, z & 15);
                    short id;
                    if (state == lastState) {
                        id = lastId;
                    } else if (state.isAir()) {
                        id = 0;
                    } else {
                        id = id(level, x, y, z, state);
                        lastState = state;
                        lastId = id;
                    }
                    if (fresh) {
                        cells[index(x - ox, y - oy, z - oz)] = id;
                    } else {
                        changed += put(x - ox, y - oy, z - oz, id, later);
                    }
                }
            }
        }
        return changed;
    }

    /** The copy's entry for a block state (not air), added the first time it's seen. */
    private short id(BlockGetter level, int x, int y, int z, BlockState state) {
        Short known = palette.get(state);
        if (known == null) {
            synchronized (this) {
                known = palette.get(state);
                if (known == null) {
                    known = add(level, new BlockPos(x, y, z), state);
                    palette.put(state, known);
                }
            }
        }
        return known;
    }

    /**
     * Fills a column range from a chunk the game hasn't loaded but was seen before or comes
     * from a saved map ({@link Places}).
     */
    private void readSaved(BlockGetter level, SavedChunk saved, int ax, int bx, int y0, int y1,
            int az, int bz) {
        int ox = origin.getX();
        int oy = origin.getY();
        int oz = origin.getZ();
        // Neighbouring blocks are mostly the same, so the last one's entry is kept at hand.
        int lastStateId = 0;
        short lastId = 0;
        for (int y = y0; y <= y1; y++) {
            if (saved.airSection(y)) {
                y |= 15; // entry 0 already
                continue;
            }
            for (int x = ax; x <= bx; x++) {
                for (int z = az; z <= bz; z++) {
                    int stateId = saved.stateId(x, y, z);
                    short id = 0;
                    if (stateId != 0) {
                        if (stateId != lastStateId) {
                            lastId = id(level, x, y, z, net.minecraft.world.level.block.Block
                                    .stateById(stateId));
                            lastStateId = stateId;
                        }
                        id = lastId;
                    }
                    cells[index(x - ox, y - oy, z - oz)] = id;
                }
            }
        }
    }

    /**
     * Writes the copy, to be read back by {@link #read(byte[], int)} as it is now: its box,
     * its palette as the game's block state ids, and its blocks, run by run.
     */
    void write(java.io.DataOutputStream out) throws java.io.IOException {
        out.writeInt(origin.getX());
        out.writeInt(origin.getY());
        out.writeInt(origin.getZ());
        out.writeInt(sizeX);
        out.writeInt(sizeY);
        out.writeInt(sizeZ);
        int n = states.size();
        out.writeInt(n);
        for (int i = 2; i < n; i++) {
            BlockPos at = seenAt.get(i);
            out.writeInt(net.minecraft.world.level.block.Block.getId(states.get(i)));
            out.writeInt(at.getX());
            out.writeInt(at.getY());
            out.writeInt(at.getZ());
        }
        // (entry, run length) pairs; most of a map is long runs of air and stone.
        byte[] buf = new byte[1 << 16];
        int b = 0;
        for (int i = 0; i < cells.length; ) {
            short id = cells[i];
            int j = i + 1;
            while (j < cells.length && cells[j] == id) {
                j++;
            }
            int run = j - i;
            if (b + 6 > buf.length) {
                out.write(buf, 0, b);
                b = 0;
            }
            buf[b++] = (byte) (id >> 8);
            buf[b++] = (byte) id;
            buf[b++] = (byte) (run >> 24);
            buf[b++] = (byte) (run >> 16);
            buf[b++] = (byte) (run >> 8);
            buf[b++] = (byte) run;
            i = j;
        }
        out.write(buf, 0, b);
    }

    /**
     * A copy as {@link #write} wrote it, from {@code data} at {@code from} to its end, its
     * palette looked at again in this game (as a fresh copy would, with no world around each
     * block). The pathfinder's view ({@link #blockView}) is laid out with the blocks, as they're
     * read.
     *
     * @throws java.io.IOException if it can't be read, or doesn't fit this game's blocks
     */
    static LiveWorld read(byte[] data, int from) throws java.io.IOException {
        java.io.ByteArrayInputStream bytes = new java.io.ByteArrayInputStream(data, from,
                data.length - from);
        java.io.DataInputStream in = new java.io.DataInputStream(bytes);
        BlockPos origin = new BlockPos(in.readInt(), in.readInt(), in.readInt());
        int sx = in.readInt();
        int sy = in.readInt();
        int sz = in.readInt();
        if (sx <= 0 || sy <= 0 || sz <= 0 || (long) sx * sy * sz > Integer.MAX_VALUE - 8) {
            throw new java.io.IOException("bad size");
        }
        LiveWorld w = new LiveWorld(origin, sx, sy, sz);
        int n = in.readInt();
        for (int i = 2; i < n; i++) {
            BlockState state = net.minecraft.world.level.block.Block.stateById(in.readInt());
            BlockPos at = new BlockPos(in.readInt(), in.readInt(), in.readInt());
            if (state.isAir() || w.palette.containsKey(state)) {
                throw new java.io.IOException("not this game's blocks");
            }
            w.palette.put(state, w.add(EmptyBlockGetter.INSTANCE, at, state));
        }
        byte[] view = new byte[w.cells.length];
        BlockRuns.decode(data, data.length - bytes.available(), data.length, w.cells,
                n, w.ordinals(), view);
        w.readView = view;
        return w;
    }

    /**
     * Reads the chunks' columns again, fresh (from what was saved of them), where the copy
     * holds them: they changed since it was written.
     */
    void reread(List<SavedChunk> chunks) {
        List<Column> columns = new ArrayList<>();
        for (SavedChunk c : chunks) {
            columns.add(new Column(c.cx, c.cz, null, 0, c));
        }
        int x0 = origin.getX();
        int y0 = origin.getY();
        int z0 = origin.getZ();
        int x1 = x0 + sizeX - 1;
        int y1 = y0 + sizeY - 1;
        int z1 = z0 + sizeZ - 1;
        // Air isn't written over when read fresh, so clear first.
        for (Column c : columns) {
            int ax = Math.max(x0, c.cx() << 4);
            int bx = Math.min(x1, (c.cx() << 4) + 15);
            int az = Math.max(z0, c.cz() << 4);
            int bz = Math.min(z1, (c.cz() << 4) + 15);
            for (int y = y0; y <= y1 && ax <= bx; y++) {
                for (int z = az; z <= bz; z++) {
                    java.util.Arrays.fill(cells, index(ax - x0, y - y0, z - z0),
                            index(bx - x0, y - y0, z - z0) + 1, (short) 0);
                }
            }
        }
        read(EmptyBlockGetter.INSTANCE, columns, x0, y0, z0, x1, y1, z1, true);
    }

    /**
     * Sets one block (copy coordinates); 1 if it changed. Where its kind changed, the
     * pathfinder's view is to be set to match: {@code later} keeps it for that.
     */
    private int put(int x, int y, int z, short id, Changes later) {
        int i = index(x, y, z);
        if (cells[i] == id) {
            return 0;
        }
        short was = cells[i];
        cells[i] = id;
        // Another state of the same kind (one ore for another) leaves the pathfinder's view,
        // and so its move graph and landmarks, as they are.
        if (view != null && types.get(was) != types.get(id)) {
            later.add(i);
        }
        return 1;
    }

    /**
     * Whether the copy holds the box from {@code min} to {@code max}, with the box's height
     * clamped to the world's, as {@link #copy} clamps it.
     */
    boolean holds(Level level, BlockPos min, BlockPos max) {
        int y0 = Math.max(min.getY(), level.getMinY());
        int y1 = Math.min(max.getY(), level.getMaxY());
        return contains(new BlockPos(min.getX(), y0, min.getZ()))
                && contains(new BlockPos(max.getX(), y1, max.getZ()));
    }

    /**
     * Reads the box from {@code min} to {@code max} (game coordinates, clamped to the copy)
     * again from the loaded chunks snapshotted ({@link Snapshot#of}), on any thread; how many
     * blocks changed. The pathfinder's view changes with them, so its move graph is patched
     * only there.
     */
    int resync(Snapshot snap, BlockPos min, BlockPos max) {
        int ox = origin.getX();
        int oy = origin.getY();
        int oz = origin.getZ();
        int x0 = Math.max(min.getX(), ox);
        int y0 = Math.max(min.getY(), oy);
        int z0 = Math.max(min.getZ(), oz);
        int x1 = Math.min(max.getX(), ox + sizeX - 1);
        int y1 = Math.min(max.getY(), oy + sizeY - 1);
        int z1 = Math.min(max.getZ(), oz + sizeZ - 1);
        if (x0 > x1 || y0 > y1 || z0 > z1) {
            return 0;
        }
        // Only the chunks the game has loaded; the rest keep what was read before.
        List<Column> columns = new ArrayList<>();
        for (int cx = x0 >> 4; cx <= x1 >> 4; cx++) {
            for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) {
                SavedChunk.Copied c = snap.chunks().get(SavedView.key(cx, cz));
                if (c != null) {
                    columns.add(new Column(cx, cz, c.states(), c.minSection(), null));
                }
            }
        }
        return read(EmptyBlockGetter.INSTANCE, columns, x0, y0, z0, x1, y1, z1, false);
    }

    private short add(BlockGetter level, BlockPos pos, BlockState state) {
        if (types.size() == Short.MAX_VALUE) {
            throw new IllegalStateException("more than " + Short.MAX_VALUE + " block states");
        }
        SimBlock block = simBlock(level, pos, state);
        List<double[]> boxes = new ArrayList<>();
        for (Aabb b : block.boxes()) {
            boxes.add(new double[] {b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()});
        }
        types.add(BlockMapping.refine(BlockMapping.map(mapped(state)), boxes));
        blocks.add(block);
        states.add(state);
        seenAt.add(pos.immutable());
        return (short) (types.size() - 1);
    }

    /** The block as the map importer sees it: its id and properties. */
    private static astar.mcworld.BlockState mapped(BlockState state) {
        Map<String, String> props = new java.util.HashMap<>();
        for (Property<?> p : state.getProperties()) {
            props.put(p.getName(), value(state, p));
        }
        return new astar.mcworld.BlockState(
                BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), props);
    }

    private static <T extends Comparable<T>> String value(BlockState state, Property<T> p) {
        return p.getName(state.getValue(p));
    }

    /** What the simulator needs, read from the game as the trace recorder reads it. */
    private static SimBlock simBlock(BlockGetter level, BlockPos pos, BlockState state) {
        VoxelShape shape = state.getCollisionShape(level, pos, CollisionContext.empty());
        List<Aabb> boxes = new ArrayList<>();
        for (AABB b : shape.toAabbs()) {
            boxes.add(new Aabb(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ));
        }
        List<Double> pointsY = shape.isEmpty() ? List.of()
                : List.copyOf(shape.getCoords(Direction.Axis.Y));
        String kind = state.getBlock() instanceof FenceGateBlock ? "gate"
                : state.is(BlockTags.WALLS) ? "wall"
                : state.is(BlockTags.FENCES) ? "fence" : "";
        return new SimBlock(boxes, pointsY, state.getBlock().getFriction(),
                state.getBlock().getSpeedFactor(), state.getBlock().getJumpFactor(), kind,
                state.getFluidState().is(FluidTags.WATER), state.is(BlockTags.CLIMBABLE));
    }

    private int index(int x, int y, int z) {
        return (y * sizeZ + z) * sizeX + x;
    }

    BlockPos origin() {
        return origin;
    }

    /** How many blocks the copy spans along x, y and z. */
    BlockPos size() {
        return new BlockPos(sizeX, sizeY, sizeZ);
    }

    boolean contains(BlockPos p) {
        int x = p.getX() - origin.getX();
        int y = p.getY() - origin.getY();
        int z = p.getZ() - origin.getZ();
        return x >= 0 && y >= 0 && z >= 0 && x < sizeX && y < sizeY && z < sizeZ;
    }

    /**
     * Reads one block (in the copy's coordinates) from the game again, if its chunk is loaded;
     * whether it changed. The pathfinder's view changes with it.
     */
    boolean sync(Level level, int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) {
            return false;
        }
        BlockPos pos = origin.offset(x, y, z);
        if (!level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        int i = index(x, y, z);
        short was = cells[i];
        short now;
        if (state.isAir()) {
            now = 0;
        } else {
            Short id = palette.get(state);
            if (id == null) {
                id = add(level, pos, state);
                palette.put(state, id);
            }
            now = id;
        }
        if (now == was) {
            return false;
        }
        cells[i] = now;
        readView = null;
        if (view != null && types.get(was) != types.get(now)) {
            view.set(x, y, z, types.get(now));
        }
        return true;
    }

    /** Each palette entry's {@link BlockType}, as its ordinal. */
    private byte[] ordinals() {
        byte[] ordinals = new byte[types.size()];
        for (int i = 0; i < ordinals.length; i++) {
            ordinals[i] = (byte) types.get(i).ordinal();
        }
        return ordinals;
    }

    /** The pathfinder's view of the copy; it follows {@link #sync}. */
    ArrayBlockView blockView() {
        if (view != null) {
            return view;
        }
        if (readView != null) {
            view = ArrayBlockView.of(sizeX, sizeY, sizeZ, readView);
            readView = null;
            return view;
        }
        byte[] ordinals = ordinals();
        byte[] out = new byte[cells.length];
        int layer = sizeX * sizeZ;
        java.util.stream.IntStream.range(0, sizeY).parallel().forEach(y -> {
            for (int i = y * layer, end = i + layer; i < end; i++) {
                out[i] = ordinals[cells[i]];
            }
        });
        view = ArrayBlockView.of(sizeX, sizeY, sizeZ, out);
        return view;
    }

    @Override
    public SimBlock block(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) {
            return SimBlock.AIR;
        }
        return blocks.get(cells[index(x, y, z)]);
    }
}
