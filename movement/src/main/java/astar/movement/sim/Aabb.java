package astar.movement.sim;

/** An axis-aligned box in world coordinates, like the game's {@code Box}. */
public record Aabb(double minX, double minY, double minZ, double maxX, double maxY,
        double maxZ) {

    public Aabb offset(double x, double y, double z) {
        return new Aabb(minX + x, minY + y, minZ + z, maxX + x, maxY + y, maxZ + z);
    }

    /** Grows the box in the direction of the movement only, as {@code Box.stretch} does. */
    public Aabb stretch(double x, double y, double z) {
        double x0 = minX, y0 = minY, z0 = minZ, x1 = maxX, y1 = maxY, z1 = maxZ;
        if (x < 0.0) {
            x0 += x;
        } else if (x > 0.0) {
            x1 += x;
        }
        if (y < 0.0) {
            y0 += y;
        } else if (y > 0.0) {
            y1 += y;
        }
        if (z < 0.0) {
            z0 += z;
        } else if (z > 0.0) {
            z1 += z;
        }
        return new Aabb(x0, y0, z0, x1, y1, z1);
    }

    public Aabb contract(double d) {
        return new Aabb(minX + d, minY + d, minZ + d, maxX - d, maxY - d, maxZ - d);
    }

    /** Whether the two overlap with some volume (touching faces don't count). */
    public boolean intersects(Aabb o) {
        return minX < o.maxX && maxX > o.minX && minY < o.maxY && maxY > o.minY
                && minZ < o.maxZ && maxZ > o.minZ;
    }
}
