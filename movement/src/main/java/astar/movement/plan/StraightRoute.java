package astar.movement.plan;

import astar.core.BlockPoint;
import astar.core.MoveSource;
import astar.core.MoveType;
import astar.core.PathStep;
import astar.pathing.EntityProfile;
import astar.pathing.MoveValidator;
import astar.pathing.PathSmoother;
import astar.pathing.Waypoint;
import astar.pathing.WorldPathfinder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A grid path laid along its smoothed lines, for {@link PlanBuilder}: where smoothing joined
 * a flat stretch into one straight line, the steps become the cells that line crosses, one
 * after another, and each one's point to pass through is on the line (the middle of the line's
 * way across that cell) instead of the cell's centre. The executor steers from point to point,
 * so it walks the straight line at whatever angle instead of the grid's zigzag.
 *
 * <p>Every other step (jumps, drops, swims, climbs, and the ends of each line) keeps its cell
 * and its centre. A line whose cells aren't each one grid move from the last (it passes exactly
 * through a corner where the grid can't cut across) keeps its grid steps, so the route can
 * still be checked move by move as it's walked.
 *
 * @param steps the cells to pass through, each reached from the one before by a grid move
 * @param x for each step, the point to pass through (x)
 * @param z for each step, the point to pass through (z)
 */
public record StraightRoute(List<PathStep> steps, double[] x, double[] z) {

    /** Room a line should leave the body on each side, beyond its own width. */
    public static final double MARGIN = 0.2;

    /** How far inside its cell a point on a line is kept, so it isn't on a cell's edge. */
    private static final double INSET = 1e-4;
    private static final double EPS = 1e-9;

    public StraightRoute {
        steps = List.copyOf(steps);
    }

    /**
     * Smoothing for the executor to walk: its lines leave the body {@link #MARGIN} of room on
     * each side, as the player doesn't keep to a line exactly and one that only just clears a
     * wall has it bumping into it and swerving.
     */
    public static PathSmoother smoother(WorldPathfinder finder) {
        PathSmoother s = smoother(finder.validator());
        return finder.keepsRoom() ? s.keepingRoom(finder.clearance()) : s;
    }

    /** As above, for paths searched without a wall cost. */
    public static PathSmoother smoother(MoveValidator v) {
        EntityProfile p = v.profile();
        double width = Math.min(1, p.width() + 2 * MARGIN);
        return new PathSmoother(new MoveValidator(v.world(), new EntityProfile(p.height(),
                p.stepHeight(), p.jumpHeight(), p.maxDrop(), width, p.opensDoors(),
                p.diagonalLeaps()))).overSteps();
    }

    /**
     * Lays {@code path} along {@code waypoints} (its smoothed form: each waypoint is one of its
     * steps, in order).
     *
     * @param moves the grid moves, to check each line's cells are walked one move at a time
     */
    public static StraightRoute of(List<PathStep> path, List<Waypoint> waypoints,
            MoveSource moves) {
        List<PathStep> steps = new ArrayList<>(path.size());
        List<double[]> points = new ArrayList<>(path.size());
        if (path.isEmpty()) {
            return new StraightRoute(steps, new double[0], new double[0]);
        }
        add(steps, points, path.get(0), centre(path.get(0).pos()));
        int from = 0;
        for (int k = 1; k < waypoints.size(); k++) {
            BlockPoint target = waypoints.get(k).pos();
            int to = from + 1;
            while (to < path.size() && !path.get(to).pos().equals(target)) {
                to++;
            }
            if (to == path.size()) {
                // Not the path's own smoothing: follow the grid for the rest.
                for (int i = from + 1; i < path.size(); i++) {
                    add(steps, points, path.get(i), centre(path.get(i).pos()));
                }
                return build(steps, points);
            }
            List<PathStep> line = to > from + 1 && waypoints.get(k).kind() == Waypoint.Kind.STRAIGHT
                    ? line(path.get(from).pos(), target, moves) : null;
            if (line == null) {
                for (int i = from + 1; i <= to; i++) {
                    add(steps, points, path.get(i), centre(path.get(i).pos()));
                }
            } else {
                BlockPoint a = path.get(from).pos();
                for (int i = 0; i < line.size(); i++) {
                    PathStep s = line.get(i);
                    add(steps, points, s, i == line.size() - 1 ? centre(target)
                            : onLine(a, target, s.pos()));
                }
            }
            from = to;
        }
        for (int i = from + 1; i < path.size(); i++) {
            add(steps, points, path.get(i), centre(path.get(i).pos()));
        }
        return build(steps, points);
    }

    /**
     * The cells the straight line between two cells' centres crosses, after {@code a}, up to and
     * including {@code b}, each in the layer the grid walks to (up or down a slab or a stair);
     * null if one of them isn't a grid walk from the one before.
     */
    static List<PathStep> line(BlockPoint a, BlockPoint b, MoveSource moves) {
        double ax = a.x() + 0.5;
        double az = a.z() + 0.5;
        double dx = b.x() - a.x();
        double dz = b.z() - a.z();
        // Where the line crosses each grid line: the cells lie between those.
        List<Double> ts = new ArrayList<>();
        ts.add(0.0);
        ts.add(1.0);
        crossings(ax, dx, ts);
        crossings(az, dz, ts);
        double[] t = ts.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        List<int[]> columns = new ArrayList<>();
        int lastX = a.x();
        int lastZ = a.z();
        for (int i = 0; i + 1 < t.length; i++) {
            if (t[i + 1] - t[i] < EPS) {
                continue;
            }
            double mid = (t[i] + t[i + 1]) / 2;
            int cx = (int) Math.floor(ax + mid * dx);
            int cz = (int) Math.floor(az + mid * dz);
            if (cx == lastX && cz == lastZ) {
                continue;
            }
            if (Math.abs(cx - lastX) > 1 || Math.abs(cz - lastZ) > 1) {
                return null;
            }
            columns.add(new int[] {cx, cz});
            lastX = cx;
            lastZ = cz;
        }
        List<PathStep> out = new ArrayList<>();
        int[] budget = {64 + 4 * columns.size()};
        return steps(columns, 0, a, b, moves, out, budget) ? out : null;
    }

    /**
     * Walks {@code columns} from {@code k} on, from {@code last}: a line over slabs and stairs
     * steps from layer to layer, and the grid's walks say which layer (a stair's cell has two
     * floors, its step and its back, so a wrong pick is taken back).
     */
    private static boolean steps(List<int[]> columns, int k, BlockPoint last, BlockPoint b,
            MoveSource moves, List<PathStep> out, int[] budget) {
        if (k == columns.size()) {
            return last.equals(b);
        }
        if (--budget[0] < 0) {
            return false;
        }
        int cx = columns.get(k)[0];
        int cz = columns.get(k)[1];
        MoveType type = Math.abs(cx - last.x()) + Math.abs(cz - last.z()) == 2
                ? MoveType.DIAGONAL : MoveType.WALK;
        for (int dy : new int[] {0, -1, 1}) {
            BlockPoint next = new BlockPoint(cx, last.y() + dy, cz);
            if (isWalk(moves, last, next)) {
                out.add(new PathStep(next, type));
                if (steps(columns, k + 1, next, b, moves, out, budget)) {
                    return true;
                }
                out.remove(out.size() - 1);
            }
        }
        return false;
    }

    private static void crossings(double start, double d, List<Double> ts) {
        if (Math.abs(d) < EPS) {
            return;
        }
        double end = start + d;
        for (double g = Math.ceil(Math.min(start, end)); g <= Math.max(start, end); g++) {
            double t = (g - start) / d;
            if (t > EPS && t < 1 - EPS) {
                ts.add(t);
            }
        }
    }

    /** Whether the grid walks (or steps diagonally) from one cell to the other. */
    private static boolean isWalk(MoveSource moves, BlockPoint from, BlockPoint to) {
        long target = to.pack();
        boolean[] found = {false};
        moves.moves(from.pack(), (next, type) -> found[0] |= next == target
                && (type == MoveType.WALK || type == MoveType.DIAGONAL));
        return found[0];
    }

    /** The middle of the line's way across cell c, kept a little inside it. */
    private static double[] onLine(BlockPoint a, BlockPoint b, BlockPoint c) {
        double ax = a.x() + 0.5;
        double az = a.z() + 0.5;
        double dx = b.x() - a.x();
        double dz = b.z() - a.z();
        // The part of the line inside the cell, as a range of t.
        double lo = 0;
        double hi = 1;
        double[] r = clip(ax, dx, c.x(), lo, hi);
        r = clip(az, dz, c.z(), r[0], r[1]);
        double mid = (r[0] + r[1]) / 2;
        double x = ax + mid * dx;
        double z = az + mid * dz;
        return new double[] {
            Math.max(c.x() + INSET, Math.min(c.x() + 1 - INSET, x)),
            Math.max(c.z() + INSET, Math.min(c.z() + 1 - INSET, z))};
    }

    private static double[] clip(double start, double d, int cell, double lo, double hi) {
        if (Math.abs(d) < EPS) {
            return new double[] {lo, hi};
        }
        double t0 = (cell - start) / d;
        double t1 = (cell + 1 - start) / d;
        return new double[] {Math.max(lo, Math.min(t0, t1)), Math.min(hi, Math.max(t0, t1))};
    }

    private static double[] centre(BlockPoint c) {
        return new double[] {c.x() + 0.5, c.z() + 0.5};
    }

    private static void add(List<PathStep> steps, List<double[]> points, PathStep s,
            double[] p) {
        steps.add(s);
        points.add(p);
    }

    private static StraightRoute build(List<PathStep> steps, List<double[]> points) {
        double[] x = new double[points.size()];
        double[] z = new double[points.size()];
        for (int i = 0; i < x.length; i++) {
            x[i] = points.get(i)[0];
            z[i] = points.get(i)[1];
        }
        return new StraightRoute(steps, x, z);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof StraightRoute r && steps.equals(r.steps) && Arrays.equals(x, r.x)
                && Arrays.equals(z, r.z);
    }

    @Override
    public int hashCode() {
        return steps.hashCode() * 31 + Arrays.hashCode(x) * 7 + Arrays.hashCode(z);
    }
}
