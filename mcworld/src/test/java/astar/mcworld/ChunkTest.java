package astar.mcworld;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class ChunkTest {
    @Test
    void unpackingAllAtOnceMatchesReadingEachBlock() {
        Random rng = new Random(3);
        for (int paletteSize : new int[] {2, 5, 16, 17, 33, 100, 300}) {
            List<BlockState> palette = new ArrayList<>();
            for (int i = 0; i < paletteSize; i++) {
                palette.add(new BlockState("minecraft:block_" + i, Map.of()));
            }
            Chunk.Section s = new Chunk.Section(-2, List.copyOf(palette), null);
            int bits = s.bits();
            int perLong = 64 / bits;
            long[] data = new long[(4096 + perLong - 1) / perLong];
            for (int i = 0; i < 4096; i++) {
                data[i / perLong] |= (long) rng.nextInt(paletteSize) << ((i % perLong) * bits);
            }
            s = new Chunk.Section(-2, List.copyOf(palette), data);
            int[] indices = s.indices();
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        assertEquals(s.get(x, y, z), palette.get(indices[(y * 16 + z) * 16 + x]),
                                "palette of " + paletteSize + " at " + x + " " + y + " " + z);
                    }
                }
            }
        }
    }

    @Test
    void chunksTheGameHadNotFinishedAreMarked() {
        assertTrue(chunk(null).generated(), "no status: assume it's finished");
        assertTrue(chunk("minecraft:full").generated());
        assertTrue(chunk("full").generated());
        assertFalse(chunk("minecraft:noise").generated());
        assertFalse(chunk("minecraft:features").generated());
    }

    private static Chunk chunk(String status) {
        Map<String, Object> root = new HashMap<>();
        root.put("xPos", 1);
        root.put("zPos", 2);
        root.put("sections", List.of());
        if (status != null) {
            root.put("Status", status);
        }
        return Chunk.decode(root);
    }
}
