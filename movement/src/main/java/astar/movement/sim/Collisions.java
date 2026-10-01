package astar.movement.sim;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Box collision against blocks, as the game does it in {@code VoxelShapes.calculateMaxOffset},
 * {@code BlockCollisionSpliterator} and {@code CollisionView.findSupportingBlockPos}.
 */
public final class Collisions {

    private static final double EPSILON = 1.0E-7;

    private Collisions() {}

    /** One block's collision shape, moved to where the block is. */
    record Shape(int x, int y, int z, List<Aabb> boxes, double[] pointsY) {}

    /** Whether no block's shape overlaps the box. */
    public static boolean isSpaceEmpty(SimWorld world, Aabb box) {
        // The same test as blocksIn(world, box, true).isEmpty(), without making any lists or
        // boxes: this is the plan builder's hottest check.
        int x0 = floor(box.minX() - EPSILON) - 1, x1 = floor(box.maxX() + EPSILON) + 1;
        int y0 = floor(box.minY() - EPSILON) - 1, y1 = floor(box.maxY() + EPSILON) + 1;
        int z0 = floor(box.minZ() - EPSILON) - 1, z1 = floor(box.maxZ() + EPSILON) + 1;
        for (int y = y0; y <= y1; y++) {
            for (int z = z0; z <= z1; z++) {
                for (int x = x0; x <= x1; x++) {
                    List<Aabb> boxes = world.block(x, y, z).boxes();
                    for (int i = 0, n = boxes.size(); i < n; i++) {
                        Aabb a = boxes.get(i);
                        if (a.minX() + x < box.maxX() && a.maxX() + x > box.minX()
                                && a.minY() + y < box.maxY() && a.maxY() + y > box.minY()
                                && a.minZ() + z < box.maxZ() && a.maxZ() + z > box.minZ()) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    /**
     * Every block box that {@link #isSpaceEmpty} could find for a box inside {@code region},
     * moved to where its block is, as {@code minX, minY, minZ, maxX, maxY, maxZ} runs of six.
     * For testing many boxes in one small area ({@link #isSpaceEmpty(double[], int, double,
     * double, double, double, double, double)}) without reading the blocks for each.
     */
    public static double[] boxesNear(SimWorld world, Aabb region, double[] into, int[] count) {
        int x0 = floor(region.minX() - EPSILON) - 1, x1 = floor(region.maxX() + EPSILON) + 1;
        int y0 = floor(region.minY() - EPSILON) - 1, y1 = floor(region.maxY() + EPSILON) + 1;
        int z0 = floor(region.minZ() - EPSILON) - 1, z1 = floor(region.maxZ() + EPSILON) + 1;
        double[] out = into;
        int n = 0;
        for (int y = y0; y <= y1; y++) {
            for (int z = z0; z <= z1; z++) {
                for (int x = x0; x <= x1; x++) {
                    List<Aabb> boxes = world.block(x, y, z).boxes();
                    for (int i = 0, m = boxes.size(); i < m; i++) {
                        if (6 * (n + 1) > out.length) {
                            out = Arrays.copyOf(out, Math.max(64, 2 * out.length));
                        }
                        Aabb a = boxes.get(i);
                        int k = 6 * n++;
                        out[k] = a.minX() + x;
                        out[k + 1] = a.minY() + y;
                        out[k + 2] = a.minZ() + z;
                        out[k + 3] = a.maxX() + x;
                        out[k + 4] = a.maxY() + y;
                        out[k + 5] = a.maxZ() + z;
                    }
                }
            }
        }
        count[0] = n;
        return out;
    }

    /**
     * Whether none of the first {@code n} boxes from {@link #boxesNear} overlaps the box: the
     * same answer as {@link #isSpaceEmpty(SimWorld, Aabb)} for a box inside their region.
     */
    public static boolean isSpaceEmpty(double[] boxes, int n, double minX, double minY,
            double minZ, double maxX, double maxY, double maxZ) {
        for (int k = 0, end = 6 * n; k < end; k += 6) {
            if (boxes[k] < maxX && boxes[k + 3] > minX && boxes[k + 1] < maxY
                    && boxes[k + 4] > minY && boxes[k + 2] < maxZ && boxes[k + 5] > minZ) {
                return false;
            }
        }
        return true;
    }

    /** The shapes of the blocks that overlap the box. */
    static List<Shape> shapes(SimWorld world, Aabb box) {
        return blocksIn(world, box, false);
    }

    private static List<Shape> blocksIn(SimWorld world, Aabb box, boolean firstOnly) {
        List<Shape> found = new ArrayList<>();
        int x0 = floor(box.minX() - EPSILON) - 1, x1 = floor(box.maxX() + EPSILON) + 1;
        int y0 = floor(box.minY() - EPSILON) - 1, y1 = floor(box.maxY() + EPSILON) + 1;
        int z0 = floor(box.minZ() - EPSILON) - 1, z1 = floor(box.maxZ() + EPSILON) + 1;
        // The game walks x fastest, then z, then y (CuboidBlockIterator); the order matters
        // because each shape can only shorten the movement left after the ones before it.
        for (int y = y0; y <= y1; y++) {
            for (int z = z0; z <= z1; z++) {
                for (int x = x0; x <= x1; x++) {
                    SimBlock b = world.block(x, y, z);
                    if (b.isEmpty()) {
                        continue;
                    }
                    List<Aabb> boxes = new ArrayList<>(b.boxes().size());
                    boolean hit = false;
                    for (Aabb a : b.boxes()) {
                        Aabb w = a.offset(x, y, z);
                        boxes.add(w);
                        hit |= w.intersects(box);
                    }
                    if (!hit) {
                        continue;
                    }
                    found.add(new Shape(x, y, z, boxes, pointsY(b, y)));
                    if (firstOnly) {
                        return found;
                    }
                }
            }
        }
        return found;
    }

    private static double[] pointsY(SimBlock b, int y) {
        List<Double> points = b.pointsY();
        double[] out;
        if (points.isEmpty()) {
            out = b.boxes().stream().flatMapToDouble(a -> java.util.stream.DoubleStream.of(
                    a.minY(), a.maxY())).distinct().sorted().toArray();
        } else {
            out = points.stream().mapToDouble(Double::doubleValue).toArray();
        }
        for (int i = 0; i < out.length; i++) {
            out[i] += y;
        }
        return out;
    }

    /**
     * How far the box can move by (mx, my, mz) before it hits a shape, moving along y first,
     * then along x and z (z first when the movement is mostly along z).
     */
    static double[] collide(double mx, double my, double mz, Aabb box, List<Shape> shapes) {
        if (shapes.isEmpty()) {
            return new double[] {mx, my, mz};
        }
        double[] v = new double[3];
        int[] order = Math.abs(mx) < Math.abs(mz) ? new int[] {1, 2, 0} : new int[] {1, 0, 2};
        double[] m = {mx, my, mz};
        for (int axis : order) {
            double d = m[axis];
            if (d != 0.0) {
                v[axis] = maxOffset(axis, box.offset(v[0], v[1], v[2]), shapes, d);
            }
        }
        return v;
    }

    /** {@code VoxelShapes.calculateMaxOffset}, with each shape taken as its boxes. */
    private static double maxOffset(int axis, Aabb a, List<Shape> shapes, double maxDist) {
        int axis2 = (axis + 1) % 3;
        int axis3 = (axis + 2) % 3;
        for (Shape s : shapes) {
            if (Math.abs(maxDist) < EPSILON) {
                return 0.0;
            }
            for (Aabb b : s.boxes()) {
                if (!(max(b, axis2) > min(a, axis2) + EPSILON
                        && min(b, axis2) <= max(a, axis2) - EPSILON
                        && max(b, axis3) > min(a, axis3) + EPSILON
                        && min(b, axis3) <= max(a, axis3) - EPSILON)) {
                    continue;
                }
                if (maxDist > 0.0) {
                    if (min(b, axis) > max(a, axis) - EPSILON) {
                        double f = min(b, axis) - max(a, axis);
                        if (f >= -EPSILON) {
                            maxDist = Math.min(maxDist, f);
                        }
                    }
                } else if (maxDist < 0.0) {
                    if (max(b, axis) < min(a, axis) + EPSILON) {
                        double f = max(b, axis) - min(a, axis);
                        if (f <= EPSILON) {
                            maxDist = Math.max(maxDist, f);
                        }
                    }
                }
            }
        }
        return maxDist;
    }

    /**
     * {@code Entity.collectStepHeights}: the heights above the box's bottom that stepping up
     * tries, lowest first. {@code landingY} is the vertical movement already allowed.
     */
    static float[] stepHeights(Aabb box, List<Shape> shapes, float stepHeight, float landingY) {
        float[] out = new float[4];
        int n = 0;
        for (Shape s : shapes) {
            for (double d : s.pointsY()) {
                float g = (float) (d - box.minY());
                if (!(g < 0.0F) && g != landingY) {
                    if (g > stepHeight) {
                        break;
                    }
                    boolean seen = false;
                    for (int i = 0; i < n; i++) {
                        seen |= out[i] == g;
                    }
                    if (!seen) {
                        if (n == out.length) {
                            out = Arrays.copyOf(out, n * 2);
                        }
                        out[n++] = g;
                    }
                }
            }
        }
        out = Arrays.copyOf(out, n);
        Arrays.sort(out);
        return out;
    }

    /**
     * {@code CollisionView.findSupportingBlockPos}: of the blocks overlapping the box, the one
     * whose centre is nearest the position; ties go to the highest, then southmost, then
     * eastmost. Null when none overlaps.
     */
    static int[] supportingBlock(SimWorld world, Aabb box, double x, double y, double z) {
        int[] best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Shape s : blocksIn(world, box, false)) {
            double dx = s.x() + 0.5 - x;
            double dy = s.y() + 0.5 - y;
            double dz = s.z() + 0.5 - z;
            double e = dx * dx + dy * dy + dz * dz;
            if (e < bestDistance || e == bestDistance && (best == null || compare(best, s) < 0)) {
                best = new int[] {s.x(), s.y(), s.z()};
                bestDistance = e;
            }
        }
        return best;
    }

    /** {@code Vec3i.compareTo}: by y, then z, then x. */
    private static int compare(int[] a, Shape b) {
        if (a[1] != b.y()) {
            return Integer.compare(a[1], b.y());
        }
        if (a[2] != b.z()) {
            return Integer.compare(a[2], b.z());
        }
        return Integer.compare(a[0], b.x());
    }

    private static double min(Aabb b, int axis) {
        return axis == 0 ? b.minX() : axis == 1 ? b.minY() : b.minZ();
    }

    private static double max(Aabb b, int axis) {
        return axis == 0 ? b.maxX() : axis == 1 ? b.maxY() : b.maxZ();
    }

    private static int floor(double d) {
        return (int) Math.floor(d);
    }
}
