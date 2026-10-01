package astar.pathing;

import astar.core.Heuristic;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * {@link Landmarks} that look after themselves: built the first time a search needs them, and
 * built again when the world changes or a search starts somewhere they don't cover. Until they
 * are ready a search uses the flat heuristic, so paths never change, only how many nodes a
 * search takes.
 *
 * <p>After an edit, the same landmarks are worked out again over the move graph patched for it
 * ({@link NavGraph#patch}, {@link Landmarks#rebuild}): no flood and no picking, and every table
 * at once on all cores. A build that took no more than {@code syncLimitMs} last time (and the
 * very first one) runs right there, before the search. A slower one (a big map, after an edit) runs in the
 * background on a fresh pathfinder, and the searches in between use the flat heuristic; when it
 * is done, {@code onReady} is called (on the background thread), so the owner can search again.
 *
 * <p>Each search keeps the landmarks it started with ({@link #prepare}), so its estimates stay
 * consistent. Use it from one thread at a time, like a pathfinder.
 */
public final class AutoLandmarks implements Heuristic {
    /** How many landmarks the route tool and the editor use. */
    public static final int DEFAULT_COUNT = 16;
    /** Builds up to this long happen before the search that needs them; slower ones in the background. */
    public static final double SYNC_LIMIT_MS = 60;

    private final Supplier<WorldPathfinder> builder;
    private final Heuristic flat;
    private final int count;
    private final double syncLimitMs;
    private final Runnable onReady;

    private volatile Landmarks ready;
    private CompletableFuture<Landmarks> pending;
    private long lastSeed = Long.MIN_VALUE;
    private int lastSeedVersion;
    private Heuristic current;
    private int builds;
    private boolean firstInBackground;

    /**
     * @param builder makes a pathfinder over the same world, entity and costs (turn costs
     *     included, so exact ones get {@link TurnLandmarks}), with the flat heuristic: the
     *     landmarks are worked out over its moves. Background builds call it
     *     from another thread, so it must make a new one each time.
     * @param flat the heuristic to fall back on, and the floor under the landmark bounds
     */
    public AutoLandmarks(Supplier<WorldPathfinder> builder, Heuristic flat, int count,
            double syncLimitMs, Runnable onReady) {
        if (count < 1) {
            throw new IllegalArgumentException("count must be at least 1");
        }
        this.builder = builder;
        this.flat = flat;
        this.count = count;
        this.syncLimitMs = syncLimitMs;
        this.onReady = onReady == null ? () -> {} : onReady;
    }

    public AutoLandmarks(Supplier<WorldPathfinder> builder, Heuristic flat) {
        this(builder, flat, DEFAULT_COUNT, SYNC_LIMIT_MS, null);
    }

    /**
     * Builds the very first landmarks in the background too, so no search waits for them: for
     * an owner that searches many times on one world, like /goto.
     */
    public synchronized AutoLandmarks firstInBackground() {
        firstInBackground = true;
        return this;
    }

    /** The landmarks in use, or null when there are none yet or they are out of date. */
    public synchronized Landmarks landmarks() {
        adoptFinished();
        Landmarks lm = ready;
        return lm != null && !lm.stale() ? lm : null;
    }

    /** Takes landmarks made elsewhere ({@link NavCache#read}) as the ones in use. */
    public synchronized void adopt(Landmarks lm) {
        ready = lm;
    }

    /** Waits for a background build, if one is running, and takes it up. */
    public void await() {
        CompletableFuture<Landmarks> p;
        synchronized (this) {
            p = pending;
        }
        if (p != null) {
            p.join();
        }
        landmarks();
    }

    /** Whether a background build is running. */
    public synchronized boolean building() {
        return pending != null && !pending.isDone();
    }

    /** How many builds have been started. */
    public synchronized int builds() {
        return builds;
    }

    @Override
    public synchronized void prepare(long start, long goal) {
        adoptFinished();
        Landmarks lm = ready;
        boolean usable = lm != null && !lm.stale() && lm.covers(start);
        if (!usable && pending == null) {
            WorldPathfinder finder = builder.get();
            int version = finder.worldVersion();
            // Once per start and world version: a start nowhere near anything stays uncovered.
            if (start != lastSeed || version != lastSeedVersion) {
                lastSeed = start;
                lastSeedVersion = version;
                builds++;
                // After an edit, the same landmarks over the patched graph; else afresh.
                Landmarks old = lm;
                Supplier<Landmarks> work = old != null && old.stale()
                        ? () -> rebuild(old, start)
                        : () -> Landmarks.build(builder.get(), start, count);
                if (lm == null ? !firstInBackground : lm.buildMs() <= syncLimitMs) {
                    lm = work.get();
                    ready = lm;
                    usable = !lm.stale() && lm.covers(start);
                } else {
                    pending = CompletableFuture.supplyAsync(work);
                    pending.thenRun(onReady);
                }
            }
        }
        current = usable ? lm.heuristic(flat) : null;
        if (current != null) {
            current.prepare(start, goal);
        } else {
            flat.prepare(start, goal);
        }
    }

    /** Takes up a finished background build. */
    private void adoptFinished() {
        if (pending != null && pending.isDone()) {
            Landmarks done = pending.getNow(null);
            pending = null;
            if (done != null) {
                ready = done;
            }
        }
    }

    /** The old landmarks over their graph patched for the edits, or built afresh if it can't be. */
    private Landmarks rebuild(Landmarks old, long start) {
        NavGraph patched = old.graph().patch();
        if (patched == null || !patched.covers(start)) {
            return Landmarks.build(builder.get(), start, count);
        }
        return old.rebuild(patched);
    }

    @Override
    public double between(long a, long b) {
        Heuristic h = current;
        return h != null ? h.between(a, b) : flat.between(a, b);
    }

    @Override
    public double between(astar.core.MoveGraph graph, int cell, long a, long b) {
        Heuristic h = current;
        return h != null ? h.between(graph, cell, a, b) : flat.between(graph, cell, a, b);
    }

    @Override
    public double between(long a, int heading, long b) {
        Heuristic h = current;
        return h != null ? h.between(a, heading, b) : flat.between(a, heading, b);
    }

    @Override
    public double estimate(int dx, int dy, int dz) {
        return flat.estimate(dx, dy, dz);
    }
}
