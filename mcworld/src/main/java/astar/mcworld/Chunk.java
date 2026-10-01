package astar.mcworld;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The blocks of one chunk (16 x 16 columns) in the 1.18+ format: a list of 16-block-tall
 * sections, each with a palette of block states and a packed array of palette indices.
 */
public final class Chunk {
    /** One 16 x 16 x 16 section. */
    public record Section(int y, List<BlockState> palette, long[] data) {

        /** Bits per index: at least 4, enough for the palette. */
        int bits() {
            return Math.max(4, 32 - Integer.numberOfLeadingZeros(Math.max(1, palette.size() - 1)));
        }

        /** The block at local coordinates 0..15. Index order is y, then z, then x. */
        public BlockState get(int x, int y, int z) {
            if (palette.size() == 1 || data == null || data.length == 0) {
                return palette.get(0);
            }
            int index = (y * 16 + z) * 16 + x;
            int bits = bits();
            int perLong = 64 / bits; // since 1.16, entries never span two longs
            long word = data[index / perLong];
            int shift = (index % perLong) * bits;
            int p = (int) ((word >>> shift) & ((1L << bits) - 1));
            if (p >= palette.size()) {
                throw new IllegalStateException("Palette index " + p + " out of range in section " + this.y);
            }
            return palette.get(p);
        }

        /**
         * Every block's palette index, in the same y, z, x order as {@link #get}: unpacked in
         * one pass, rather than working out each block's place in the data one at a time.
         */
        public int[] indices() {
            int[] out = new int[4096];
            indices(out);
            return out;
        }

        /** {@link #indices()} into {@code out} (4096 long), so one array serves many sections. */
        public void indices(int[] out) {
            int size = palette.size();
            if (size == 1 || data == null || data.length == 0) {
                java.util.Arrays.fill(out, 0, 4096, 0);
                return;
            }
            int bits = bits();
            int perLong = 64 / bits; // since 1.16, entries never span two longs
            long mask = (1L << bits) - 1;
            if (data.length < (4096 + perLong - 1) / perLong) {
                throw new IllegalStateException("Block data too short in section " + this.y);
            }
            int i = 0;
            for (int w = 0; i < 4096; w++) {
                long word = data[w];
                for (int k = 0; k < perLong && i < 4096; k++, word >>>= bits) {
                    int p = (int) (word & mask);
                    if (p >= size) {
                        throw new IllegalStateException("Palette index " + p
                                + " out of range in section " + this.y);
                    }
                    out[i++] = p;
                }
            }
        }
    }

    private final int chunkX;
    private final int chunkZ;
    private final boolean generated;
    private final List<Section> sections;

    private Chunk(int chunkX, int chunkZ, boolean generated, List<Section> sections) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.generated = generated;
        this.sections = sections;
    }

    /**
     * Decodes a chunk's root NBT. Chunks saved before 1.18 (with a {@code Level} compound)
     * aren't supported.
     */
    public static Chunk decode(Map<String, Object> root) {
        if (root.containsKey("Level") && !root.containsKey("sections")) {
            throw new IllegalArgumentException("Chunk was saved before 1.18; open and save the"
                    + " world in 1.18 or newer first");
        }
        int cx = Nbt.intValue(root, "xPos", 0);
        int cz = Nbt.intValue(root, "zPos", 0);
        List<Section> sections = new ArrayList<>();
        for (Object o : Nbt.list(root, "sections")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> sec = (Map<String, Object>) o;
            Map<String, Object> states = Nbt.compound(sec, "block_states");
            if (states == null) {
                continue; // light-only section: all air
            }
            List<BlockState> palette = new ArrayList<>();
            for (Object p : Nbt.list(states, "palette")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> entry = (Map<String, Object>) p;
                Map<String, String> props = new LinkedHashMap<>();
                Map<String, Object> rawProps = Nbt.compound(entry, "Properties");
                if (rawProps != null) {
                    rawProps.forEach((k, v) -> props.put(k, String.valueOf(v)));
                }
                palette.add(new BlockState(Nbt.string(entry, "Name"), Map.copyOf(props)));
            }
            if (palette.isEmpty()) {
                continue;
            }
            Object data = states.get("data");
            sections.add(new Section(Nbt.intValue(sec, "Y", 0), List.copyOf(palette),
                    data instanceof long[] l ? l : null));
        }
        String status = Nbt.string(root, "Status");
        boolean generated = status == null || status.equals("full")
                || status.equals("minecraft:full");
        return new Chunk(cx, cz, generated, List.copyOf(sections));
    }

    public int chunkX() {
        return chunkX;
    }

    public int chunkZ() {
        return chunkZ;
    }

    public List<Section> sections() {
        return sections;
    }

    /**
     * False for a chunk the game saved before it finished generating it (its {@code Status}
     * isn't {@code full}): the ring at the edge of explored land, bare terrain with nothing on
     * it yet, which players never see as it is.
     */
    public boolean generated() {
        return generated;
    }

    /** Calls {@code visitor} for every block that isn't plain air, in world coordinates. */
    public void forEachNonAir(BlockVisitor visitor) {
        for (Section s : sections) {
            List<BlockState> palette = s.palette();
            boolean[] air = new boolean[palette.size()];
            boolean allAir = true;
            for (int i = 0; i < air.length; i++) {
                air[i] = palette.get(i).isAir();
                allAir &= air[i];
            }
            if (allAir) {
                continue;
            }
            int baseY = s.y() * 16;
            int[] indices = s.indices();
            for (int i = 0; i < 4096; i++) {
                int p = indices[i];
                if (!air[p]) {
                    visitor.visit(chunkX * 16 + (i & 15), baseY + (i >> 8),
                            chunkZ * 16 + ((i >> 4) & 15), palette.get(p));
                }
            }
        }
    }

    @FunctionalInterface
    public interface BlockVisitor {
        void visit(int x, int y, int z, BlockState block);
    }
}
