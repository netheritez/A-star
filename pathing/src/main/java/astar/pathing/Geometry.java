package astar.pathing;

/**
 * 2D geometry between a segment (ax, az)-(bx, bz) and the unit cell [cx, cx+1] x [cz, cz+1].
 */
final class Geometry {
    private static final double EPS = 1e-9;

    private Geometry() {}

    /**
     * How much of the segment, as a share of its length, lies inside the cell; negative when it
     * misses the cell entirely (Liang-Barsky clipping).
     */
    static double insideShare(double ax, double az, double bx, double bz, int cx, int cz) {
        return clipEnd(ax, az, bx, bz, cx, cz) - clipStart(ax, az, bx, bz, cx, cz);
    }

    /** Where the segment enters the cell; +inf if a parallel edge misses. */
    private static double clipStart(double ax, double az, double bx, double bz, int cx, int cz) {
        double dx = bx - ax;
        double dz = bz - az;
        double t0 = 0;
        t0 = enter(t0, -dx, ax - cx);
        t0 = enter(t0, dx, cx + 1 - ax);
        t0 = enter(t0, -dz, az - cz);
        return enter(t0, dz, cz + 1 - az);
    }

    private static double enter(double t0, double p, double q) {
        if (p == 0) {
            return q < 0 ? Double.POSITIVE_INFINITY : t0;
        }
        return p < 0 ? Math.max(t0, q / p) : t0;
    }

    /** Where the segment leaves the cell; -inf if a parallel edge misses. */
    private static double clipEnd(double ax, double az, double bx, double bz, int cx, int cz) {
        double dx = bx - ax;
        double dz = bz - az;
        double t1 = 1;
        t1 = leave(t1, -dx, ax - cx);
        t1 = leave(t1, dx, cx + 1 - ax);
        t1 = leave(t1, -dz, az - cz);
        return leave(t1, dz, cz + 1 - az);
    }

    private static double leave(double t1, double p, double q) {
        if (p == 0) {
            return q < 0 ? Double.NEGATIVE_INFINITY : t1;
        }
        return p > 0 ? Math.min(t1, q / p) : t1;
    }

    /**
     * For each column of cells cx from minX to maxX, the rows cz that can come within r of
     * the segment: {@code rows[2 i]} to {@code rows[2 i + 1]} for column {@code minX + i}
     * (empty when the first is larger). Every cell the segment crosses, or passes within r of,
     * is in there, so a long line checks a strip of cells instead of its whole bounding box.
     */
    static int[] rowsNear(double ax, double az, double bx, double bz, double r, int minX,
            int maxX) {
        int columns = maxX - minX + 1;
        int[] rows = new int[2 * columns];
        double dx = bx - ax;
        for (int i = 0; i < columns; i++) {
            int cx = minX + i;
            // The part of the segment whose x is within r of the column.
            double t0 = 0;
            double t1 = 1;
            if (dx != 0) {
                double u = (cx - r - ax) / dx;
                double v = (cx + 1 + r - ax) / dx;
                t0 = Math.max(0, Math.min(u, v));
                t1 = Math.min(1, Math.max(u, v));
            }
            if (t0 > t1) {
                rows[2 * i] = 1;
                rows[2 * i + 1] = 0;
                continue;
            }
            double z0 = az + t0 * (bz - az);
            double z1 = az + t1 * (bz - az);
            rows[2 * i] = (int) Math.floor(Math.min(z0, z1) - r);
            rows[2 * i + 1] = (int) Math.floor(Math.max(z0, z1) + r);
        }
        return rows;
    }

    /** True if a non-zero length of the segment lies inside the cell (not just a corner). */
    static boolean crossesInterior(double ax, double az, double bx, double bz, int cx, int cz) {
        double share = insideShare(ax, az, bx, bz, cx, cz);
        if (share < 0) {
            return false;
        }
        double length = Math.hypot(bx - ax, bz - az);
        if (length == 0) {
            return ax > cx && ax < cx + 1 && az > cz && az < cz + 1;
        }
        return share * length > EPS;
    }

    /** The shortest distance between the segment and the cell; 0 if they touch. */
    static double distanceToCell(double ax, double az, double bx, double bz, int cx, int cz) {
        if (insideShare(ax, az, bx, bz, cx, cz) >= 0) {
            return 0;
        }
        double best = Math.min(pointToCell(ax, az, cx, cz), pointToCell(bx, bz, cx, cz));
        for (int corner = 0; corner < 4; corner++) {
            double px = cx + (corner & 1);
            double pz = cz + (corner >> 1);
            best = Math.min(best, pointToSegment(px, pz, ax, az, bx, bz));
        }
        return best;
    }

    static double pointToCell(double px, double pz, int cx, int cz) {
        double dx = Math.max(Math.max(cx - px, 0), px - (cx + 1));
        double dz = Math.max(Math.max(cz - pz, 0), pz - (cz + 1));
        return Math.hypot(dx, dz);
    }

    static double pointToSegment(double px, double pz, double ax, double az, double bx, double bz) {
        double dx = bx - ax;
        double dz = bz - az;
        double len2 = dx * dx + dz * dz;
        double t = len2 == 0 ? 0 : ((px - ax) * dx + (pz - az) * dz) / len2;
        t = Math.max(0, Math.min(1, t));
        return Math.hypot(px - (ax + t * dx), pz - (az + t * dz));
    }
}
