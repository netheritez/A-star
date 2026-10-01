package astar.pathing;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

/** {@link Geometry#rowsNear}: the strip of cells holds every cell a line's body can reach. */
class GeometryTest {
    @Test
    void theStripHoldsEveryCellNearTheLine() {
        Random rng = new Random(51);
        int near = 0;
        for (int trial = 0; trial < 3000; trial++) {
            // Block centres as the smoother uses, and arbitrary points too.
            boolean centres = trial % 2 == 0;
            double ax = point(rng, centres);
            double az = point(rng, centres);
            double bx = trial % 7 == 0 ? ax : point(rng, centres);
            double bz = trial % 11 == 0 ? az : point(rng, centres);
            double r = new double[] {0.3, 0.35, 0.49, 0.5, 1.2}[rng.nextInt(5)];
            int minX = (int) Math.floor(Math.min(ax, bx) - r);
            int maxX = (int) Math.floor(Math.max(ax, bx) + r);
            int minZ = (int) Math.floor(Math.min(az, bz) - r);
            int maxZ = (int) Math.floor(Math.max(az, bz) + r);
            int[] rows = Geometry.rowsNear(ax, az, bx, bz, r, minX, maxX);
            for (int cx = minX; cx <= maxX; cx++) {
                for (int cz = minZ; cz <= maxZ; cz++) {
                    if (Geometry.crossesInterior(ax, az, bx, bz, cx, cz)
                            || Geometry.distanceToCell(ax, az, bx, bz, cx, cz) < r) {
                        near++;
                        int i = cx - minX;
                        String what = "(" + ax + ", " + az + ")-(" + bx + ", " + bz + ") r " + r
                                + " cell " + cx + ", " + cz;
                        assertTrue(cz >= rows[2 * i] && cz <= rows[2 * i + 1], what);
                    }
                }
            }
        }
        assertTrue(near > 10_000, "near " + near);
    }

    private static double point(Random rng, boolean centre) {
        return centre ? rng.nextInt(40) - 20 + 0.5 : rng.nextDouble() * 40 - 20;
    }
}
