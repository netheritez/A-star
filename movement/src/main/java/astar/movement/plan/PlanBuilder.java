package astar.movement.plan;

import astar.core.BlockPoint;
import astar.core.MoveType;
import astar.core.PathStep;
import astar.movement.plan.ExecutionPlan.Door;
import astar.movement.plan.ExecutionPlan.Edge;
import astar.movement.plan.ExecutionPlan.Kind;
import astar.movement.plan.ExecutionPlan.Node;
import astar.movement.plan.ExecutionPlan.Segment;
import astar.movement.plan.ExecutionPlan.Side;
import astar.movement.sim.Aabb;
import astar.movement.sim.Collisions;
import astar.movement.sim.SimBlock;
import astar.movement.sim.SimWorld;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds an {@link ExecutionPlan} from a grid path and the blocks around it.
 *
 * <p>The grid path says which cells to pass through and how each was reached. The plan adds
 * what moving a real player body through them needs:
 * <ul>
 *   <li><b>Floor heights</b>: the top of what's underfoot (a slab is half a block, soul sand
 *       is 14/16), so the follower knows where the feet will be.
 *   <li><b>Where to stand</b>: each node is the middle of its cell unless the body doesn't
 *       fit there or can't walk from one node to the next on a straight line (the back half
 *       of a stair is a full block high), in which case it moves as little as it takes, or to
 *       the corner of an L. A step it can't walk however it moves (up the back of a stair) is
 *       planned as a jump.
 *   <li><b>Room on each side</b>: how far the body can drift from the route before it bumps a
 *       wall or would fall off an edge, measured by moving the player's box sideways in small
 *       steps along each step of the route.
 *   <li><b>Doors</b> to open, found in the path's cells.
 *   <li><b>A speed profile</b>: a limit at each node from what comes next (a corner with a
 *       drop on the outside, a drop, a ladder, water, a door, the goal), then carried back
 *       along the route by how fast the player can slow down, so every limit can be met.
 * </ul>
 */
public final class PlanBuilder {

    /** How far to the side the room is measured; anything beyond counts as open. */
    public static final double MAX_WIDTH = 2.0;
    /** The step between sideways samples. */
    public static final double SAMPLE = 0.05;

    private static final double HALF_WIDTH = 0.3;
    private static final double HEIGHT = 1.8;
    private static final double STEP_HEIGHT = 0.6;
    /**
     * Speed lost per block while coasting on the ground: velocity keeps 0.546 of itself each
     * tick (friction 0.6 times drag 0.91), so slowing from v to u takes (v - u) / 0.454 blocks.
     * The plan uses less than that, leaving the follower some slack.
     */
    private static final double GROUND_BRAKE = 0.3;
    /** In the air velocity keeps 0.91 of itself each tick, so there's little braking. */
    private static final double AIR_BRAKE = 0.08;
    /** Coasting keeps drifting sideways by about 1.2 times the sideways speed. */
    private static final double DRIFT = 0.546 / (1 - 0.546);
    /** Turns sharper than this, in degrees, are corners. */
    private static final double CORNER = 10;
    /** Turns this sharp or sharper are taken at walking speed. */
    private static final double SHARP_CORNER = 60;
    private static final double CLIMB_SPEED = 0.12;
    private static final double SWIM_SPEED = 0.1;
    private static final double SLOWEST = 0.06;
    /** How much of the room inside a corner its curve may cut into. */
    static final double INSET_SHARE = 0.6;
    /** The furthest a curve starts before its corner, in blocks. */
    static final double MAX_TRIM = 3;
    /** Room the body keeps from walls going round a curve, beyond its own width. */
    private static final double CURVE_MARGIN = 0.1;

    private final SimWorld world;
    /**
     * How far off its cell's centre a node may stand ({@link #fits}): {@link #MAX_SHIFT} for
     * grid paths; anywhere in the cell for routes laid along lines, whose points are on the
     * line wherever it crosses the cell, even near its edge.
     */
    private double band = MAX_SHIFT;
    /** The blocks walks have read, by position ({@link #blockSlot}). */
    private long[] slotKeys = new long[1 << 12];
    private boolean[] slotUsed = new boolean[1 << 12];
    private int[] slotStart = new int[1 << 12];
    private int[] slotCount = new int[1 << 12];
    private int slotsFilled;
    private double[] slotBoxes = new double[6 * 1024];
    private int slotBoxCount;
    /** The boxes round the points {@link #overEdge} tests. */
    private double[] edgeNear = new double[0];
    /** Scratch for {@link #walk}: the bottoms and tops of the boxes under the body. */
    private double[] around = new double[64];
    /** The boxes {@link #side} tests for walls, underfoot and a step up, and how many. */
    private double[] wall = new double[0];
    private double[] foot = new double[0];
    private double[] low = new double[0];
    private int wallCount;
    private int footCount;
    private int lowCount;
    /** Which of the boxes underfoot the last place {@link #side} tested stood on. */
    private int footHit;
    /**
     * The blocks {@link #boxesAround} last read, from (cellX0, cellY0, cellZ0) to (cellX1,
     * cellY1, cellZ1), and their boxes: min and max x, z and y, in world coordinates.
     */
    private int cellX0 = Integer.MIN_VALUE;
    private int cellX1;
    private int cellY0;
    private int cellY1;
    private int cellZ0;
    private int cellZ1;
    private int cellBoxes;
    private double[] cells = new double[96];
    /**
     * The blocks round the node {@link #tighten} is trying to move, while it works ({@link
     * #near}): the columns from (nearX0, nearZ0) on, nearSX by nearSZ of them, each from
     * nearY0 up for nearSY blocks. A column is read from the world the first time a walk
     * meets it, and its boxes kept in {@link #nearBoxes} as {@link #cells} holds them, block by
     * block from the bottom up, so the boxes of any run of its blocks are all together: from
     * {@code nearLevel[(nearSY + 1) * column + level]} up to the same for the level after the
     * run. A column counts as read when its {@code nearRead} is {@link #nearStamp}, so moving
     * the region on forgets them all at once. Tightening tries each node many times, and its
     * walks cross the same few blocks over and over, so they're read once, and the blocks a
     * walk's footprint covers are then a few runs to copy instead of a block at a time.
     */
    private boolean near;
    private int nearX0;
    private int nearY0;
    private int nearZ0;
    private int nearSX;
    private int nearSY;
    private int nearSZ;
    private int nearStamp;
    private int[] nearRead = new int[0];
    private int[] nearLevel = new int[0];
    private double[] nearBoxes = new double[6 * 256];
    private int nearBoxCount;
    /** How many times a normal player's ground speed the player moves. */
    private final double speed;
    private boolean round = true;

    public PlanBuilder(SimWorld world) {
        this(world, 1);
    }

    /**
     * @param speed how many times a normal player's ground speed the player moves: its
     *     movement speed attribute over 0.1, so 2.4 with Speed VII
     */
    /**
     * Whether corners are rounded off with curves ({@link Curve}); on unless turned off here.
     * With them off, sharp corners are walked.
     */
    public PlanBuilder roundCorners(boolean round) {
        this.round = round;
        return this;
    }

    public PlanBuilder(SimWorld world, double speed) {
        if (!(speed > 0)) {
            throw new IllegalArgumentException("speed " + speed);
        }
        this.world = world;
        this.speed = speed;
    }

    /**
     * Builds the plan for a path.
     *
     * @throws IllegalArgumentException if two steps in a row aren't next to each other (or a
     *     level step one across and two along)
     */
    public ExecutionPlan build(List<PathStep> path) {
        return build(path, null, null);
    }

    /**
     * Builds the plan for a path laid along straight lines ({@link StraightRoute}): each node
     * starts at the route's point instead of its cell's centre, and directions (for the room on
     * each side and the corners) are read from the points, so a line across the grid at any
     * angle is one straight run, not a staircase of cells.
     */
    public ExecutionPlan build(StraightRoute route) {
        return build(route.steps(), route.x(), route.z());
    }

    private ExecutionPlan build(List<PathStep> path, double[] startX, double[] startZ) {
        cellX0 = Integer.MIN_VALUE; // the world may have changed since the last build
        clearSlots();
        nearSX = 0;
        int n = path.size();
        if (n == 0) {
            return new ExecutionPlan(List.of(), List.of(), speed, List.of());
        }
        for (int i = 1; i < n; i++) {
            BlockPoint a = path.get(i - 1).pos();
            BlockPoint b = path.get(i).pos();
            int dx = Math.abs(a.x() - b.x());
            int dz = Math.abs(a.z() - b.z());
            // A 16-way step, one across and two along, is on level ground.
            boolean knight = dx + dz == 3 && dx > 0 && dz > 0 && a.y() == b.y();
            if ((dx > 1 || dz > 1) && !knight) {
                throw new IllegalArgumentException("steps " + (i - 1) + " and " + i + " (" + a
                        + " and " + b + ") aren't next to each other");
            }
        }

        double[] floorY = new double[n];
        boolean[] hasFloor = new boolean[n];
        for (int i = 0; i < n; i++) {
            BlockPoint c = path.get(i).pos();
            double f = floor(c);
            hasFloor[i] = !Double.isNaN(f);
            floorY[i] = hasFloor[i] ? f : c.y();
        }

        double[] px = new double[n];
        double[] pz = new double[n];
        for (int i = 0; i < n; i++) {
            px[i] = startX != null ? startX[i] : path.get(i).pos().x() + 0.5;
            pz[i] = startZ != null ? startZ[i] : path.get(i).pos().z() + 0.5;
        }
        boolean lines = startX != null;
        band = lines ? 0.5 : MAX_SHIFT;
        if (lines) {
            // A line's point stands on what's under the point itself (the low half of a stair
            // it crosses, say), so the node stays on the line.
            for (int i = 0; i < n; i++) {
                BlockPoint c = path.get(i).pos();
                double f = hasFloor[i] ? floor(c, px[i] - c.x(), pz[i] - c.z()) : Double.NaN;
                if (!Double.isNaN(f)) {
                    floorY[i] = f;
                }
            }
        }
        settle(path, floorY, hasFloor, px, pz);
        boolean[] jump = fit(path, floorY, hasFloor, px, pz);
        if (lines) {
            tighten(path, floorY, hasFloor, jump, px, pz);
        }

        double[] distance = new double[n];
        for (int i = 1; i < n; i++) {
            BlockPoint a = path.get(i - 1).pos();
            BlockPoint b = path.get(i).pos();
            double flat = lines ? Math.hypot(px[i] - px[i - 1], pz[i] - pz[i - 1])
                    : Math.hypot(b.x() - a.x(), b.z() - a.z());
            distance[i] = distance[i - 1] + (flat > 0 ? flat : Math.abs(floorY[i] - floorY[i - 1]));
        }

        Side[] left = new Side[n];
        Side[] right = new Side[n];
        for (int i = 0; i < n; i++) {
            double[] d = lines ? direction(px, pz, i) : direction(path, i);
            // Left of the direction of travel, seen from above (x east, z south).
            double lx = d[1];
            double lz = -d[0];
            left[i] = side(path, floorY, hasFloor[i], px, pz, i, lx, lz);
            right[i] = side(path, floorY, hasFloor[i], px, pz, i, -lx, -lz);
        }

        Door[] doors = new Door[n];
        for (int i = 0; i < n; i++) {
            doors[i] = door(path.get(i).pos());
        }

        List<Curve> curves = round
                ? curves(path, floorY, hasFloor, jump, px, pz, left, right, doors)
                : List.of();
        boolean[] curved = new boolean[n];
        for (Curve c : curves) {
            // Only a level curve is taken at a sprint: one up or down steps, with the edge of
            // each to catch the feet, is walked, as a sharp corner is.
            curved[c.node()] = !stepped[c.node()];
        }
        double[] limit = speedLimits(path, floorY, hasFloor, jump, distance, left, right,
                doors, lines ? px : null, pz, curved);

        List<Node> nodes = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            BlockPoint c = path.get(i).pos();
            nodes.add(new Node(c, path.get(i).via(), px[i], floorY[i], pz[i],
                    hasFloor[i], distance[i], left[i], right[i], doors[i], limit[i]));
        }
        return new ExecutionPlan(nodes, segments(path, floorY, jump), speed, curves);
    }

    // ---- floors ----

    /**
     * The top of what the feet stand on in this cell: the highest collision box top in the
     * cell or the two below it that's no more than a step above the cell's bottom and above
     * the cell below's bottom. NaN when there's nothing (a ladder, water, the air).
     */
    private double floor(BlockPoint c) {
        return floor(c, Double.NaN, Double.NaN);
    }

    /** The same, from only the boxes under a point in the cell (lx, lz), when given. */
    private double floor(BlockPoint c, double lx, double lz) {
        double best = Double.NaN;
        for (int dy = -2; dy <= 0; dy++) {
            SimBlock b = world.block(c.x(), c.y() + dy, c.z());
            for (Aabb box : b.boxes()) {
                if (b.climbable() && (box.maxX() - box.minX() < 0.5
                        || box.maxZ() - box.minZ() < 0.5)) {
                    // A ladder's thin board holds no one up: the player slides down it.
                    // (Scaffolding's top is a whole floor.)
                    continue;
                }
                if (!Double.isNaN(lx) && (lx < box.minX() || lx > box.maxX() || lz < box.minZ()
                        || lz > box.maxZ())) {
                    continue;
                }
                double top = c.y() + dy + box.maxY();
                if (top > c.y() - 1 && top <= c.y() + STEP_HEIGHT
                        && (Double.isNaN(best) || top > best)) {
                    best = top;
                }
            }
        }
        return best;
    }

    // ---- fitting the body through ----

    /** How far off a cell's centre a node may be moved to let the body through. */
    private static final double MAX_SHIFT = 0.45;
    private static final double MARGIN = 0.1;
    /**
     * How high a lip beside a node the body may stand over: a carpet's edge (1/16), which the
     * feet just step onto. Otherwise a node next to a carpet counts as not fitting and is
     * pushed away from it, and a line over a carpet pattern zigzags.
     */
    private static final double LIP = 0.07;

    /** How far apart the walk between two nodes is checked. */
    private static final double WALK_STEP = 0.05;
    private static final double EPS = 1e-7;
    /** How far to either side of a fitted line the body must still get through. */
    private static final double SLACK = 0.1;

    /**
     * Moves nodes where the body can't walk the straight line from one to the next. Stepping
     * onto a stair from its side, the stair's back half is a full block high, so the body has
     * to keep to the low half's side of the line: both nodes (or one) move sideways by the
     * least that lets it through. Where no sideways move will do, one of the nodes moves to
     * the corner of an L instead, so the body climbs a stair's back half before turning where
     * a ceiling leaves no room to turn on the low half (a staircase that turns). A fitted line
     * must leave the body a little room to either side, as the follower doesn't keep to it
     * exactly.
     *
     * @return for each node, whether the step up or along to it can't be walked however the
     *     body is placed (onto the back of a stair, which is a full block high): it takes a jump
     */
    private boolean[] fit(List<PathStep> path, double[] floorY, boolean[] hasFloor,
            double[] px, double[] pz) {
        int n = path.size();
        boolean[] jump = new boolean[n];
        for (int i = 0; i + 1 < n; i++) {
            // Jumps and drops leave the ground; only walks are fitted.
            if (!hasFloor[i] || !hasFloor[i + 1] || kind(path.get(i + 1).via()) != Kind.STRAIGHT) {
                continue;
            }
            double dx = px[i + 1] - px[i];
            double dz = pz[i + 1] - pz[i];
            double len = Math.hypot(dx, dz);
            if (len < 1e-9 || reaches(px[i], floorY[i], pz[i], px[i + 1], floorY[i + 1],
                    pz[i + 1])) {
                continue;
            }
            if (!shift(path, floorY, hasFloor, px, pz, i, -dz / len, dx / len)
                    && !corner(path, floorY, hasFloor, px, pz, i)) {
                jump[i + 1] = floorY[i + 1] >= floorY[i];
            }
        }
        return jump;
    }

    /** Moves nodes i and i + 1 (or one of them) sideways along (ux, uz), if that helps. */
    private boolean shift(List<PathStep> path, double[] floorY, boolean[] hasFloor,
            double[] px, double[] pz, int i, double ux, double uz) {
        for (double s = SAMPLE; s <= MAX_SHIFT + 1e-9; s += SAMPLE) {
            for (int sign = 1; sign >= -1; sign -= 2) {
                // Both nodes, or just one of them (when the other already had to move).
                for (double[] w : new double[][] {{1, 1}, {1, 0}, {0, 1}}) {
                    if (!passes(path, floorY, hasFloor, px, pz, i, ux * s * sign,
                            uz * s * sign, w)) {
                        continue;
                    }
                    // A little more than just enough if there's room, so the follower, which
                    // closes in on the line without ever quite reaching it, isn't left grazing
                    // the block.
                    double m = Math.min(s + MARGIN, MAX_SHIFT);
                    if (!passes(path, floorY, hasFloor, px, pz, i, ux * m * sign,
                            uz * m * sign, w)) {
                        m = s;
                    }
                    px[i] += ux * m * sign * w[0];
                    pz[i] += uz * m * sign * w[0];
                    px[i + 1] += ux * m * sign * w[1];
                    pz[i + 1] += uz * m * sign * w[1];
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether moving node i by w[0] times (ox, oz) and node i + 1 by w[1] times it lets the
     * body walk from one to the other, without spoiling the walk from the node before.
     */
    private boolean passes(List<PathStep> path, double[] floorY, boolean[] hasFloor,
            double[] px, double[] pz, int i, double ox, double oz, double[] w) {
        double ax = px[i] + ox * w[0];
        double az = pz[i] + oz * w[0];
        double bx = px[i + 1] + ox * w[1];
        double bz = pz[i + 1] + oz * w[1];
        return fits(path, floorY[i], i, ax, az) && fits(path, floorY[i + 1], i + 1, bx, bz)
                && reachesWithSlack(ax, floorY[i], az, bx, floorY[i + 1], bz)
                && (w[0] == 0 || i == 0 || !hasFloor[i - 1]
                        || reachesWithSlack(px[i - 1], floorY[i - 1], pz[i - 1], ax, floorY[i],
                                az));
    }

    /**
     * Moves node i, or node i + 1, to the corner of an L between them (within its own cell),
     * standing on whatever the walk there reaches, if the body can walk both legs.
     */
    private boolean corner(List<PathStep> path, double[] floorY, boolean[] hasFloor,
            double[] px, double[] pz, int i) {
        double[][] corners = {{px[i], pz[i + 1]}, {px[i + 1], pz[i]}};
        for (int moved = i; moved <= i + 1; moved++) {
            BlockPoint c = path.get(moved).pos();
            for (double[] k : corners) {
                double x = c.x() + clamp(k[0] - c.x());
                double z = c.z() + clamp(k[1] - c.z());
                // The leg into the corner starts at the node before the moved one.
                int from = moved == i && i > 0 && hasFloor[i - 1] ? i - 1 : i;
                double y = walk(px[from], floorY[from], pz[from], x, z);
                if (Double.isNaN(y) || !fits(path, y, moved, x, z)
                        || !reachesWithSlack(px[from], floorY[from], pz[from], x, y, z)) {
                    continue;
                }
                if (moved == i
                        && !reachesWithSlack(x, y, z, px[i + 1], floorY[i + 1], pz[i + 1])) {
                    continue;
                }
                px[moved] = x;
                pz[moved] = z;
                floorY[moved] = y;
                return true;
            }
        }
        return false;
    }

    /** Whether the body walks from one point to the other and ends up standing near y1. */
    private boolean reaches(double x0, double y0, double z0, double x1, double y1, double z1) {
        double y = walk(x0, y0, z0, x1, z1);
        return !Double.isNaN(y) && Math.abs(y - y1) <= STEP_HEIGHT + 1e-9;
    }

    /**
     * {@link #reaches}, and still so a little to either side of the line: the follower cuts
     * corners and drifts, so a line that only just fits isn't one it can keep to.
     */
    private boolean reachesWithSlack(double x0, double y0, double z0, double x1, double y1,
            double z1) {
        double len = Math.hypot(x1 - x0, z1 - z0);
        if (!reaches(x0, y0, z0, x1, y1, z1)) {
            return false;
        }
        if (len < 1e-9) {
            return true;
        }
        double ox = -(z1 - z0) / len * SLACK;
        double oz = (x1 - x0) / len * SLACK;
        return reaches(x0 + ox, y0, z0 + oz, x1 + ox, y1, z1 + oz)
                && reaches(x0 - ox, y0, z0 - oz, x1 - ox, y1, z1 - oz);
    }

    /**
     * Walks the body in a straight line from (x0, y0, z0) to (x1, z1), in small steps: at each
     * it steps up onto anything a step high or less, steps down onto what's under it, and
     * stops at anything higher or at a ceiling that leaves no room for the body.
     *
     * @return the height it ends up standing at, or NaN if it can't get there
     */
    private double walk(double x0, double y0, double z0, double x1, double z1) {
        int steps = walkSteps(x1 - x0, z1 - z0);
        double y = y0;
        for (int k = 1; k <= steps; k++) {
            double x = x0 + (x1 - x0) * k / steps;
            double z = z0 + (z1 - z0) * k / steps;
            // Each box's bottom and top, in pairs: all walking needs of them.
            int n = boxesAround(x, z, y - 1.0, y + STEP_HEIGHT + HEIGHT);
            double[] b = around;
            double top = y;
            for (int i = 0; i < 2 * n; i += 2) {
                if (b[i + 1] > y + EPS && b[i] < y + HEIGHT - EPS) {
                    if (b[i + 1] > y + STEP_HEIGHT + EPS) {
                        return Double.NaN;
                    }
                    top = Math.max(top, b[i + 1]);
                }
            }
            if (top > y) {
                for (int i = 0; i < 2 * n; i += 2) {
                    if (b[i + 1] > top + EPS && b[i] < top + HEIGHT - EPS) {
                        return Double.NaN;
                    }
                }
                y = top;
                continue;
            }
            double under = Double.NaN;
            for (int i = 0; i < 2 * n; i += 2) {
                if (b[i + 1] <= y + EPS && (Double.isNaN(under) || b[i + 1] > under)) {
                    under = b[i + 1];
                }
            }
            if (Double.isNaN(under)) {
                return Double.NaN;
            }
            if (under == y && k < steps) {
                // On level ground: every later sample that meets the same boxes the same way
                // comes to the same height, so skip to the last of them.
                k = sameBoxesUntil(x0, z0, x1, z1, steps, k);
            }
            y = under;
        }
        return y;
    }

    /**
     * How many samples a walk across (dx, dz) takes: its length ({@link Math#hypot}) over
     * {@link #WALK_STEP}, rounded up, and at least one. A plain square root is much quicker
     * than hypot and differs from it by no more than a few units in the last place, so it
     * rounds up to the same whole number unless it's all but whole itself: only then is
     * hypot worked out.
     */
    private static int walkSteps(double dx, double dz) {
        double r = Math.sqrt(dx * dx + dz * dz) / WALK_STEP;
        if (Math.abs(r - Math.rint(r)) > 1e-9 * (1 + r)) {
            return Math.max(1, (int) Math.ceil(r));
        }
        return Math.max(1, (int) Math.ceil(Math.hypot(dx, dz) / WALK_STEP));
    }

    /**
     * The last sample of a walk, from sample k on, whose footprint meets the same blocks and
     * the same boxes of them, each on the same sides, as sample k's does (the last one {@link
     * #boxesAround} read): at least k. Each test is monotone along the line, so a test that
     * comes out the same at both ends comes out the same everywhere between them.
     */
    private int sameBoxesUntil(double x0, double z0, double x1, double z1, int steps, int k) {
        // First the last sample over the same blocks (which a search finds, the blocks under
        // the footprint only ever moving on along the line), then back from there to one whose
        // boxes are met alike.
        int lo = k;
        int hi = steps;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (sameBlocks(x0 + (x1 - x0) * mid / steps, z0 + (z1 - z0) * mid / steps)) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        double xk = x0 + (x1 - x0) * k / steps;
        double zk = z0 + (z1 - z0) * k / steps;
        int to = lo;
        while (to > k && !sameBoxes(xk, zk, x0 + (x1 - x0) * to / steps,
                z0 + (z1 - z0) * to / steps)) {
            to = k + (to - k) / 2;
        }
        return to;
    }

    /** Whether the footprint at (x, z) is over the blocks {@link #boxesAround} last read. */
    private boolean sameBlocks(double x, double z) {
        return (int) Math.floor(x - HALF_WIDTH) == cellX0
                && (int) Math.floor(x + HALF_WIDTH - EPS) == cellX1
                && (int) Math.floor(z - HALF_WIDTH) == cellZ0
                && (int) Math.floor(z + HALF_WIDTH - EPS) == cellZ1;
    }

    /** Whether the footprints at (ax, az) and (bx, bz) meet the boxes read alike. */
    private boolean sameBoxes(double ax, double az, double bx, double bz) {
        double[] c = cells;
        for (int k = 0, end = 6 * cellBoxes; k < end; k += 6) {
            if ((c[k] < ax + HALF_WIDTH - EPS) != (c[k] < bx + HALF_WIDTH - EPS)
                    || (c[k + 1] > ax - HALF_WIDTH + EPS) != (c[k + 1] > bx - HALF_WIDTH + EPS)
                    || (c[k + 2] < az + HALF_WIDTH - EPS) != (c[k + 2] < bz + HALF_WIDTH - EPS)
                    || (c[k + 3] > az - HALF_WIDTH + EPS)
                            != (c[k + 3] > bz - HALF_WIDTH + EPS)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The slot in the block cache for the block at (x, y, z), its boxes read from the world
     * the first time: in world coordinates, as {@link #cells} holds them, from {@code
     * slotStart} for {@code slotCount} boxes in {@link #slotBoxes}.
     */
    private int blockSlot(int x, int y, int z) {
        long key = ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | (y & 0xFFF);
        int mask = slotKeys.length - 1;
        int h = (int) (key * 0x9E3779B97F4A7C15L >>> 40) & mask;
        while (slotUsed[h]) {
            if (slotKeys[h] == key) {
                return h;
            }
            h = (h + 1) & mask;
        }
        if (2 * (slotsFilled + 1) > slotKeys.length) {
            growSlots();
            return blockSlot(x, y, z);
        }
        List<Aabb> boxes = world.block(x, y, z).boxes();
        int m = boxes.size();
        if (6 * (slotBoxCount + m) > slotBoxes.length) {
            slotBoxes = java.util.Arrays.copyOf(slotBoxes,
                    Math.max(2 * slotBoxes.length, 6 * (slotBoxCount + m)));
        }
        for (int i = 0; i < m; i++) {
            Aabb b = boxes.get(i);
            int c = 6 * (slotBoxCount + i);
            slotBoxes[c] = x + b.minX();
            slotBoxes[c + 1] = x + b.maxX();
            slotBoxes[c + 2] = z + b.minZ();
            slotBoxes[c + 3] = z + b.maxZ();
            slotBoxes[c + 4] = y + b.minY();
            slotBoxes[c + 5] = y + b.maxY();
        }
        slotUsed[h] = true;
        slotKeys[h] = key;
        slotStart[h] = slotBoxCount;
        slotCount[h] = m;
        slotBoxCount += m;
        slotsFilled++;
        return h;
    }

    private void growSlots() {
        long[] keys = slotKeys;
        boolean[] used = slotUsed;
        int[] start = slotStart;
        int[] count = slotCount;
        int size = 2 * keys.length;
        slotKeys = new long[size];
        slotUsed = new boolean[size];
        slotStart = new int[size];
        slotCount = new int[size];
        for (int i = 0; i < keys.length; i++) {
            if (used[i]) {
                int h = (int) (keys[i] * 0x9E3779B97F4A7C15L >>> 40) & (size - 1);
                while (slotUsed[h]) {
                    h = (h + 1) & (size - 1);
                }
                slotUsed[h] = true;
                slotKeys[h] = keys[i];
                slotStart[h] = start[i];
                slotCount[h] = count[i];
            }
        }
    }

    /** Empties the block cache: the world may have changed since the last build. */
    private void clearSlots() {
        java.util.Arrays.fill(slotUsed, false);
        slotsFilled = 0;
        slotBoxCount = 0;
    }

    /** How far past what's asked for {@link #nearCover} reaches, across and up and down. */
    private static final int NEAR_PAD = 8;
    private static final int NEAR_PAD_Y = 3;

    /**
     * Makes the kept columns ({@link #near}) take in the blocks from (x0, y0, z0) to (x1, y1,
     * z1): if they don't already, they're forgotten and the region moved there, with some
     * room round it so the next few nodes along are in it too.
     */
    private void nearCover(int x0, int y0, int z0, int x1, int y1, int z1) {
        if (x0 >= nearX0 && x1 < nearX0 + nearSX && y0 >= nearY0 && y1 < nearY0 + nearSY
                && z0 >= nearZ0 && z1 < nearZ0 + nearSZ) {
            return;
        }
        nearX0 = x0 - NEAR_PAD;
        nearY0 = y0 - NEAR_PAD_Y;
        nearZ0 = z0 - NEAR_PAD;
        nearSX = x1 - x0 + 1 + 2 * NEAR_PAD;
        nearSY = y1 - y0 + 1 + 2 * NEAR_PAD_Y;
        nearSZ = z1 - z0 + 1 + 2 * NEAR_PAD;
        int columns = nearSX * nearSZ;
        if (columns > nearRead.length) {
            nearRead = new int[columns];
            nearStamp = 0;
        }
        if (columns * (nearSY + 1) > nearLevel.length) {
            nearLevel = new int[columns * (nearSY + 1)];
        }
        nearStamp++;
        nearBoxCount = 0;
    }

    /**
     * Where column (rx, rz) of the region, counted from its corner, starts in {@link
     * #nearLevel}: read from the world first if it hasn't been.
     */
    private int nearColumn(int rx, int rz) {
        int column = rz * nearSX + rx;
        int base = (nearSY + 1) * column;
        if (nearRead[column] == nearStamp) {
            return base;
        }
        int x = nearX0 + rx;
        int z = nearZ0 + rz;
        for (int ry = 0; ry < nearSY; ry++) {
            nearLevel[base + ry] = nearBoxCount;
            int y = nearY0 + ry;
            List<Aabb> boxes = world.block(x, y, z).boxes();
            int m = boxes.size();
            if (6 * (nearBoxCount + m) > nearBoxes.length) {
                nearBoxes = java.util.Arrays.copyOf(nearBoxes,
                        Math.max(2 * nearBoxes.length, 6 * (nearBoxCount + m)));
            }
            for (int i = 0; i < m; i++) {
                Aabb b = boxes.get(i);
                int c = 6 * (nearBoxCount + i);
                nearBoxes[c] = x + b.minX();
                nearBoxes[c + 1] = x + b.maxX();
                nearBoxes[c + 2] = z + b.minZ();
                nearBoxes[c + 3] = z + b.maxZ();
                nearBoxes[c + 4] = y + b.minY();
                nearBoxes[c + 5] = y + b.maxY();
            }
            nearBoxCount += m;
        }
        nearLevel[base + nearSY] = nearBoxCount;
        nearRead[column] = nearStamp;
        return base;
    }

    /**
     * The boxes, in world coordinates, under the body's footprint at (x, z) from y0 to y1:
     * how many, with each one's bottom and top in {@link #around}.
     */
    private int boxesAround(double x, double z, double y0, double y1) {
        double x0 = x - HALF_WIDTH;
        double x1 = x + HALF_WIDTH;
        double z0 = z - HALF_WIDTH;
        double z1 = z + HALF_WIDTH;
        int bx0 = (int) Math.floor(x0);
        int bx1 = (int) Math.floor(x1 - EPS);
        int by0 = (int) Math.floor(y0);
        int by1 = (int) Math.floor(y1);
        int bz0 = (int) Math.floor(z0);
        int bz1 = (int) Math.floor(z1 - EPS);
        // A walk samples every WALK_STEP, so most samples cover the same blocks as the last:
        // their boxes are read from the world once and kept, in world coordinates, in the
        // order the blocks were visited in.
        if (bx0 != cellX0 || bx1 != cellX1 || by0 != cellY0 || by1 != cellY1 || bz0 != cellZ0
                || bz1 != cellZ1) {
            cellX0 = bx0;
            cellX1 = bx1;
            cellY0 = by0;
            cellY1 = by1;
            cellZ0 = bz0;
            cellZ1 = bz1;
            cellBoxes = 0;
            if (near && bx0 >= nearX0 && bx1 < nearX0 + nearSX && by0 >= nearY0
                    && by1 < nearY0 + nearSY && bz0 >= nearZ0 && bz1 < nearZ0 + nearSZ) {
                // The same boxes from the kept columns, a column at a time: in another order,
                // which nothing that reads them depends on.
                for (int bz = bz0; bz <= bz1; bz++) {
                    for (int bx = bx0; bx <= bx1; bx++) {
                        int base = nearColumn(bx - nearX0, bz - nearZ0);
                        int from = nearLevel[base + by0 - nearY0];
                        int m = nearLevel[base + by1 - nearY0 + 1] - from;
                        if (6 * (cellBoxes + m) > cells.length) {
                            cells = java.util.Arrays.copyOf(cells,
                                    Math.max(2 * cells.length, 6 * (cellBoxes + m)));
                        }
                        System.arraycopy(nearBoxes, 6 * from, cells, 6 * cellBoxes, 6 * m);
                        cellBoxes += m;
                    }
                }
            } else {
                for (int by = by0; by <= by1; by++) {
                    for (int bz = bz0; bz <= bz1; bz++) {
                        for (int bx = bx0; bx <= bx1; bx++) {
                            int slot = blockSlot(bx, by, bz);
                            int from = slotStart[slot];
                            int m = slotCount[slot];
                            if (6 * (cellBoxes + m) > cells.length) {
                                cells = java.util.Arrays.copyOf(cells,
                                        Math.max(2 * cells.length, 6 * (cellBoxes + m)));
                            }
                            System.arraycopy(slotBoxes, 6 * from, cells, 6 * cellBoxes, 6 * m);
                            cellBoxes += m;
                        }
                    }
                }
            }
        }
        int n = 0;
        double[] c = cells;
        for (int k = 0, end = 6 * cellBoxes; k < end; k += 6) {
            if (c[k] < x1 - EPS && c[k + 1] > x0 + EPS && c[k + 2] < z1 - EPS
                    && c[k + 3] > z0 + EPS && c[k + 5] >= y0 && c[k + 4] <= y1) {
                if (2 * n + 2 > around.length) {
                    around = java.util.Arrays.copyOf(around, 2 * around.length);
                }
                around[2 * n] = c[k + 4];
                around[2 * n + 1] = c[k + 5];
                n++;
            }
        }
        return n;
    }

    /**
     * Moves each node whose floor doesn't reach under its cell's centre over that floor: the
     * top of a stair is only its back half, so the node for standing on it is there. Otherwise
     * a step up onto it from the stair's own low half would be a step on the spot. Then moves
     * each node the body doesn't fit at (on a stair's low half, next to its back) the least
     * distance to where it does.
     */
    private void settle(List<PathStep> path, double[] floorY, boolean[] hasFloor, double[] px,
            double[] pz) {
        for (int i = 0; i < path.size(); i++) {
            if (!hasFloor[i]) {
                continue;
            }
            BlockPoint c = path.get(i).pos();
            // Where the node is within its cell: the centre, or a line's point.
            double lx = px[i] - c.x();
            double lz = pz[i] - c.z();
            Aabb support = null;
            boolean centred = false;
            for (int dy = -2; dy <= 0 && !centred; dy++) {
                for (Aabb box : world.block(c.x(), c.y() + dy, c.z()).boxes()) {
                    if (Math.abs(c.y() + dy + box.maxY() - floorY[i]) > 1e-9) {
                        continue;
                    }
                    if (box.minX() < lx && box.maxX() > lx && box.minZ() < lz
                            && box.maxZ() > lz) {
                        centred = true;
                        break;
                    }
                    if (support == null) {
                        support = box;
                    }
                }
            }
            if (!centred && support != null) {
                double x = c.x() + clamp((support.minX() + support.maxX()) / 2);
                double z = c.z() + clamp((support.minZ() + support.maxZ()) / 2);
                if (fits(path, floorY[i], i, x, z)) {
                    px[i] = x;
                    pz[i] = z;
                }
            }
            if (fits(path, floorY[i], i, px[i], pz[i])) {
                continue;
            }
            double[] spot = nearestFit(path, i, floorY[i], px[i], pz[i]);
            if (spot == null) {
                // Nowhere to stand on this floor (the low half of a stair wedged against a
                // wall): stand on what's a step above it in the cell instead, such as the
                // stair's back half.
                for (double top : topsAbove(c, floorY[i])) {
                    double[] over = nearestFit(path, i, top, px[i], pz[i]);
                    if (over != null) {
                        spot = over;
                        floorY[i] = top;
                        break;
                    }
                }
            }
            if (spot != null) {
                px[i] = spot[0];
                pz[i] = spot[1];
            }
        }
    }

    /** The nearest place to (x0, z0) in node i's cell where its body fits standing at y. */
    private double[] nearestFit(List<PathStep> path, int i, double y, double x0, double z0) {
        if (fits(path, y, i, x0, z0)) {
            return new double[] {x0, z0};
        }
        for (double r = SAMPLE; r <= 2 * MAX_SHIFT + 1e-9; r += SAMPLE) {
            for (int k = 0; k < 16; k++) {
                double a = k * Math.PI / 8;
                // A little more than just enough, as in fit.
                for (double m : new double[] {r + MARGIN, r}) {
                    double x = x0 + m * Math.cos(a);
                    double z = z0 + m * Math.sin(a);
                    if (fits(path, y, i, x, z)
                            && fits(path, y, i, x0 + r * Math.cos(a), z0 + r * Math.sin(a))) {
                        return new double[] {x, z};
                    }
                }
            }
        }
        return null;
    }

    /** The tops of the boxes in the cell, and the one below, up to a step above y, lowest first. */
    private List<Double> topsAbove(BlockPoint c, double y) {
        List<Double> tops = new ArrayList<>();
        for (int dy = -1; dy <= 0; dy++) {
            for (Aabb box : world.block(c.x(), c.y() + dy, c.z()).boxes()) {
                double top = c.y() + dy + box.maxY();
                if (top > y + 1e-9 && top <= y + STEP_HEIGHT + 1e-9 && !tops.contains(top)) {
                    tops.add(top);
                }
            }
        }
        tops.sort(null);
        return tops;
    }

    private static double clamp(double d) {
        return Math.max(0.5 - MAX_SHIFT, Math.min(0.5 + MAX_SHIFT, d));
    }

    /**
     * Whether node i's body fits at (x, z), standing at y with nothing in it (not even a step
     * it could climb), and stays in its cell.
     */
    private boolean fits(List<PathStep> path, double y, int i, double x, double z) {
        BlockPoint c = path.get(i).pos();
        return Math.abs(x - (c.x() + 0.5)) <= band + 1e-9
                && Math.abs(z - (c.z() + 0.5)) <= band + 1e-9
                && Collisions.isSpaceEmpty(world, new Aabb(x - HALF_WIDTH, y + LIP,
                        z - HALF_WIDTH, x + HALF_WIDTH, y + HEIGHT, z + HALF_WIDTH))
                && blockedAt(x, y, z, true) == null;
    }

    // ---- pulling the route taut ----

    /** How many times each node is pulled toward the line between its neighbours. */
    private static final int TIGHTEN_PASSES = 40;
    /**
     * How far a node must be from where it's pulled to for {@link #tighten} to move it: a
     * route within this of taut is left as it is. 5 mm lays walks out about twice as fast as
     * 1 mm, the nodes within a few centimetres of where they'd end up.
     */
    private static final double TIGHTEN_STOP = 5e-3;
    /** Where {@link #overEdge} tests, from the body's centre: round it, a little outside. */
    private static final double[] EDGE_DX = new double[8];
    private static final double[] EDGE_DZ = new double[8];
    /** The farthest of them, across. */
    private static final double EDGE_REACH;

    static {
        double r = HALF_WIDTH + CURVE_MARGIN;
        double reach = 0;
        for (int k = 0; k < 8; k++) {
            double a = k * Math.PI / 4;
            EDGE_DX[k] = r * Math.cos(a) * (k % 2 == 1 ? Math.sqrt(2) : 1);
            EDGE_DZ[k] = r * Math.sin(a) * (k % 2 == 1 ? Math.sqrt(2) : 1);
            reach = Math.max(reach, Math.max(Math.abs(EDGE_DX[k]), Math.abs(EDGE_DZ[k])));
        }
        EDGE_REACH = reach;
    }
    /** How far inside its cell a pulled node stays, so it's clearly in it. */
    private static final double CELL_INSET = 0.02;

    /**
     * Pulls the route taut, like a string, within the cells it goes through: each node moves
     * toward the straight line between the nodes either side of it, as far as its cell, the
     * floor under it and the room for the body let it. Smoothed lines end at cell centres,
     * and so do jumps and drops, so where two lines meet, or a line meets a jump up onto a
     * ledge, the route would otherwise swerve sideways to a centre and back: this takes the
     * swerve out, and cuts a little inside each corner too.
     */
    private void tighten(List<PathStep> path, double[] floorY, boolean[] hasFloor,
            boolean[] jump, double[] px, double[] pz) {
        int n = path.size();
        boolean[] fixed = new boolean[n];
        for (int i = 0; i < n; i++) {
            fixed[i] = i == 0 || i == n - 1 || !hasFloor[i] || jump[i]
                    || (i + 1 < n && jump[i + 1]) || door(path.get(i).pos()) != null
                    || !hasFloor[i - 1] || !hasFloor[i + 1];
        }
        // A node is only tried again once it or a neighbour has moved: with the same three
        // points it would come to the same answer.
        boolean[] dirty = new boolean[n];
        java.util.Arrays.fill(dirty, true);
        near = true;
        // How far over an edge each node is where it stands now, worked out once per place.
        int[] edge = new int[n];
        for (int i = 0; i < n; i++) {
            edge[i] = fixed[i] ? 0 : overEdge(px[i], pz[i], floorY[i]);
        }
        for (int pass = 0; pass < TIGHTEN_PASSES; pass++) {
            boolean moved = false;
            for (int i = 1; i + 1 < n; i++) {
                if (fixed[i] || !dirty[i]) {
                    continue;
                }
                dirty[i] = false;
                double ax = px[i - 1];
                double az = pz[i - 1];
                double dx = px[i + 1] - ax;
                double dz = pz[i + 1] - az;
                double len2 = dx * dx + dz * dz;
                if (len2 < 1e-12) {
                    continue;
                }
                double t = Math.max(0, Math.min(1, ((px[i] - ax) * dx + (pz[i] - az) * dz) / len2));
                BlockPoint c = path.get(i).pos();
                double tx = Math.max(c.x() + CELL_INSET, Math.min(c.x() + 1 - CELL_INSET,
                        ax + t * dx));
                double tz = Math.max(c.z() + CELL_INSET, Math.min(c.z() + 1 - CELL_INSET,
                        az + t * dz));
                if (Math.hypot(tx - px[i], tz - pz[i]) < TIGHTEN_STOP) {
                    continue;
                }
                nearCover(path, floorY, i);
                for (double f = 1; f >= 0.2; f /= 2) {
                    double x = px[i] + f * (tx - px[i]);
                    double z = pz[i] + f * (tz - pz[i]);
                    int over;
                    if (canStand(path, floorY, i, x, z)
                            && (over = overEdge(x, z, floorY[i], edge[i])) <= edge[i]
                            && canLink(path, floorY, px[i - 1], pz[i - 1], i - 1, x, z, i)
                            && canLink(path, floorY, x, z, i, px[i + 1], pz[i + 1], i + 1)) {
                        px[i] = x;
                        pz[i] = z;
                        edge[i] = over;
                        moved = true;
                        dirty[i - 1] = true;
                        dirty[i] = true;
                        dirty[i + 1] = true;
                        break;
                    }
                }
            }
            if (!moved) {
                break;
            }
        }
        near = false;
    }

    /**
     * Makes the kept columns take in what the walks trying node i makes can meet: the cells of
     * it and its neighbours and a little round them, from below the lowest floor to a body and
     * a step above the highest. A walk that strays further reads its blocks as it would without
     * them.
     */
    private void nearCover(List<PathStep> path, double[] floorY, int i) {
        BlockPoint a = path.get(i - 1).pos();
        BlockPoint b = path.get(i).pos();
        BlockPoint c = path.get(i + 1).pos();
        double lo = Math.min(floorY[i - 1], Math.min(floorY[i], floorY[i + 1]));
        double hi = Math.max(floorY[i - 1], Math.max(floorY[i], floorY[i + 1]));
        nearCover(Math.min(a.x(), Math.min(b.x(), c.x())) - 2, (int) Math.floor(lo) - 3,
                Math.min(a.z(), Math.min(b.z(), c.z())) - 2,
                Math.max(a.x(), Math.max(b.x(), c.x())) + 2, (int) Math.floor(hi) + 4,
                Math.max(a.z(), Math.max(b.z(), c.z())) + 2);
    }

    /**
     * Whether node i can stand at (x, z): on the same floor as before, with the body and a
     * little more clear.
     */
    private boolean canStand(List<PathStep> path, double[] floorY, int i, double x, double z) {
        BlockPoint c = path.get(i).pos();
        double f = floor(c, x - c.x(), z - c.z());
        if (Double.isNaN(f) || Math.abs(f - floorY[i]) > 1e-6) {
            return false;
        }
        // The same as fits(path, floorY[i], i, x, z) and the wider box clear, tested in an
        // order that lets one test settle others: the wider box clear, the body is too, from
        // the lip up and from a step up (blockedAt's wall), as every box that meets the body
        // meets the wider box, among blocks it reads too.
        double y = floorY[i];
        if (!(Math.abs(x - (c.x() + 0.5)) <= band + 1e-9)
                || !(Math.abs(z - (c.z() + 0.5)) <= band + 1e-9)) {
            return false;
        }
        double r = HALF_WIDTH + CURVE_MARGIN;
        if (!Collisions.isSpaceEmpty(world, new Aabb(x - r, y + LIP, z - r, x + r, y + HEIGHT,
                z + r))) {
            return false;
        }
        // That leaves blockedAt's drop: none if there's something underfoot, which the floor
        // just found is, most likely.
        if (underfoot(c, x, y, z)) {
            return true;
        }
        return !(Collisions.isSpaceEmpty(world, new Aabb(x - HALF_WIDTH, y - STEP_HEIGHT - 1e-3,
                z - HALF_WIDTH, x + HALF_WIDTH, y + 1e-3, z + HALF_WIDTH))
                && Collisions.isSpaceEmpty(world, new Aabb(x - HALF_WIDTH, y + 1e-3,
                        z - HALF_WIDTH, x + HALF_WIDTH, y + STEP_HEIGHT, z + HALF_WIDTH)));
    }

    /**
     * Whether a box of the blocks in cell c's column, from two below it up, is in the way of
     * the box {@link #blockedAt} looks for a floor in, standing at (x, y, z): tested as {@link
     * Collisions#isSpaceEmpty(SimWorld, Aabb)} tests each box, and only among blocks it reads,
     * so if one is, that box isn't empty either.
     */
    private boolean underfoot(BlockPoint c, double x, double y, double z) {
        double minX = x - HALF_WIDTH;
        double minY = y - STEP_HEIGHT - 1e-3;
        double minZ = z - HALF_WIDTH;
        double maxX = x + HALF_WIDTH;
        double maxY = y + 1e-3;
        double maxZ = z + HALF_WIDTH;
        int bx = c.x();
        int bz = c.z();
        if (bx < (int) Math.floor(minX - 1.0E-7) - 1 || bx > (int) Math.floor(maxX + 1.0E-7) + 1
                || bz < (int) Math.floor(minZ - 1.0E-7) - 1
                || bz > (int) Math.floor(maxZ + 1.0E-7) + 1) {
            return false;
        }
        int y0 = Math.max(c.y() - 2, (int) Math.floor(minY - 1.0E-7) - 1);
        int y1 = Math.min(c.y(), (int) Math.floor(maxY + 1.0E-7) + 1);
        for (int by = y0; by <= y1; by++) {
            List<Aabb> boxes = world.block(bx, by, bz).boxes();
            for (int k = 0, m = boxes.size(); k < m; k++) {
                Aabb a = boxes.get(k);
                if (a.minX() + bx < maxX && a.maxX() + bx > minX && a.minY() + by < maxY
                        && a.maxY() + by > minY && a.minZ() + bz < maxZ
                        && a.maxZ() + bz > minZ) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * How many points round the body (and a little more) are over a drop at (x, z) standing at
     * y: nothing under them for a block down. Pulling the route taut mustn't take it nearer an
     * edge than it was.
     */
    private int overEdge(double x, double z, double y) {
        return overEdge(x, z, y, EDGE_DX.length);
    }

    /** The same, or {@code limit + 1} as soon as it's more than {@code limit}. */
    private int overEdge(double x, double z, double y, int limit) {
        // The blocks round all eight points are read once, then each point is tested against
        // their boxes alone: the same answer as testing it against the world. Most points are
        // over a floor, though, which the block right under them shows at once (one of those
        // blocks, a box of it meeting the point is one of those boxes): the rest are only read
        // for a point that isn't.
        int boxes = -1;
        int over = 0;
        for (int k = 0; k < EDGE_DX.length; k++) {
            double qx = x + EDGE_DX[k];
            double qz = z + EDGE_DZ[k];
            if (overFloor(qx - 0.01, y - 1, qz - 0.01, qx + 0.01, y + 1e-3, qz + 0.01)) {
                continue;
            }
            if (boxes < 0) {
                int[] count = new int[1];
                edgeNear = Collisions.boxesNear(world, new Aabb(x - EDGE_REACH - 0.01, y - 1,
                        z - EDGE_REACH - 0.01, x + EDGE_REACH + 0.01, y + 1e-3,
                        z + EDGE_REACH + 0.01), edgeNear, count);
                boxes = count[0];
            }
            if (Collisions.isSpaceEmpty(edgeNear, boxes, qx - 0.01, y - 1, qz - 0.01,
                    qx + 0.01, y + 1e-3, qz + 0.01) && ++over > limit) {
                return over;
            }
        }
        return over;
    }

    /**
     * Whether a box of the blocks under the middle of the box from (minX, minY, minZ) to
     * (maxX, maxY, maxZ) meets it, tested as {@link Collisions#isSpaceEmpty(double[], int,
     * double, double, double, double, double, double)} tests the boxes {@link
     * Collisions#boxesNear} gives.
     */
    private boolean overFloor(double minX, double minY, double minZ, double maxX, double maxY,
            double maxZ) {
        int bx = (int) Math.floor((minX + maxX) / 2);
        int bz = (int) Math.floor((minZ + maxZ) / 2);
        for (int by = (int) Math.floor(minY), top = (int) Math.floor(maxY); by <= top; by++) {
            List<Aabb> boxes = world.block(bx, by, bz).boxes();
            for (int k = 0, m = boxes.size(); k < m; k++) {
                Aabb a = boxes.get(k);
                if (a.minX() + bx < maxX && a.maxX() + bx > minX && a.minY() + by < maxY
                        && a.maxY() + by > minY && a.minZ() + bz < maxZ
                        && a.maxZ() + bz > minZ) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether the step from node a at (ax, az) to node b at (bx, bz) still works: a walk the
     * body makes with a little room to spare, or a jump or drop with the body clear all the
     * way up or down at both ends.
     */
    private boolean canLink(List<PathStep> path, double[] floorY, double ax, double az, int a,
            double bx, double bz, int b) {
        Kind k = kind(path.get(b).via());
        if (k == Kind.STRAIGHT) {
            return reachesWithSlack(ax, floorY[a], az, bx, floorY[b], bz);
        }
        if (k != Kind.JUMP_UP && k != Kind.DROP) {
            return false;
        }
        double top = Math.max(floorY[a], floorY[b]);
        double hi = top + HEIGHT;
        // Each end from its own floor up (the whole fall, for a drop), and half way across
        // above the higher floor.
        return clearColumn(ax, az, floorY[a], hi) && clearColumn(bx, bz, floorY[b], hi)
                && clearColumn((ax + bx) / 2, (az + bz) / 2, top, hi);
    }

    private boolean clearColumn(double x, double z, double y0, double y1) {
        return Collisions.isSpaceEmpty(world, new Aabb(x - HALF_WIDTH, y0 + 1e-3,
                z - HALF_WIDTH, x + HALF_WIDTH, y1, z + HALF_WIDTH));
    }

    // ---- room on each side ----

    /** The horizontal direction the route leaves node i in, as a unit vector (x, z). */
    private static double[] direction(List<PathStep> path, int i) {
        for (int j = i; j + 1 < path.size(); j++) {
            double[] d = between(path.get(j).pos(), path.get(j + 1).pos());
            if (d != null) {
                return d;
            }
        }
        for (int j = i; j > 0; j--) {
            double[] d = between(path.get(j - 1).pos(), path.get(j).pos());
            if (d != null) {
                return d;
            }
        }
        return new double[] {1, 0};
    }

    /** The same, read from the nodes' points: for routes laid along lines. */
    private static double[] direction(double[] px, double[] pz, int i) {
        for (int j = i; j + 1 < px.length; j++) {
            double[] d = between(px[j], pz[j], px[j + 1], pz[j + 1]);
            if (d != null) {
                return d;
            }
        }
        for (int j = i; j > 0; j--) {
            double[] d = between(px[j - 1], pz[j - 1], px[j], pz[j]);
            if (d != null) {
                return d;
            }
        }
        return new double[] {1, 0};
    }

    private static double[] between(double ax, double az, double bx, double bz) {
        double dx = bx - ax;
        double dz = bz - az;
        double len = Math.hypot(dx, dz);
        return len < 1e-9 ? null : new double[] {dx / len, dz / len};
    }

    private static double[] between(BlockPoint a, BlockPoint b) {
        double dx = b.x() - a.x();
        double dz = b.z() - a.z();
        double len = Math.hypot(dx, dz);
        return len == 0 ? null : new double[] {dx / len, dz / len};
    }

    /**
     * Moves the body sideways from the route, in {@link #SAMPLE} steps, at a few points along
     * the step from node i to the next one on the same level, until it would hit something or
     * lose its floor. Where there's no floor (a ladder, water) only walls count.
     */
    private Side side(List<PathStep> path, double[] floorY, boolean floor, double[] px,
            double[] pz, int i, double sx, double sz) {
        double x0 = px[i];
        double z0 = pz[i];
        double ex = 0;
        double ez = 0;
        boolean level = i + 1 < path.size() && floorY[i + 1] == floorY[i];
        if (level) {
            ex = px[i + 1] - px[i];
            ez = pz[i + 1] - pz[i];
        }
        double[] ts = level ? new double[] {0, 0.25, 0.5, 0.75} : new double[] {0};
        // Read the blocks the body can meet on this side once, then test each place against
        // their boxes alone.
        double y = floorY[i];
        double reach = MAX_WIDTH + HALF_WIDTH + 0.01;
        double xa = x0 + Math.min(0, ex * 0.75);
        double xb = x0 + Math.max(0, ex * 0.75);
        double za = z0 + Math.min(0, ez * 0.75);
        double zb = z0 + Math.max(0, ez * 0.75);
        sideBoxes(xa + Math.min(0, sx * reach) - HALF_WIDTH - 0.01, y - STEP_HEIGHT - 0.01,
                za + Math.min(0, sz * reach) - HALF_WIDTH - 0.01,
                xb + Math.max(0, sx * reach) + HALF_WIDTH + 0.01, y + HEIGHT + 0.01,
                zb + Math.max(0, sz * reach) + HALF_WIDTH + 0.01, y, floor);
        int walls = wallCount;
        int feet = footCount;
        int lows = lowCount;
        footHit = 0;
        for (double s = SAMPLE; s <= MAX_WIDTH + 1e-9; s += SAMPLE) {
            for (double t : ts) {
                double x = x0 + ex * t + sx * s;
                double z = z0 + ez * t + sz * s;
                Edge e = blockedAt(wall, walls, foot, feet, low, lows, x, y, z, floor);
                if (e != null) {
                    return new Side(Math.max(0, round(s - SAMPLE)), e);
                }
            }
        }
        return new Side(MAX_WIDTH, Edge.OPEN);
    }

    /**
     * Sorts out the boxes of the blocks round a region (from its min to its max corner) that
     * {@link #side} can meet, for the body standing at height y: into {@link #wall}, {@link
     * #foot} and {@link #low}, as {@link #blockedAt(double, double, double, boolean)} tests
     * them, in runs of six as {@link Collisions#boxesNear} lays them out, and how many in each.
     * The blocks are the ones {@link Collisions#boxesNear} reads for the region. Only boxes
     * that reach into the region are kept (every place side tests is well inside it, so the
     * rest can't meet it), each where it reaches into the heights of a test, so each test
     * meets exactly the boxes it would among all of them.
     */
    private void sideBoxes(double minX, double minY, double minZ, double maxX, double maxY,
            double maxZ, double y, boolean floor) {
        double wallLo = y + (floor ? STEP_HEIGHT : 1e-3);
        double wallHi = y + HEIGHT;
        double footLo = y - STEP_HEIGHT - 1e-3;
        double footHi = y + 1e-3;
        double lowLo = y + 1e-3;
        double lowHi = y + STEP_HEIGHT;
        int x0 = (int) Math.floor(minX - 1.0E-7) - 1;
        int x1 = (int) Math.floor(maxX + 1.0E-7) + 1;
        int y0 = (int) Math.floor(minY - 1.0E-7) - 1;
        int y1 = (int) Math.floor(maxY + 1.0E-7) + 1;
        int z0 = (int) Math.floor(minZ - 1.0E-7) - 1;
        int z1 = (int) Math.floor(maxZ + 1.0E-7) + 1;
        int walls = 0;
        int feet = 0;
        int lows = 0;
        for (int by = y0; by <= y1; by++) {
            for (int bz = z0; bz <= z1; bz++) {
                for (int bx = x0; bx <= x1; bx++) {
                    List<Aabb> boxes = world.block(bx, by, bz).boxes();
                    for (int k = 0, m = boxes.size(); k < m; k++) {
                        // Moved to where the block is, as boxesNear does.
                        Aabb a = boxes.get(k);
                        double ax0 = a.minX() + bx;
                        double ax1 = a.maxX() + bx;
                        double az0 = a.minZ() + bz;
                        double az1 = a.maxZ() + bz;
                        if (!(ax0 < maxX && ax1 > minX && az0 < maxZ && az1 > minZ)) {
                            continue;
                        }
                        double ay0 = a.minY() + by;
                        double ay1 = a.maxY() + by;
                        if (ay1 > wallLo && ay0 < wallHi) {
                            wall = keep(wall, walls++, ax0, ay0, az0, ax1, ay1, az1);
                        }
                        if (ay1 > footLo && ay0 < footHi) {
                            foot = keep(foot, feet++, ax0, ay0, az0, ax1, ay1, az1);
                        }
                        if (ay1 > lowLo && ay0 < lowHi) {
                            low = keep(low, lows++, ax0, ay0, az0, ax1, ay1, az1);
                        }
                    }
                }
            }
        }
        wallCount = walls;
        footCount = feet;
        lowCount = lows;
    }

    /** Puts a box into {@code into} as its {@code m}th; the array, grown if need be. */
    private static double[] keep(double[] into, int m, double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ) {
        double[] out = 6 * (m + 1) > into.length
                ? java.util.Arrays.copyOf(into, Math.max(64, 2 * into.length)) : into;
        int o = 6 * m;
        out[o] = minX;
        out[o + 1] = minY;
        out[o + 2] = minZ;
        out[o + 3] = maxX;
        out[o + 4] = maxY;
        out[o + 5] = maxZ;
        return out;
    }

    /**
     * {@link #blockedAt(double, double, double, boolean)} against boxes laid out as {@link
     * Collisions#boxesNear} does, sorted out for each test by {@link #sideBoxes}: the same
     * answer.
     */
    private Edge blockedAt(double[] wall, int walls, double[] foot, int feet,
            double[] low, int lows, double x, double y, double z, boolean floor) {
        if (!Collisions.isSpaceEmpty(wall, walls, x - HALF_WIDTH,
                y + (floor ? STEP_HEIGHT : 1e-3), z - HALF_WIDTH, x + HALF_WIDTH, y + HEIGHT,
                z + HALF_WIDTH)) {
            return Edge.WALL;
        }
        if (!floor) {
            return null;
        }
        if (isFootEmpty(foot, feet, x - HALF_WIDTH, y - STEP_HEIGHT - 1e-3,
                z - HALF_WIDTH, x + HALF_WIDTH, y + 1e-3, z + HALF_WIDTH)
                && Collisions.isSpaceEmpty(low, lows, x - HALF_WIDTH, y + 1e-3,
                        z - HALF_WIDTH, x + HALF_WIDTH, y + STEP_HEIGHT, z + HALF_WIDTH)) {
            return Edge.DROP;
        }
        return null;
    }

    /**
     * {@link Collisions#isSpaceEmpty(double[], int, double, double, double, double, double,
     * double)} for the boxes underfoot, the same test of each box, but starting from the box
     * the last place stood on ({@link #footHit}), which it becomes when another is found: the
     * next place along is almost always on the same one, so it's found at once instead of
     * after the boxes before it. Whether any box is in the way doesn't depend on the order
     * they're tried in.
     */
    private boolean isFootEmpty(double[] boxes, int n, double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ) {
        int from = footHit < n ? footHit : 0;
        for (int i = 0; i < n; i++) {
            int b = from + i < n ? from + i : from + i - n;
            int k = 6 * b;
            if (boxes[k] < maxX && boxes[k + 3] > minX && boxes[k + 1] < maxY
                    && boxes[k + 4] > minY && boxes[k + 2] < maxZ && boxes[k + 5] > minZ) {
                footHit = b;
                return false;
            }
        }
        return true;
    }

    /**
     * What stops a body standing at (x, y, z), or null if it can be there. With no floor the
     * body is held up (on a ladder, in water), so only walls stop it.
     */
    private Edge blockedAt(double x, double y, double z, boolean floor) {
        // Anything up to a step high is stepped onto, so only look above that.
        Aabb body = new Aabb(x - HALF_WIDTH, y + (floor ? STEP_HEIGHT : 1e-3), z - HALF_WIDTH,
                x + HALF_WIDTH, y + HEIGHT, z + HALF_WIDTH);
        if (!Collisions.isSpaceEmpty(world, body)) {
            return Edge.WALL;
        }
        if (!floor) {
            return null;
        }
        Aabb low = new Aabb(x - HALF_WIDTH, y + 1e-3, z - HALF_WIDTH, x + HALF_WIDTH,
                y + STEP_HEIGHT, z + HALF_WIDTH);
        Aabb underfoot = new Aabb(x - HALF_WIDTH, y - STEP_HEIGHT - 1e-3, z - HALF_WIDTH,
                x + HALF_WIDTH, y + 1e-3, z + HALF_WIDTH);
        if (Collisions.isSpaceEmpty(world, underfoot) && Collisions.isSpaceEmpty(world, low)) {
            return Edge.DROP;
        }
        return null;
    }

    private static double round(double d) {
        return Math.round(d * 1000) / 1000.0;
    }

    // ---- doors ----

    private Door door(BlockPoint c) {
        for (int dy = 0; dy <= 1; dy++) {
            String kind = world.block(c.x(), c.y() + dy, c.z()).kind();
            if (kind.equals("door") || kind.equals("gate")) {
                return new Door(c.offset(0, dy, 0), kind);
            }
        }
        return null;
    }

    // ---- speed ----

    private double[] speedLimits(List<PathStep> path, double[] floorY,
            boolean[] hasFloor, boolean[] jump, double[] distance, Side[] left, Side[] right,
            Door[] doors, double[] px, double[] pz, boolean[] curved) {
        int n = path.size();
        double[] limit = new double[n];
        // Sprinting and walking go as fast as the player does; what holds it to them (the
        // turn at a sharp corner, a door) scales with it. Ladders, water, drifting off an
        // edge and braking are the game's own numbers, whatever the player's speed.
        double sprint = ExecutionPlan.SPRINT_SPEED * speed;
        double walk = ExecutionPlan.WALK_SPEED * speed;
        for (int i = 0; i < n; i++) {
            double v = sprint;
            MoveType via = path.get(i).via();
            MoveType next = i + 1 < n ? path.get(i + 1).via() : null;
            if (via == MoveType.CLIMB || next == MoveType.CLIMB) {
                v = Math.min(v, CLIMB_SPEED);
            }
            if (via == MoveType.SWIM || next == MoveType.SWIM) {
                v = Math.min(v, SWIM_SPEED);
            }
            if (doors[i] != null || (i + 1 < n && doors[i + 1] != null)) {
                v = Math.min(v, walk);
            }
            if (next == MoveType.DROP) {
                // Land where there's room: a narrow landing with an edge beside it is taken
                // at walking speed.
                if (narrowDrop(left[i + 1]) || narrowDrop(right[i + 1])) {
                    v = Math.min(v, ExecutionPlan.WALK_SPEED);
                }
            }
            if (i > 0 && i + 1 < n) {
                // A rounded corner is taken at speed: the curve turns the player gradually.
                double turn = curved[i] ? sprint : walk;
                v = Math.min(v, px != null
                        ? cornerLimit(between(px[i - 1], pz[i - 1], px[i], pz[i]),
                                between(px[i], pz[i], px[i + 1], pz[i + 1]), left[i], right[i],
                                sprint, turn)
                        : cornerLimit(between(path.get(i - 1).pos(), path.get(i).pos()),
                                between(path.get(i).pos(), path.get(i + 1).pos()), left[i],
                                right[i], sprint, turn));
            }
            limit[i] = v;
        }
        limit[n - 1] = 0;
        for (int i = n - 2; i >= 0; i--) {
            MoveType next = path.get(i + 1).via();
            boolean air = next == MoveType.DROP || next == MoveType.JUMP_UP
                    || !hasFloor[i + 1] || jump[i + 1];
            double brake = air ? AIR_BRAKE : GROUND_BRAKE;
            double reachable = limit[i + 1] + brake * (distance[i + 1] - distance[i]);
            limit[i] = Math.min(limit[i], reachable);
        }
        return limit;
    }

    /**
     * Rounds off the corners between walked stretches: at each node where the route turns,
     * the biggest {@link Curve} that starts no further back than half the way to the last
     * corner (or the whole way to a jump, drop or door), and whose middle cuts no further inside
     * than {@link #INSET_SHARE} of the room on the inside there.
     */
    /** For each node, whether its curve goes up or down a step: set by {@link #curves}. */
    private boolean[] stepped;
    /** How far the floor rose or fell along the middle of the last curve checked. */
    private double rise;
    /** Floors closer than this are one level (a carpet on the floor, say). */
    private static final double LEVEL = 0.1;
    /**
     * The curve {@link #walkable} checks, laid out: its places; which way is left of it at
     * each, as a unit vector (acrossX, -acrossZ); the height the route has there; and the node
     * its start is past.
     */
    private double[] curveX = new double[0];
    private double[] curveZ = new double[0];
    private double[] acrossX = new double[0];
    private double[] acrossZ = new double[0];
    private double[] guesses = new double[0];
    private int firstNode;

    private List<Curve> curves(List<PathStep> path, double[] floorY, boolean[] hasFloor,
            boolean[] jump,
            double[] px, double[] pz, Side[] left, Side[] right, Door[] doors) {
        int n = path.size();
        stepped = new boolean[n];
        double[] along = new double[n];
        for (int i = 1; i < n; i++) {
            along[i] = along[i - 1] + Math.hypot(px[i] - px[i - 1], pz[i] - pz[i - 1]);
        }
        // Where a curve may reach to: nodes on walked, level-ish floor.
        boolean[] flat = new boolean[n];
        for (int i = 0; i < n; i++) {
            MoveType via = path.get(i).via();
            flat[i] = hasFloor[i] && !jump[i] && doors[i] == null
                    && (i == 0 || via == MoveType.WALK || via == MoveType.DIAGONAL);
        }
        // The corners: where the way in and the way out differ.
        List<Integer> corners = new ArrayList<>();
        for (int i = 1; i + 1 < n; i++) {
            double[] in = between(px[i - 1], pz[i - 1], px[i], pz[i]);
            double[] out = between(px[i], pz[i], px[i + 1], pz[i + 1]);
            if (in != null && out != null && flat[i - 1] && flat[i] && flat[i + 1]
                    && in[0] * out[0] + in[1] * out[1]
                            < Math.cos(Math.toRadians(Curve.MIN_TURN))) {
                corners.add(i);
            }
        }
        List<Curve> out = new ArrayList<>();
        for (int k = 0; k < corners.size(); k++) {
            int i = corners.get(k);
            // Back along the way in: to the last corner (sharing the way), or to where the
            // floor stops being walked.
            double back = along[i] - along[k > 0 ? corners.get(k - 1) : 0];
            if (k > 0) {
                back /= 2;
            }
            for (int j = i - 1; j >= 0; j--) {
                if (!flat[j]) {
                    back = Math.min(back, along[i] - along[j + 1]);
                    break;
                }
            }
            double ahead = along[k + 1 < corners.size() ? corners.get(k + 1) : n - 1] - along[i];
            if (k + 1 < corners.size()) {
                ahead /= 2;
            } else {
                // Leave the end, where it stops, on the straight.
                ahead = Math.max(0, ahead - 0.5);
            }
            for (int j = i + 1; j < n; j++) {
                if (!flat[j]) {
                    ahead = Math.min(ahead, along[j - 1] - along[i]);
                    break;
                }
            }
            double maxTrim = Math.min(MAX_TRIM, Math.min(back, ahead));
            double[] dirIn = between(px[i - 1], pz[i - 1], px[i], pz[i]);
            double[] dirOut = between(px[i], pz[i], px[i + 1], pz[i + 1]);
            boolean rightTurn = dirIn[0] * dirOut[1] - dirIn[1] * dirOut[0] > 0;
            Curve c = null;
            // The room inside depends on how far the curve reaches, which depends on the room.
            for (int pass = 0; pass < 3; pass++) {
                double reach = c == null ? maxTrim : c.trim();
                double room = MAX_WIDTH;
                // The nodes that near are all together round i, the way along only ever
                // growing: out from i each way until one is too far.
                for (int j = i; j >= 0 && Math.abs(along[j] - along[i]) <= reach + 1e-9; j--) {
                    room = Math.min(room, (rightTurn ? right[j] : left[j]).width());
                }
                for (int j = i + 1; j < n && Math.abs(along[j] - along[i]) <= reach + 1e-9;
                        j++) {
                    room = Math.min(room, (rightTurn ? right[j] : left[j]).width());
                }
                Curve next = Curve.fit(i, px[i], pz[i], dirIn, dirOut, maxTrim,
                        INSET_SHARE * room);
                if (next == null || (c != null && Math.abs(next.trim() - c.trim()) < 1e-6)) {
                    c = next;
                    break;
                }
                c = next;
                maxTrim = Math.min(maxTrim, c.trim());
            }
            // Shrink it until the body walks it: the inside of a corner can hide a step up or
            // a wall the room either side doesn't see.
            for (int shrink = 0; c != null && !walkable(c, path, floorY, along); shrink++) {
                c = shrink < 4 ? Curve.fit(i, px[i], pz[i], dirIn, dirOut, c.trim() * 0.6,
                        c.inset() * 0.6) : null;
            }
            if (c != null) {
                stepped[i] = rise > LEVEL;
                out.add(c);
            }
        }
        return out;
    }

    /**
     * Whether the body can walk a curve, checked the way a straight line is ({@link #walk}):
     * stepping up and down slabs and stairs on the way round, never more than a step at a
     * time nor more than a step off the height the route has there, and still so a little to
     * either side of it ({@link #SLACK}), as the follower doesn't keep to it exactly.
     */
    private boolean walkable(Curve c, List<PathStep> path, double[] floorY, double[] along) {
        // The places along the curve, which way is across it there and the height the route
        // has there are the same whichever side of it is walked: work them out once.
        int steps = 4 * (c.x().length - 1);
        if (curveX.length < steps + 1) {
            curveX = new double[steps + 1];
            curveZ = new double[steps + 1];
            acrossX = new double[steps + 1];
            acrossZ = new double[steps + 1];
            guesses = new double[steps + 1];
        }
        double from = along[c.node()] - c.trim();
        int j = 0;
        for (int k = 0; k <= steps; k++) {
            double f = (double) k / steps;
            double[] p = c.at(f);
            double[] ahead = c.at(Math.min(1, f + 1e-3));
            double[] behind = c.at(Math.max(0, f - 1e-3));
            double dx = ahead[0] - behind[0];
            double dz = ahead[1] - behind[1];
            double dl = Math.hypot(dx, dz);
            curveX[k] = p[0];
            curveZ[k] = p[1];
            acrossX[k] = dz / dl;
            acrossZ[k] = dx / dl;
            double s = from + 2 * f * c.trim();
            // The node the place is past: s only grows along the curve, so on from the last.
            while (j + 1 < along.length && along[j + 1] <= s) {
                j++;
            }
            if (k == 0) {
                firstNode = j;
            }
            int j2 = Math.min(j + 1, along.length - 1);
            double t = along[j2] > along[j] ? (s - along[j]) / (along[j2] - along[j]) : 0;
            guesses[k] = floorY[j] + Math.max(0, Math.min(1, t)) * (floorY[j2] - floorY[j]);
        }
        return walkable(path, steps, 0) && walkable(path, steps, SLACK)
                && walkable(path, steps, -SLACK);
    }

    /**
     * The same, along the curve {@link #walkable(Curve, List, double[], double[])} laid out
     * moved sideways by {@code side} (positive to its left).
     */
    private boolean walkable(List<PathStep> path, int steps, double side) {
        double y = Double.NaN;
        double lastX = 0;
        double lastZ = 0;
        double low = Double.POSITIVE_INFINITY;
        double high = Double.NEGATIVE_INFINITY;
        for (int k = 0; k <= steps; k++) {
            // Left of the way along, seen from above (x east, z south).
            double x = curveX[k] + acrossX[k] * side;
            double z = curveZ[k] - acrossZ[k] * side;
            double guess = guesses[k];
            if (k == 0) {
                int cx = (int) Math.floor(x);
                int cz = (int) Math.floor(z);
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPoint cell = new BlockPoint(cx, path.get(firstNode).pos().y() + dy, cz);
                    double e = floor(cell, x - cx, z - cz);
                    if (!Double.isNaN(e) && (Double.isNaN(y)
                            || Math.abs(e - guess) < Math.abs(y - guess))) {
                        y = e;
                    }
                }
                if (Double.isNaN(y)) {
                    return false;
                }
            } else {
                double next = walk(lastX, y, lastZ, x, z);
                if (Double.isNaN(next) || Math.abs(next - y) > STEP_HEIGHT + 1e-9) {
                    return false;
                }
                y = next;
            }
            if (Math.abs(y - guess) > STEP_HEIGHT) {
                return false;
            }
            lastX = x;
            lastZ = z;
            low = Math.min(low, y);
            high = Math.max(high, y);
        }
        if (side == 0) {
            rise = high - low;
        }
        return true;
    }

    private static boolean narrowDrop(Side s) {
        return s.edge() == Edge.DROP && s.width() < 1.0;
    }

    /**
     * The speed a corner can be taken at. Turning leaves the old velocity to die away, which
     * carries the body to the outside by about {@link #DRIFT} times the speed across the new
     * direction. That only matters when the outside is an edge to fall off; sharp corners are
     * taken at {@code sharp} either way (walking, unless the corner is rounded off).
     */
    private static double cornerLimit(double[] in, double[] out, Side left, Side right,
            double sprint, double sharp) {
        if (in == null || out == null) {
            return sprint;
        }
        double cross = in[0] * out[1] - in[1] * out[0];
        double dot = in[0] * out[0] + in[1] * out[1];
        double angle = Math.toDegrees(Math.atan2(Math.abs(cross), dot));
        if (angle <= CORNER) {
            return sprint;
        }
        double v = angle >= SHARP_CORNER ? sharp : sprint;
        // Seen from above with x east and z south, a positive cross product turns right, so the
        // outside of the corner is on the left.
        Side outside = cross > 0 ? left : right;
        if (outside.edge() == Edge.DROP) {
            double across = Math.sin(Math.toRadians(Math.min(angle, 90)));
            v = Math.min(v, Math.max(SLOWEST, outside.width() / (DRIFT * across)));
        }
        return v;
    }

    // ---- segments ----

    private static List<Segment> segments(List<PathStep> path, double[] floorY,
            boolean[] jump) {
        List<Segment> out = new ArrayList<>();
        int i = 1;
        while (i < path.size()) {
            Kind kind = kind(path.get(i).via());
            if (kind == Kind.STRAIGHT && jump[i]) {
                // A walk the grid allows but the body can't step: onto stairs the grid sees as
                // a step up, but whose real shape is higher than a step (see fit).
                kind = Kind.JUMP_UP;
            }
            int from = i - 1;
            int to = i;
            if (kind == Kind.STRAIGHT) {
                BlockPoint a = path.get(from).pos();
                BlockPoint b = path.get(to).pos();
                int dx = b.x() - a.x();
                int dz = b.z() - a.z();
                while (to + 1 < path.size()
                        && kind(path.get(to + 1).via()) == Kind.STRAIGHT
                        && path.get(to + 1).via() == path.get(to).via()
                        && path.get(to + 1).pos().x() - path.get(to).pos().x() == dx
                        && path.get(to + 1).pos().z() - path.get(to).pos().z() == dz
                        && floorY[to + 1] == floorY[to] && !jump[to + 1]) {
                    to++;
                }
            }
            out.add(new Segment(from, to, kind));
            i = to + 1;
        }
        return out;
    }

    private static Kind kind(MoveType via) {
        return switch (via) {
            case WALK, DIAGONAL -> Kind.STRAIGHT;
            case JUMP_UP -> Kind.JUMP_UP;
            case DROP -> Kind.DROP;
            case CLIMB -> Kind.CLIMB;
            case SWIM -> Kind.SWIM;
            case WARP, TRANSMIT -> throw new IllegalArgumentException(
                    "A walk plan can't hold a teleport");
        };
    }
}
