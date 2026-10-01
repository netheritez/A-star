package astar.client;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;

/**
 * The blocks of one chunk, kept after the game unloads it: from a chunk seen while exploring,
 * or from a saved map. Each 16-block section is a palette of block states and an index per
 * block; all-air sections hold nothing.
 */
final class SavedChunk {
    final int cx;
    final int cz;
    /** The section index (y / 16) of {@link #palettes}[0]. */
    final int minSection;
    /** Per section, the block states' ids ({@link Block#getId}); null for all air. */
    private final int[][] palettes;
    /** Per section, each block's palette entry (byte[] or short[]), y, z, x order. */
    private final Object[] indices;

    private SavedChunk(int cx, int cz, int minSection, int[][] palettes, Object[] indices) {
        this.cx = cx;
        this.cz = cz;
        this.minSection = minSection;
        this.palettes = palettes;
        this.indices = indices;
    }

    /**
     * A loaded chunk's blocks as they are now, copied quickly on the game thread; {@link
     * Copied#build} makes the saved chunk from them on any thread.
     */
    static Copied copy(LevelChunk chunk) {
        LevelChunkSection[] sections = chunk.getSections();
        @SuppressWarnings("unchecked")
        PalettedContainer<BlockState>[] states = new PalettedContainer[sections.length];
        for (int s = 0; s < sections.length; s++) {
            LevelChunkSection section = sections[s];
            if (section != null && !section.hasOnlyAir()) {
                states[s] = section.getStates().copy();
            }
        }
        return new Copied(chunk.getPos().x(), chunk.getPos().z(), chunk.getMinSectionY(),
                states);
    }

    record Copied(int cx, int cz, int minSection, PalettedContainer<BlockState>[] states) {
        SavedChunk build() {
            int n = states.length;
            int[][] palettes = new int[n][];
            Object[] indices = new Object[n];
            Builder b = new Builder();
            for (int s = 0; s < n; s++) {
                PalettedContainer<BlockState> section = states[s];
                if (section == null) {
                    continue;
                }
                b.start();
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            BlockState state = section.get(x, y, z);
                            b.put(state.isAir() ? 0 : Block.getId(state));
                        }
                    }
                }
                palettes[s] = b.palette();
                indices[s] = b.indices();
            }
            return new SavedChunk(cx, cz, minSection, palettes, indices);
        }
    }

    /** A copy of a loaded chunk as it is now (on the game thread: {@link #copy} is quicker). */
    static SavedChunk of(LevelChunk chunk) {
        return copy(chunk).build();
    }

    /** Collects one section's blocks into a palette and indices. */
    static final class Builder {
        private int[] palette = new int[16];
        private final short[] idx = new short[4096];
        /** The indices put by {@link #putAll} when they fit in bytes, else null. */
        private byte[] bytes;
        private int size;
        private int i;
        private int lastId = -1;
        private int lastP;

        void start() {
            size = 0;
            i = 0;
            lastId = -1;
            bytes = null;
        }

        void put(int stateId) {
            if (stateId != lastId) {
                lastId = stateId;
                lastP = entry(stateId);
            }
            idx[i++] = (short) lastP;
        }

        /**
         * Puts a whole section's blocks, as {@link #put} would one at a time: {@code entries}
         * are their places in {@code ids}, a palette of state ids (from a saved map, say).
         * Each palette entry is looked up once, not at every change of block.
         */
        void putAll(int[] entries, int[] ids) {
            int[] to = new int[ids.length];
            Arrays.fill(to, -1);
            // A palette of 256 or fewer makes one that fits in bytes: written straight there.
            byte[] b = ids.length <= 256 && i == 0 ? new byte[4096] : null;
            for (int e : entries) {
                int p = to[e];
                if (p < 0) {
                    p = entry(ids[e]);
                    to[e] = p;
                }
                if (b != null) {
                    b[i++] = (byte) p;
                } else {
                    idx[i++] = (short) p;
                }
            }
            bytes = b;
            lastId = -1;
        }

        /** The palette entry for a state id, added if it's new. */
        private int entry(int stateId) {
            for (int k = 0; k < size; k++) {
                if (palette[k] == stateId) {
                    return k;
                }
            }
            if (size == palette.length) {
                palette = Arrays.copyOf(palette, size * 2);
            }
            palette[size] = stateId;
            return size++;
        }

        int[] palette() {
            return Arrays.copyOf(palette, size);
        }

        Object indices() {
            if (bytes != null) {
                return bytes;
            }
            if (size <= 256) {
                byte[] out = new byte[4096];
                for (int k = 0; k < 4096; k++) {
                    out[k] = (byte) idx[k];
                }
                return out;
            }
            return idx.clone();
        }
    }

    /** A chunk built section by section, from a saved map. */
    static SavedChunk of(int cx, int cz, int minSection, int[][] palettes, Object[] indices) {
        return new SavedChunk(cx, cz, minSection, palettes, indices);
    }

    /** The block at game coordinates (x, y, z) in this chunk; air above and below it. */
    BlockState state(int x, int y, int z) {
        int id = stateId(x, y, z);
        return id == 0 ? Blocks.AIR.defaultBlockState() : Block.stateById(id);
    }

    /** The state id of the block at game coordinates in this chunk (0 is air). */
    int stateId(int x, int y, int z) {
        int s = (y >> 4) - minSection;
        if (s < 0 || s >= palettes.length || palettes[s] == null) {
            return 0;
        }
        int k = ((y & 15) * 16 + (z & 15)) * 16 + (x & 15);
        Object idx = indices[s];
        int p = idx instanceof byte[] b ? b[k] & 0xFF : ((short[]) idx)[k];
        return palettes[s][p];
    }

    /** Whether it's all air. */
    boolean empty() {
        for (int[] p : palettes) {
            if (p != null) {
                return false;
            }
        }
        return true;
    }

    /** Whether the section holding height y is all air. */
    boolean airSection(int y) {
        int s = (y >> 4) - minSection;
        return s < 0 || s >= palettes.length || palettes[s] == null;
    }

    /**
     * Widens {@code range} (lowest, highest) to the heights where something could stand in this
     * chunk: a block with two of air above it.
     */
    void standingHeights(int[] range) {
        // Each column is looked at from both ends only as far as it needs: from the top, the
        // highest block always has air above it; from the bottom, up to the first block with
        // two of air above.
        int n = palettes.length;
        int lo = Integer.MAX_VALUE;
        int hi = Integer.MIN_VALUE;
        for (int c = 0; c < 256; c++) {
            int top = Integer.MIN_VALUE;
            for (int s = n - 1; s >= 0 && top == Integer.MIN_VALUE; s--) {
                for (int y = 15; y >= 0 && palettes[s] != null; y--) {
                    if (solid(s, y * 256 + c)) {
                        top = (minSection + s) * 16 + y;
                        break;
                    }
                }
            }
            if (top == Integer.MIN_VALUE) {
                continue; // all air
            }
            hi = Math.max(hi, top + 1);
            lo = Math.min(lo, top + 1);
            // Then from the bottom up, across sections (all-air ones count as air), while a
            // lower height could still turn up.
            int air = 0;
            boolean below = false;
            for (int s = 0; s < n && lo > (minSection + s) * 16 - 2; s++) {
                if (palettes[s] == null) {
                    if (below) {
                        lo = Math.min(lo, (minSection + s) * 16 - air);
                        break;
                    }
                    continue;
                }
                int y = 0;
                for (; y < 16; y++) {
                    if (solid(s, y * 256 + c)) {
                        below = true;
                        air = 0;
                    } else if (below && ++air == 2) {
                        break;
                    }
                }
                if (y < 16) {
                    lo = Math.min(lo, (minSection + s) * 16 + y - 1);
                    break;
                }
            }
        }
        if (lo <= hi) {
            range[0] = Math.min(range[0], lo);
            range[1] = Math.max(range[1], hi);
        }
    }

    /** Whether the block at {@code k} (y, z, x order) of section {@code s} isn't air. */
    private boolean solid(int s, int k) {
        Object idx = indices[s];
        int p = idx instanceof byte[] b ? b[k] & 0xFF : ((short[]) idx)[k];
        return palettes[s][p] != 0;
    }

    /**
     * How alike two copies of the same chunk are: the share of blocks that match, among those
     * that are solid in either (so the air above doesn't count). 1 when neither has any.
     */
    double likeness(SavedChunk other) {
        long both = 0;
        long same = 0;
        int lo = Math.min(minSection, other.minSection) * 16;
        int hi = (Math.max(minSection + palettes.length, other.minSection
                + other.palettes.length)) * 16;
        int x0 = cx << 4;
        int z0 = cz << 4;
        for (int y = lo; y < hi; y++) {
            if (airSection(y) && other.airSection(y)) {
                y |= 15;
                continue;
            }
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int a = stateId(x0 + x, y, z0 + z);
                    int b = other.stateId(x0 + x, y, z0 + z);
                    if (a == 0 && b == 0) {
                        continue;
                    }
                    both++;
                    if (a == b) {
                        same++;
                    }
                }
            }
        }
        return both == 0 ? 1 : (double) same / both;
    }

    /**
     * Written with each palette as text ({@link BlockStateParser#serialize}), so a file stays
     * readable when the game's state numbering changes between versions.
     */
    void write(DataOutputStream out) throws IOException {
        out.writeInt(cx);
        out.writeInt(cz);
        out.writeInt(minSection);
        out.writeInt(palettes.length);
        for (int s = 0; s < palettes.length; s++) {
            int[] palette = palettes[s];
            if (palette == null) {
                out.writeShort(0);
                continue;
            }
            out.writeShort(palette.length);
            for (int id : palette) {
                out.writeUTF(BlockStateParser.serialize(Block.stateById(id)));
            }
            Object idx = indices[s];
            if (idx instanceof byte[] b) {
                out.write(b);
            } else {
                for (short v : (short[]) idx) {
                    out.writeShort(v);
                }
            }
        }
    }

    static SavedChunk read(DataInputStream in) throws IOException {
        int cx = in.readInt();
        int cz = in.readInt();
        int minSection = in.readInt();
        int n = in.readInt();
        int[][] palettes = new int[n][];
        Object[] indices = new Object[n];
        for (int s = 0; s < n; s++) {
            int size = in.readUnsignedShort();
            if (size == 0) {
                continue;
            }
            int[] palette = new int[size];
            for (int k = 0; k < size; k++) {
                palette[k] = stateId(in.readUTF());
            }
            palettes[s] = palette;
            if (size <= 256) {
                byte[] b = new byte[4096];
                in.readFully(b);
                indices[s] = b;
            } else {
                short[] v = new short[4096];
                for (int k = 0; k < 4096; k++) {
                    v[k] = in.readShort();
                }
                indices[s] = v;
            }
        }
        return new SavedChunk(cx, cz, minSection, palettes, indices);
    }

    private static final Map<String, Integer> PARSED = new ConcurrentHashMap<>();

    /**
     * The state id for a block written as text, {@code minecraft:oak_stairs[facing=east,...]}.
     * One this version doesn't know becomes its block's default state, or, for a block it
     * doesn't know at all, air if the pathfinder would walk through it and stone otherwise.
     */
    static int stateId(String text) {
        // Parsed outside the map's lock, so chunks read at once don't wait on each other's
        // (the first parse is slow); one parsed twice meanwhile comes out the same.
        Integer id = PARSED.get(text);
        if (id == null) {
            id = parse(text);
            PARSED.putIfAbsent(text, id);
        }
        return id;
    }

    private static int parse(String text) {
        try {
            return Block.getId(BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, text,
                    false).blockState());
        } catch (CommandSyntaxException | RuntimeException e) {
            int bracket = text.indexOf('[');
            String name = bracket < 0 ? text : text.substring(0, bracket);
            try {
                return Block.getId(BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, name,
                        false).blockState());
            } catch (CommandSyntaxException | RuntimeException e2) {
                boolean passable = astar.mcworld.BlockMapping.map(new astar.mcworld.BlockState(
                        name, Map.of())).shape(false).passable();
                return passable ? 0 : Block.getId(Blocks.STONE.defaultBlockState());
            }
        }
    }
}
