package astar.pathing;

import astar.core.Pos;

/**
 * Splits a world of {@code sizeX x sizeY x sizeZ} cells into clusters for hierarchical search:
 * square columns of {@code size x size} blocks, the world's full height (16, like a Minecraft
 * chunk, by default). Cluster ids run from 0 to {@link #count()} - 1, row by row along x.
 *
 * <p>Every move goes to a neighbouring column or stays in its own, so a move leaves its cluster
 * only from a column on the cluster's edge, and never skips over a cluster.
 */
public record ClusterLayout(int size, int sizeX, int sizeY, int sizeZ) {
    public static final int DEFAULT_SIZE = 16;

    public ClusterLayout {
        if (size < 2) {
            throw new IllegalArgumentException("clusters must be at least 2 blocks across");
        }
        if (sizeX < 1 || sizeY < 1 || sizeZ < 1) {
            throw new IllegalArgumentException("the world must have at least one cell");
        }
        if ((long) size * size * sizeY > Integer.MAX_VALUE - 8) {
            throw new IllegalArgumentException("clusters of " + size + " blocks are too big for a "
                    + sizeY + "-tall world");
        }
    }

    public static ClusterLayout of(ArrayBlockView world, int size) {
        return new ClusterLayout(size, world.sizeX(), world.sizeY(), world.sizeZ());
    }

    /** Clusters along x. */
    public int countX() {
        return (sizeX + size - 1) / size;
    }

    /** Clusters along z. */
    public int countZ() {
        return (sizeZ + size - 1) / size;
    }

    public int count() {
        return countX() * countZ();
    }

    /** The cluster holding column (x, z), which must be inside the world. */
    public int clusterOf(int x, int z) {
        return (z / size) * countX() + x / size;
    }

    public int clusterOf(long pos) {
        return clusterOf(Pos.x(pos), Pos.z(pos));
    }

    public boolean inWorld(int x, int z) {
        return x >= 0 && x < sizeX && z >= 0 && z < sizeZ;
    }

    /** The cluster's first column along x. */
    public int minX(int cluster) {
        return (cluster % countX()) * size;
    }

    /** The cluster's first column along z. */
    public int minZ(int cluster) {
        return (cluster / countX()) * size;
    }

    /** One past the cluster's last column along x (clusters on the far edge may be narrower). */
    public int endX(int cluster) {
        return Math.min(sizeX, minX(cluster) + size);
    }

    /** One past the cluster's last column along z. */
    public int endZ(int cluster) {
        return Math.min(sizeZ, minZ(cluster) + size);
    }

    public boolean contains(int cluster, int x, int z) {
        int x0 = minX(cluster);
        int z0 = minZ(cluster);
        return x >= x0 && x < x0 + size && z >= z0 && z < z0 + size && inWorld(x, z);
    }

    public boolean contains(int cluster, long pos) {
        return contains(cluster, Pos.x(pos), Pos.z(pos));
    }

    /** Whether column (x, z) is on the edge of its cluster, where moves can leave it. */
    public boolean onEdge(int x, int z) {
        int c = clusterOf(x, z);
        return x == minX(c) || x == endX(c) - 1 || z == minZ(c) || z == endZ(c) - 1;
    }

    /** Cells in one full-size cluster: the size of a cluster-local array. */
    public int cellsPerCluster() {
        return size * size * sizeY;
    }

    /** The index of a cell inside its cluster, in [0, {@link #cellsPerCluster()}). */
    public int localIndex(int cluster, long pos) {
        int lx = Pos.x(pos) - minX(cluster);
        int lz = Pos.z(pos) - minZ(cluster);
        return (Pos.y(pos) * size + lz) * size + lx;
    }
}
