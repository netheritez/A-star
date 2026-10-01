package astar.pathing;

import astar.core.MoveSource;

/**
 * The moves of another {@link MoveSource} that stay inside one cluster: for running any search
 * that mustn't leave it, as {@link ClusterDijkstra} does on its own.
 */
public final class BoundedMoveSource implements MoveSource {
    private final MoveSource moves;
    private final ClusterLayout layout;
    private final int cluster;

    public BoundedMoveSource(MoveSource moves, ClusterLayout layout, int cluster) {
        this.moves = moves;
        this.layout = layout;
        this.cluster = cluster;
    }

    @Override
    public void moves(long from, MoveSink sink) {
        moves.moves(from, (to, type) -> {
            if (layout.contains(cluster, to)) {
                sink.accept(to, type);
            }
        });
    }
}
