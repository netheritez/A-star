package astar.pathing;

import astar.core.CostModel;
import astar.core.Heuristic;

/**
 * A pathfinder kept for one world across many searches that start in different places, like
 * one /goto trip after another: its move graph is built once and patched after edits, and its
 * {@link Landmarks} are built once, in the background, then reused by every later search (and
 * rebuilt in the background after edits). Searches that come before the landmarks are ready
 * use the flat heuristic, so they're no slower than without them; paths are the same either way.
 *
 * <p>Use it from one thread at a time, like a pathfinder.
 */
public final class KeptPathfinder {
    private final WorldPathfinder base;
    private final AutoLandmarks landmarks;

    /**
     * @param flat the plain pathfinder: world, entity, costs and the flat heuristic; the graph
     *     and landmarks are built over its moves
     * @param landmarks how many landmarks; 0 for none
     */
    public KeptPathfinder(WorldPathfinder flat, int landmarks) {
        this.base = flat;
        this.landmarks = landmarks <= 0 ? null
                // The landmarks build the kept graph too, on the same pathfinder, so it's built once.
                : new AutoLandmarks(() -> base, base.heuristic(),
                        landmarks, AutoLandmarks.SYNC_LIMIT_MS, null).firstInBackground();
    }

    /**
     * A pathfinder for searches from {@code start} (packed): the kept move graph, made to
     * cover {@code start} first (patched now after edits), and the landmarks when they are
     * ready. Make a new one for each trip; it keeps working through edits on its own.
     *
     * <p>With landmarks, the first search doesn't wait for the graph either: it works out its
     * moves as it goes, as a new pathfinder would, while the graph and then the landmarks are
     * built in the background.
     */
    public WorldPathfinder forStart(long start) {
        if (landmarks == null) {
            if (base.usesGraph()) {
                base.graphCovering(start);
            }
            return base.withHeuristic(base.heuristic());
        }
        boolean noGraphYet = base.graphBuilds() == 0
                || landmarks.building() && base.graph() == null;
        if (noGraphYet || !base.usesGraph()) {
            // The search starts the background build (AutoLandmarks.prepare).
            return base.withHeuristic(landmarks).useGraph(false);
        }
        base.graphCovering(start);
        return base.withHeuristic(landmarks);
    }

    /**
     * Builds the move graph around {@code start} (packed) and then the landmarks, on this
     * thread, so the next searches find them ready: for warming up ahead of a search.
     */
    public void warm(long start) {
        if (!base.usesGraph()) {
            return;
        }
        base.graphCovering(start);
        if (landmarks != null) {
            landmarks.prepare(start, start);
            landmarks.await();
        }
    }

    /** Takes a graph and landmarks read back from a file ({@link NavCache#read}). */
    public void adopt(NavCache.Read read) {
        base.adopt(read.graph());
        if (landmarks != null && read.landmarks() != null) {
            landmarks.adopt(read.landmarks());
        }
    }

    /** The kept plain pathfinder. */
    public WorldPathfinder base() {
        return base;
    }

    /** The landmarks ready now, or null (none yet, being built, or out of date). */
    public Landmarks landmarks() {
        return landmarks == null ? null : landmarks.landmarks();
    }

    /** Whether landmarks are being built in the background. */
    public boolean buildingLandmarks() {
        return landmarks != null && landmarks.building();
    }

    /** How many landmark builds have been started. */
    public int landmarkBuilds() {
        return landmarks == null ? 0 : landmarks.builds();
    }

    /** The costs searches use. */
    public CostModel costs() {
        return base.costs();
    }

    /** The flat heuristic. */
    public Heuristic flat() {
        return base.heuristic();
    }
}
