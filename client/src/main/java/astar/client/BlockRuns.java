package astar.client;

import java.io.IOException;
import java.util.Arrays;
import java.util.stream.IntStream;

/**
 * Reads back a whole-map copy's blocks as {@link LiveWorld#write} wrote them: (palette entry,
 * run length) pairs, 6 bytes each, big-endian. A quick pass finds where every few thousand
 * runs start; then the runs are laid out on all cores, into the copy's cells and, at the same
 * time, into the pathfinder's view of them, so neither needs another pass over the map.
 */
final class BlockRuns {
    /** Runs per piece laid out on one core. */
    private static final int PIECE = 1 << 14;

    private BlockRuns() {}

    /**
     * Lays out the runs in {@code data} from {@code from} to {@code to} over {@code cells},
     * which they must fill exactly (runs past the end, and bytes short of a whole run, are
     * left alone), and over {@code view} (unless null) as each entry's kind in {@code
     * ordinals}.
     *
     * @param entries how many palette entries there are
     * @throws IOException if a run names no entry, is empty, or runs past the end, or the
     *     runs stop short of the end
     */
    static void decode(byte[] data, int from, int to, short[] cells, int entries,
            byte[] ordinals, byte[] view) throws IOException {
        int records = Math.max(0, (to - from) / 6);
        int[] pieceStart = new int[(records + PIECE - 1) / PIECE + 1];
        int i = 0;
        int used = 0;
        for (int b = from; used < records && i < cells.length; b += 6, used++) {
            if ((used & (PIECE - 1)) == 0) {
                pieceStart[used / PIECE] = i;
            }
            int id = (data[b] & 0xFF) << 8 | data[b + 1] & 0xFF;
            int run = (data[b + 2] & 0xFF) << 24 | (data[b + 3] & 0xFF) << 16
                    | (data[b + 4] & 0xFF) << 8 | data[b + 5] & 0xFF;
            // An entry past 0x7fff reads as a negative short, which was refused too.
            if (id >= entries || id > Short.MAX_VALUE || run <= 0 || run > cells.length - i) {
                throw new IOException("bad blocks");
            }
            i += run;
        }
        if (i != cells.length) {
            throw new IOException("cut short");
        }
        int runs = used;
        IntStream.range(0, (runs + PIECE - 1) / PIECE).parallel().forEach(p -> {
            int at = pieceStart[p];
            int end = Math.min(runs, (p + 1) * PIECE);
            for (int r = p * PIECE, b = from + 6 * r; r < end; r++, b += 6) {
                short id = (short) ((data[b] & 0xFF) << 8 | data[b + 1] & 0xFF);
                int run = (data[b + 2] & 0xFF) << 24 | (data[b + 3] & 0xFF) << 16
                        | (data[b + 4] & 0xFF) << 8 | data[b + 5] & 0xFF;
                Arrays.fill(cells, at, at + run, id);
                if (view != null) {
                    Arrays.fill(view, at, at + run, ordinals[id]);
                }
                at += run;
            }
        });
    }
}
