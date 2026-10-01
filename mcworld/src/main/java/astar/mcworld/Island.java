package astar.mcworld;

/**
 * A cluster of touching non-empty chunks, with the bounding box of its non-air blocks, in
 * world coordinates (inclusive).
 *
 * @param location where its world is inside the folder or zip that was scanned, as a relative
 *     path to the region folder ({@code "region"} for a plain world folder)
 */
public record Island(int index, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
        long blocks, int chunks, String location) {

    public int sizeX() {
        return maxX - minX + 1;
    }

    public int sizeY() {
        return maxY - minY + 1;
    }

    public int sizeZ() {
        return maxZ - minZ + 1;
    }

    /** The world folder's name, when the scan found more than one world; otherwise empty. */
    public String worldLabel() {
        String parent = location.contains("/") ? location.substring(0, location.lastIndexOf('/')) : "";
        return parent.isEmpty() ? "" : parent;
    }

    @Override
    public String toString() {
        String where = worldLabel().isEmpty() ? "" : " in " + worldLabel();
        return String.format("Island %d%s: %d x %d x %d blocks at x %d..%d, y %d..%d, z %d..%d"
                        + " (%,d blocks in %d chunks)",
                index, where, sizeX(), sizeY(), sizeZ(), minX, maxX, minY, maxY, minZ, maxZ,
                blocks, chunks);
    }
}
