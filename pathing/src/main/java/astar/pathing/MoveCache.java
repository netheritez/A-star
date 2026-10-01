package astar.pathing;

import astar.core.MoveSource;
import astar.core.MoveType;
import astar.core.Pos;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Remembers the moves out of each cell of an {@link ArrayBlockView}, so each cell's moves are
 * worked out once however often it's expanded: by the searches that re-plan over the same
 * ground, by each portal's {@link ClusterDijkstra} while the portal graph is built (every cell
 * of a cluster, once per portal), and by the goal's insertion in each hierarchical query.
 *
 * <p>Each move is kept in an int: the step to a column up to two away (or the same one), the rise
 * or fall in cells, and the move type. A cell costs about 30 bytes plus 4 per move. Only cells
 * that have been expanded and have moves are kept, in pages of 16 x 16 columns (a cell with no
 * moves, such as air or the inside of a wall, is quick to work out again). A cell with a move
 * too far to encode is marked, and always asks the source. Everything is dropped when the
 * world changes ({@link ArrayBlockView#version}).
 *
 * <p>Safe for threads that search the same world at once, as the portal graph's build does:
 * each page is locked while a cell is looked up (or worked out and stored) and its moves handed
 * on. Threads working in different clusters rarely share a page.
 */
public final class MoveCache implements MoveSource {
    private static final int SHIFT = 4;
    private static final MoveType[] TYPES = MoveType.values();

    private final MoveSource source;
    private final ArrayBlockView world;
    private final int pagesX;
    private final int pageCount;
    // Pages are published through an atomic array, so a thread that finds another thread's new
    // page also sees it fully built (a plain array gives no such guarantee off x86).
    private volatile AtomicReferenceArray<Page> pages;
    private volatile int version;

    public MoveCache(MoveSource source, ArrayBlockView world) {
        this.source = source;
        this.world = world;
        this.pagesX = (world.sizeX() >> SHIFT) + 1;
        this.pageCount = pagesX * ((world.sizeZ() >> SHIFT) + 1);
        this.pages = new AtomicReferenceArray<>(pageCount);
        this.version = world.version();
    }

    /** The source the moves come from. */
    public MoveSource source() {
        return source;
    }

    @Override
    public void moves(long from, MoveSink sink) {
        int x = Pos.x(from);
        int y = Pos.y(from);
        int z = Pos.z(from);
        if (!world.inBounds(x, y, z)) {
            source.moves(from, sink);
            return;
        }
        page(x, z).moves(from, source, sink);
    }

    /** Cells whose moves are stored. */
    public long cells() {
        long total = 0;
        AtomicReferenceArray<Page> all = pages;
        for (int i = 0; i < all.length(); i++) {
            Page p = all.get(i);
            total += p == null ? 0 : p.size();
        }
        return total;
    }

    /** Roughly how much memory the stored moves take. */
    public long bytes() {
        long total = 0;
        AtomicReferenceArray<Page> all = pages;
        for (int i = 0; i < all.length(); i++) {
            Page p = all.get(i);
            total += p == null ? 0 : p.bytes();
        }
        return total;
    }

    /** Forgets every cell, e.g. to free the memory. */
    public void clear() {
        forget();
    }

    private synchronized void forget() {
        pages = new AtomicReferenceArray<>(pageCount);
        version = world.version();
    }

    private Page page(int x, int z) {
        if (world.version() != version) {
            forget(); // the world changed (while nothing else searches it)
        }
        AtomicReferenceArray<Page> all = pages;
        int i = (z >> SHIFT) * pagesX + (x >> SHIFT);
        Page p = all.get(i);
        if (p == null) {
            Page made = new Page();
            p = all.compareAndSet(i, null, made) ? made : all.get(i);
        }
        return p;
    }

    /**
     * The cells of one 16 x 16 column page: each position's moves, one after another in one
     * array, found through an index. A missing cell is worked out and stored while the page is
     * locked, and the moves are handed on under the same lock: one lock per lookup, and no
     * allocation per cell.
     */
    private static final class Page implements MoveSink {
        private static final int NOT_CACHED = -1;
        private final LongIntMap index = new LongIntMap(256);
        private int[] moves = new int[1024]; // per cell: its count, then its moves
        private int used;
        private int cells;
        // Recording one cell's moves:
        private int fromX;
        private int fromY;
        private int fromZ;
        private boolean fits;

        synchronized void moves(long pos, MoveSource source, MoveSink sink) {
            int at = index.get(pos, -1);
            if (at < 0) {
                at = record(pos, source);
            }
            int n = at < 0 ? 0 : moves[at];
            if (n == NOT_CACHED) {
                source.moves(pos, sink);
                return;
            }
            int x = Pos.x(pos);
            int y = Pos.y(pos);
            int z = Pos.z(pos);
            for (int i = at + 1; i <= at + n; i++) {
                int m = moves[i];
                sink.accept(Pos.pack(x + (m & 7) - 2, y + ((m >> 6) & 0xff) - 128,
                        z + ((m >> 3) & 7) - 2), TYPES[m >>> 14]);
            }
        }

        /** Works out a cell's moves and stores them; -1 for none (not stored: most cells). */
        private int record(long pos, MoveSource source) {
            fromX = Pos.x(pos);
            fromY = Pos.y(pos);
            fromZ = Pos.z(pos);
            fits = true;
            int at = used;
            add(0);
            source.moves(pos, this);
            if (fits && used == at + 1) {
                used = at; // no moves: quick to work out again
                return -1;
            }
            if (fits) {
                moves[at] = used - at - 1;
            } else {
                used = at + 1;
                moves[at] = NOT_CACHED;
            }
            index.put(pos, at);
            cells++;
            return at;
        }

        @Override
        public void accept(long to, MoveType type) {
            int dx = Pos.x(to) - fromX;
            int dy = Pos.y(to) - fromY;
            int dz = Pos.z(to) - fromZ;
            if (Math.abs(dx) > 2 || Math.abs(dz) > 2 || dy < -128 || dy > 127) {
                fits = false;
                return;
            }
            add((dx + 2) | (dz + 2) << 3 | (dy + 128) << 6 | type.ordinal() << 14);
        }

        private void add(int v) {
            if (used == moves.length) {
                moves = Arrays.copyOf(moves, used * 2);
            }
            moves[used++] = v;
        }

        synchronized int size() {
            return cells;
        }

        synchronized long bytes() {
            return 4L * moves.length + 12L * 2 * index.size();
        }
    }
}
