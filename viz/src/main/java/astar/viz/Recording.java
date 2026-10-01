package astar.viz;

import astar.core.BlockPoint;
import astar.core.NodeView;
import astar.core.SearchResult;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * A recorded search, split into steps. Step 0 is the moment the search starts; each later step
 * is one expansion together with the neighbour updates it caused. The last step also includes
 * the finish.
 *
 * <p>Every event is indexed once, up front: for each cell, the events that touched it and its
 * node and open/closed state after each one. A frame is then a read-only view of "the last
 * touch before this step", so making one costs nothing however far into the search it is, and
 * frames stay valid snapshots while others are made.
 */
public final class Recording {
    private static final byte OPEN = 1;
    private static final byte CLOSED = 2;

    private final List<SearchEvent> events;
    private final int[] expansionIndices;

    // Cells get ids in the order they're first touched, so the cells a frame knows are ids 0..k.
    private final Map<BlockPoint, Integer> ids;
    private final BlockPoint[] cells;
    private final int[] firstTouch; // by id: the first event that touched the cell
    private final int[] touchStart; // by id: where its events begin in touches (CSR)
    private final int[] touches; // event indices, grouped by cell, in order

    // By event: the node and state of the cell it touched, just after it.
    private final NodeView[] nodeAfter;
    private final byte[] stateAfter;
    private final int[] openAfter; // how many cells are open after each event
    private final int[] closedAfter;

    public Recording(List<SearchEvent> events) {
        if (events.isEmpty() || !(events.get(0) instanceof SearchEvent.Started)) {
            throw new IllegalArgumentException("A recording must begin with a Started event");
        }
        this.events = List.copyOf(events);
        int n = this.events.size();
        ids = HashMap.newHashMap(n / 2); // most searches touch a cell two or three times
        nodeAfter = new NodeView[n];
        stateAfter = new byte[n];
        openAfter = new int[n];
        closedAfter = new int[n];
        int[] cellOf = new int[n];
        int[] expansions = new int[16];
        int expansionCount = 0;
        List<BlockPoint> cellList = new ArrayList<>();
        int[] firsts = new int[16];
        int[] lastOf = new int[16]; // by id: the latest event indexed that touched it
        int open = 0;
        int closed = 0;
        for (int i = 0; i < n; i++) {
            SearchEvent e = this.events.get(i);
            BlockPoint p = switch (e) {
                case SearchEvent.Started s -> s.start().pos();
                case SearchEvent.Expanded x -> x.node().pos();
                case SearchEvent.Opened o -> o.node().pos();
                case SearchEvent.Improved im -> im.node().pos();
                case SearchEvent.Finished f -> f.result().found() ? goal() : null;
            };
            int id = -1;
            if (p != null) {
                Integer known = ids.get(p);
                if (known != null) {
                    id = known;
                } else if (!(e instanceof SearchEvent.Finished)) { // else a goal never reached
                    id = cellList.size();
                    ids.put(p, id);
                    cellList.add(p);
                    if (id == firsts.length) {
                        firsts = Arrays.copyOf(firsts, id * 2);
                        lastOf = Arrays.copyOf(lastOf, id * 2);
                    }
                    firsts[id] = i;
                    lastOf[id] = -1;
                }
            }
            cellOf[i] = id;
            if (id >= 0) {
                int last = lastOf[id];
                byte before = last < 0 ? 0 : stateAfter[last];
                NodeView nv = last < 0 ? null : nodeAfter[last];
                byte after = before;
                switch (e) {
                    case SearchEvent.Started s -> {
                        nv = s.start();
                        after |= OPEN;
                    }
                    case SearchEvent.Expanded x -> {
                        nv = x.node();
                        after = (byte) ((after & ~OPEN) | CLOSED);
                    }
                    case SearchEvent.Opened o -> {
                        nv = o.node();
                        after |= OPEN;
                    }
                    case SearchEvent.Improved im -> nv = im.node();
                    case SearchEvent.Finished f -> after &= ~OPEN; // polled, but never expanded
                }
                open += (after & OPEN) - (before & OPEN);
                closed += ((after & CLOSED) - (before & CLOSED)) / CLOSED;
                nodeAfter[i] = nv;
                stateAfter[i] = after;
                lastOf[id] = i;
            }
            openAfter[i] = open;
            closedAfter[i] = closed;
            if (e instanceof SearchEvent.Expanded) {
                if (expansionCount == expansions.length) {
                    expansions = Arrays.copyOf(expansions, expansionCount * 2);
                }
                expansions[expansionCount++] = i;
            }
        }
        expansionIndices = Arrays.copyOf(expansions, expansionCount);

        int cellCount = cellList.size();
        cells = cellList.toArray(new BlockPoint[0]);
        firstTouch = Arrays.copyOf(firsts, cellCount);
        touchStart = new int[cellCount + 1];
        for (int i = 0; i < n; i++) {
            if (cellOf[i] >= 0) {
                touchStart[cellOf[i] + 1]++;
            }
        }
        for (int id = 0; id < cellCount; id++) {
            touchStart[id + 1] += touchStart[id];
        }
        touches = new int[touchStart[cellCount]];
        int[] fill = Arrays.copyOf(touchStart, cellCount);
        for (int i = 0; i < n; i++) {
            if (cellOf[i] >= 0) {
                touches[fill[cellOf[i]]++] = i;
            }
        }
    }

    public List<SearchEvent> events() {
        return events;
    }

    /** Number of expansions. Frames run from 0 to this value inclusive. */
    public int lastStep() {
        return expansionIndices.length;
    }

    public BlockPoint start() {
        return ((SearchEvent.Started) events.get(0)).start().pos();
    }

    public BlockPoint goal() {
        return ((SearchEvent.Started) events.get(0)).goal();
    }

    /** The search's state after {@code step} expansions. */
    public FrameState frame(int step) {
        if (step < 0 || step > lastStep()) {
            throw new IndexOutOfBoundsException("step " + step + " of " + lastStep());
        }
        int end = step < lastStep() ? expansionIndices[step] : events.size();
        BlockPoint current = step == 0 ? null
                : ((SearchEvent.Expanded) events.get(expansionIndices[step - 1])).node().pos();
        SearchResult result = end == events.size()
                && events.get(end - 1) instanceof SearchEvent.Finished f ? f.result() : null;
        Frame view = new Frame(end);
        return new FrameState(step, start(), goal(), view.nodes, view.open, view.closed,
                current, result);
    }

    /** The events before {@code end}, seen as the search stood then. */
    private final class Frame {
        final int end;
        final int known; // cells 0..known-1 had been touched
        final Map<BlockPoint, NodeView> nodes = new Nodes();
        final Set<BlockPoint> open = new Cells(OPEN);
        final Set<BlockPoint> closed = new Cells(CLOSED);

        Frame(int end) {
            this.end = end;
            int k = Arrays.binarySearch(firstTouch, end); // first touches are distinct
            known = k >= 0 ? k : -k - 1;
        }

        /** The last event before {@code end} that touched cell {@code id}, or -1. */
        int lastTouch(int id) {
            int lo = touchStart[id], hi = touchStart[id + 1] - 1, found = -1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (touches[mid] < end) {
                    found = touches[mid];
                    lo = mid + 1;
                } else {
                    hi = mid - 1;
                }
            }
            return found;
        }

        int lastTouch(Object p) {
            Integer id = ids.get(p);
            return id == null || id >= known ? -1 : lastTouch(id.intValue());
        }

        final class Nodes extends AbstractMap<BlockPoint, NodeView> {
            @Override
            public NodeView get(Object key) {
                int t = lastTouch(key);
                return t < 0 ? null : nodeAfter[t];
            }

            @Override
            public boolean containsKey(Object key) {
                return lastTouch(key) >= 0;
            }

            @Override
            public int size() {
                return known;
            }

            @Override
            public Set<Map.Entry<BlockPoint, NodeView>> entrySet() {
                return new AbstractSet<>() {
                    @Override
                    public Iterator<Map.Entry<BlockPoint, NodeView>> iterator() {
                        return new Iterator<>() {
                            int id;

                            @Override
                            public boolean hasNext() {
                                return id < known;
                            }

                            @Override
                            public Map.Entry<BlockPoint, NodeView> next() {
                                if (id >= known) {
                                    throw new NoSuchElementException();
                                }
                                int i = id++;
                                return Map.entry(cells[i], nodeAfter[lastTouch(i)]);
                            }
                        };
                    }

                    @Override
                    public int size() {
                        return known;
                    }
                };
            }
        }

        /** The cells whose state has {@code flag} set. */
        final class Cells extends AbstractSet<BlockPoint> {
            private final byte flag;

            Cells(byte flag) {
                this.flag = flag;
            }

            @Override
            public boolean contains(Object o) {
                int t = lastTouch(o);
                return t >= 0 && (stateAfter[t] & flag) != 0;
            }

            @Override
            public int size() {
                return (flag == OPEN ? openAfter : closedAfter)[end - 1];
            }

            /** The first cell from {@code id} on that has the flag, or {@code known}. */
            private int firstFrom(int id) {
                while (id < known && (stateAfter[lastTouch(id)] & flag) == 0) {
                    id++;
                }
                return id;
            }

            @Override
            public Iterator<BlockPoint> iterator() {
                return new Iterator<>() {
                    int id = firstFrom(0);

                    @Override
                    public boolean hasNext() {
                        return id < known;
                    }

                    @Override
                    public BlockPoint next() {
                        if (id >= known) {
                            throw new NoSuchElementException();
                        }
                        BlockPoint p = cells[id];
                        id = firstFrom(id + 1);
                        return p;
                    }
                };
            }
        }
    }
}
