package astar.pathing;

import astar.core.Heuristic;
import astar.core.MoveGraph;
import astar.core.MoveType;
import astar.core.Pos;
import astar.core.TurnPenalty;
import astar.core.TurningCostModel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * Etherwarp hops (Hypixel's Aspect of the Void: sneak, aim at a block, right-click, and land on
 * top of it), worked out ahead of time over a map's {@link NavGraph} and added to it as moves,
 * so a search picks walking and hops together and a trip is one ordinary A* over the result.
 *
 * <p>A hop is kept only if it is a sure one. It goes from one standing spot to another,
 * aiming at a point on the target floor block; it is checked with the eye at the middle of the
 * spot and nudged {@link #EYE_JITTER} along x and z, at both sneaking eye heights
 * ({@link #EYE_HEIGHTS}), and with the aim point nudged
 * {@link #AIM_JITTER} across the face it is on, and every one of those rays must hit that block
 * first and within range. So a player who stands a little off the middle, or aims a little
 * off, still lands where the route says.
 *
 * <p>Hops start and end at anchors: standing spots on a solid floor with a free block above the
 * head, at most one per {@code spacing}-sized box. Every hop costs {@code cost} (the stop,
 * sneak, aim, click and the server's answer, in the graph's units: blocks walked), whatever
 * its length, and hops shorter than {@code minLength} blocks are left to walking.
 *
 * <p>What the rays hit is read from the {@link BlockType}s: anything but air and water stops
 * the crosshair (partial blocks as if they were full, so a ray that might clip a slab counts as
 * hitting it). Blocks imported as air that the crosshair still targets, such as flowers, grass
 * and torches, aren't known to the map, so a hop across them can fail.
 */
public final class WarpHops {
    /** Hypixel's etherwarp range without Tuned Transmission, in blocks from the eye. */
    public static final double DEFAULT_RANGE = 57;
    /** What a hop costs, in blocks walked: about a second of stopping, aiming and teleporting. */
    public static final double DEFAULT_COST = 8;
    /**
     * Hops shorter than this (blocks, feet to feet) are walked: a player doesn't stop to
     * etherwarp a few blocks, and it looks botted.
     */
    public static final double DEFAULT_MIN_LENGTH = 35;
    /**
     * What turning onto a hop, or off it onto the next stretch, costs per 45 degrees, in blocks
     * walked: swinging the view round takes about half a second for 90 degrees.
     */
    public static final double DEFAULT_TURN = 1;
    /** At most one anchor per box this many blocks across. */
    public static final int DEFAULT_SPACING = 4;

    /**
     * The eye's heights above the feet while sneaking that a hop is checked from: 1.54 as a 1.8
     * server (Hypixel's) sees a sneaking player, and 1.27 as clients since 1.14 do. Whichever
     * the server casts from, a sure hop lands the same.
     */
    static final double[] EYE_HEIGHTS = {1.54, 1.27};
    /** The one the aim is worked out from. */
    static final double EYE_HEIGHT = EYE_HEIGHTS[0];
    /**
     * How far off the middle of the block the eye is checked, along x and z: as far as /goto
     * may stop from the end of a walk (the executor's arrival radius).
     */
    static final double EYE_JITTER = 0.3;
    /** How far across the face the aim point is checked, from where the hop aims. */
    static final double AIM_JITTER = 0.25;

    private final NavGraph walking;
    private final double range;
    private final double cost;
    private final int anchors;
    // Hop k goes from cell hopFrom[k] to cell hopTo[k] (walking graph ids), aiming at aim[3k..3k+2]
    private final int[] hopFrom;
    private final int[] hopTo;
    private final float[] aim;
    private final double buildMs;
    private final long raysCast;

    private WarpHops(NavGraph walking, double range, double cost, int anchors, int[] hopFrom,
            int[] hopTo, float[] aim, double buildMs, long raysCast) {
        this.walking = walking;
        this.range = range;
        this.cost = cost;
        this.anchors = anchors;
        this.hopFrom = hopFrom;
        this.hopTo = hopTo;
        this.aim = aim;
        this.buildMs = buildMs;
        this.raysCast = raysCast;
    }

    /**
     * What the hops {@link #build(NavGraph, ArrayBlockView) built with the defaults} depend on
     * besides the world and the graph: change it when how they're found changes.
     */
    public static final String CACHE_KEY = "etherwarp 1; range " + DEFAULT_RANGE + "; cost "
            + DEFAULT_COST + "; min " + DEFAULT_MIN_LENGTH + "; spacing " + DEFAULT_SPACING;

    /**
     * Writes these hops, for {@link #read} to load for the same world and graph instead of
     * building them again.
     */
    public void write(java.io.DataOutputStream out, ArrayBlockView world) throws java.io.IOException {
        HopFile.header(out, world, walking, CACHE_KEY);
        out.writeDouble(range);
        out.writeDouble(cost);
        out.writeInt(anchors);
        out.writeInt(hopFrom.length);
        HopFile.ints(out, hopFrom);
        HopFile.ints(out, hopTo);
        HopFile.floats(out, aim);
    }

    /** Hops written by {@link #write} for {@code world} and {@code walking}, or null if not. */
    public static WarpHops read(java.io.DataInputStream in, ArrayBlockView world,
            NavGraph walking) throws java.io.IOException {
        long t0 = System.nanoTime();
        if (!HopFile.header(in, world, walking, CACHE_KEY)) {
            return null;
        }
        double range = in.readDouble();
        double cost = in.readDouble();
        int anchors = in.readInt();
        int n = in.readInt();
        int[] from = HopFile.ints(in, n);
        int[] to = HopFile.ints(in, n);
        float[] aim = HopFile.floats(in, 3 * n);
        return new WarpHops(walking, range, cost, anchors, from, to, aim,
                (System.nanoTime() - t0) / 1e6, 0);
    }

    /** One hop out of an anchor, found while building. */
    private record Hop(int to, float ax, float ay, float az) {}

    /**
     * Finds every sure hop between the anchors of {@code walking}'s cells.
     *
     * @param world the blocks {@code walking} was built on
     */
    public static WarpHops build(NavGraph walking, ArrayBlockView world, double range, double cost,
            double minLength, int spacing) {
        long t0 = System.nanoTime();
        long[] cells = walking.positions();

        // Anchors: the first good standing spot met in each box.
        Map<Long, Integer> boxes = new HashMap<>();
        for (int v = 0; v < cells.length; v++) {
            long p = cells[v];
            int x = Pos.x(p), y = Pos.y(p), z = Pos.z(p);
            if (!goodSpot(world, x, y, z)) {
                continue;
            }
            boxes.putIfAbsent(Pos.pack(Math.floorDiv(x, spacing), Math.floorDiv(y, spacing),
                    Math.floorDiv(z, spacing)), v);
        }
        int[] anchor = boxes.values().stream().mapToInt(Integer::intValue).sorted().toArray();

        // Anchors bucketed by range-sized boxes, to find the ones in reach quickly.
        int bucket = (int) Math.ceil(range);
        Map<Long, List<Integer>> near = new HashMap<>();
        for (int v : anchor) {
            long p = cells[v];
            near.computeIfAbsent(Pos.pack(Math.floorDiv(Pos.x(p), bucket),
                    Math.floorDiv(Pos.y(p), bucket), Math.floorDiv(Pos.z(p), bucket)),
                    k -> new ArrayList<>()).add(v);
        }

        long[] rays = new long[anchor.length];
        @SuppressWarnings({"unchecked", "rawtypes"})
        List<Hop>[] found = new List[anchor.length];
        double minSq = minLength * minLength;
        IntStream.range(0, anchor.length).parallel().forEach(i -> {
            int a = anchor[i];
            long p = cells[a];
            int ax = Pos.x(p), ay = Pos.y(p), az = Pos.z(p);
            int bx = Math.floorDiv(ax, bucket), by = Math.floorDiv(ay, bucket),
                    bz = Math.floorDiv(az, bucket);
            List<Hop> hops = new ArrayList<>();
            Ray ray = new Ray(world, range);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        List<Integer> there = near.get(Pos.pack(bx + dx, by + dy, bz + dz));
                        if (there == null) {
                            continue;
                        }
                        for (int b : there) {
                            long q = cells[b];
                            int tx = Pos.x(q), ty = Pos.y(q), tz = Pos.z(q);
                            double lx = tx - ax, ly = ty - ay, lz = tz - az;
                            if (b == a || lx * lx + ly * ly + lz * lz < minSq) {
                                continue;
                            }
                            float[] at = sureAim(ray, ax, ay, az, tx, ty - 1, tz);
                            if (at != null) {
                                hops.add(new Hop(b, at[0], at[1], at[2]));
                            }
                        }
                    }
                }
            }
            found[i] = hops;
            rays[i] = ray.cast;
        });

        int total = Arrays.stream(found).mapToInt(List::size).sum();
        int[] from = new int[total];
        int[] to = new int[total];
        float[] aim = new float[total * 3];
        int k = 0;
        for (int i = 0; i < anchor.length; i++) {
            for (Hop h : found[i]) {
                from[k] = anchor[i];
                to[k] = h.to();
                aim[3 * k] = h.ax();
                aim[3 * k + 1] = h.ay();
                aim[3 * k + 2] = h.az();
                k++;
            }
        }
        double ms = (System.nanoTime() - t0) / 1e6;
        return new WarpHops(walking, range, cost, anchor.length, from, to, aim, ms,
                Arrays.stream(rays).sum());
    }

    /** With the defaults. */
    public static WarpHops build(NavGraph walking, ArrayBlockView world) {
        return build(walking, world, DEFAULT_RANGE, DEFAULT_COST, DEFAULT_MIN_LENGTH,
                DEFAULT_SPACING);
    }

    /** A spot to warp from or to: a solid floor, room for the body, and a free block above it. */
    private static boolean goodSpot(ArrayBlockView world, int x, int y, int z) {
        return world.blockAt(x, y - 1, z) == BlockType.SOLID && clear(world, x, y, z)
                && clear(world, x, y + 1, z) && clear(world, x, y + 2, z);
    }

    private static boolean clear(ArrayBlockView world, int x, int y, int z) {
        return world.inBounds(x, y, z) && world.blockAt(x, y, z) == BlockType.AIR;
    }

    /**
     * Where to aim, standing in cell (ax, ay, az), to land on block (tx, ty, tz) every time: a
     * point on one of its faces the eye can see, such that every jittered ray hits that block
     * first; null if there's none.
     */
    static float[] sureAim(Ray ray, int ax, int ay, int az, int tx, int ty, int tz) {
        double ex = ax + 0.5, ey = ay + EYE_HEIGHT, ez = az + 0.5;
        // The faces the eye can see: the top when it is above it, and the sides facing it.
        // Each is aimed at its middle, a hair inside the block.
        double[][] faces = new double[3][];
        int n = 0;
        if (ay + EYE_HEIGHTS[1] > ty + 1) {
            faces[n++] = new double[] {tx + 0.5, ty + 0.98, tz + 0.5, 1, 0, 0, 0, 0, 1};
        }
        double cx = tx + 0.5, cz = tz + 0.5;
        if (Math.abs(ex - cx) >= Math.abs(ez - cz)) {
            if (Math.abs(ex - cx) > 0.5) {
                faces[n++] = new double[] {ex > cx ? tx + 0.98 : tx + 0.02, ty + 0.5, cz,
                        0, 1, 0, 0, 0, 1};
            }
        } else if (Math.abs(ez - cz) > 0.5) {
            faces[n++] = new double[] {cx, ty + 0.5, ez > cz ? tz + 0.98 : tz + 0.02,
                    1, 0, 0, 0, 1, 0};
        }
        for (int f = 0; f < n; f++) {
            double[] face = faces[f];
            if (sure(ray, ex, ey, ez, face, tx, ty, tz)) {
                return new float[] {(float) face[0], (float) face[1], (float) face[2]};
            }
        }
        return null;
    }

    /** Whether every jittered ray from around the eye to around the aim point hits the block. */
    private static boolean sure(Ray ray, double ex, double ey, double ez, double[] face,
            int tx, int ty, int tz) {
        // The middle ray first: most candidates fail there.
        if (!ray.hits(ex, ey, ez, face[0], face[1], face[2], tx, ty, tz)) {
            return false;
        }
        double[][] eyes = {{-EYE_JITTER, 0}, {EYE_JITTER, 0}, {0, -EYE_JITTER}, {0, EYE_JITTER},
                {0, 0}};
        double[][] aims = {{0, 0}, {-AIM_JITTER, 0}, {AIM_JITTER, 0}, {0, -AIM_JITTER},
                {0, AIM_JITTER}};
        double feet = ey - EYE_HEIGHT;
        for (double height : EYE_HEIGHTS) {
            for (double[] e : eyes) {
                for (double[] m : aims) {
                    if (height == EYE_HEIGHT && e[0] == 0 && e[1] == 0 && m[0] == 0
                            && m[1] == 0) {
                        continue;
                    }
                    double px = face[0] + m[0] * face[3] + m[1] * face[6];
                    double py = face[1] + m[0] * face[4] + m[1] * face[7];
                    double pz = face[2] + m[0] * face[5] + m[1] * face[8];
                    if (!ray.hits(ex + e[0], feet + height, ez + e[1], px, py, pz, tx, ty, tz)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** Casts crosshair rays through the blocks, counting them. One per thread. */
    static final class Ray {
        private final ArrayBlockView world;
        private final double range;
        long cast;

        Ray(ArrayBlockView world, double range) {
            this.world = world;
            this.range = range;
        }

        /**
         * Whether the ray from the eye through (px, py, pz) first hits block (tx, ty, tz), within
         * range. Walks the blocks along it one by one (Amanatides-Woo).
         */
        boolean hits(double ex, double ey, double ez, double px, double py, double pz,
                int tx, int ty, int tz) {
            cast++;
            double dx = px - ex, dy = py - ey, dz = pz - ez;
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            dx /= len;
            dy /= len;
            dz /= len;
            int x = (int) Math.floor(ex), y = (int) Math.floor(ey), z = (int) Math.floor(ez);
            int sx = dx > 0 ? 1 : -1, sy = dy > 0 ? 1 : -1, sz = dz > 0 ? 1 : -1;
            double deltaX = dx == 0 ? Double.MAX_VALUE : Math.abs(1 / dx);
            double deltaY = dy == 0 ? Double.MAX_VALUE : Math.abs(1 / dy);
            double deltaZ = dz == 0 ? Double.MAX_VALUE : Math.abs(1 / dz);
            double nextX = dx == 0 ? Double.MAX_VALUE : (dx > 0 ? x + 1 - ex : ex - x) * deltaX;
            double nextY = dy == 0 ? Double.MAX_VALUE : (dy > 0 ? y + 1 - ey : ey - y) * deltaY;
            double nextZ = dz == 0 ? Double.MAX_VALUE : (dz > 0 ? z + 1 - ez : ez - z) * deltaZ;
            double t = 0;
            while (t <= range) {
                if (stops(x, y, z)) {
                    return x == tx && y == ty && z == tz;
                }
                if (nextX <= nextY && nextX <= nextZ) {
                    t = nextX;
                    x += sx;
                    nextX += deltaX;
                } else if (nextY <= nextZ) {
                    t = nextY;
                    y += sy;
                    nextY += deltaY;
                } else {
                    t = nextZ;
                    z += sz;
                    nextZ += deltaZ;
                }
            }
            return false;
        }

        /** Whether the crosshair stops at this block: anything but air and water. */
        private boolean stops(int x, int y, int z) {
            if (!world.inBounds(x, y, z)) {
                return true;
            }
            return switch (world.blockAt(x, y, z)) {
                case AIR, WATER, FLOWING_WATER, BUBBLE_UP, BUBBLE_DOWN -> false;
                default -> true;
            };
        }
    }

    /** How many anchors hops were looked for between. */
    public int anchors() {
        return anchors;
    }

    /** How many sure hops there are. */
    public int count() {
        return hopFrom.length;
    }

    /** How many rays were cast while building. */
    public long raysCast() {
        return raysCast;
    }

    public double buildMs() {
        return buildMs;
    }

    public double range() {
        return range;
    }

    public double cost() {
        return cost;
    }

    /**
     * The same hops on {@code g}, a patch of the graph they were built on ({@link
     * NavGraph#patch}), which keeps every cell's id; null if {@code g} numbers its cells
     * differently (built afresh). Hops near the changed blocks may no longer land: /goto checks
     * each one in the game before casting it.
     */
    public WarpHops onto(NavGraph g) {
        if (g == walking) {
            return this;
        }
        long[] was = walking.positions();
        long[] now = g.positions();
        if (now.length < was.length || Arrays.mismatch(was, 0, was.length, now, 0,
                was.length) >= 0) {
            return null;
        }
        return new WarpHops(g, range, cost, anchors, hopFrom, hopTo, aim, 0, 0);
    }

    /** The walking graph the hops were added to. */
    public NavGraph walking() {
        return walking;
    }

    /**
     * Where hop from cell {@code from} to cell {@code to} (packed positions) aims, as {x, y, z}
     * in the map's coordinates, or null if there's no such hop.
     */
    public float[] aim(long from, long to) {
        int k = index(from, to);
        return k < 0 ? null : new float[] {aim[3 * k], aim[3 * k + 1], aim[3 * k + 2]};
    }

    /** The number of the hop from {@code from} to {@code to} (packed), or -1 if there's none. */
    public int index(long from, long to) {
        int a = walking.cell(from), b = walking.cell(to);
        for (int k = 0; k < hopFrom.length; k++) {
            if (hopFrom[k] == a && hopTo[k] == b) {
                return k;
            }
        }
        return -1;
    }

    /** The walking graph with the hops added, as moves of type {@link MoveType#WARP}. */
    public MoveGraph graph() {
        return graph(java.util.Set.of());
    }

    /** The same, leaving out the hops numbered in {@code skip} ({@link #index}). */
    public MoveGraph graph(java.util.Set<Integer> skip) {
        return HopGraph.of(walking, List.of(moves(skip)));
    }

    /** The hops as moves to add to the walking graph, leaving out those in {@code skip}. */
    public HopGraph.Moves moves(java.util.Set<Integer> skip) {
        double[] price = new double[hopFrom.length];
        Arrays.fill(price, cost);
        return new HopGraph.Moves(MoveType.WARP, hopFrom, hopTo, price, skip);
    }

    /**
     * Turn costs for searches over {@link #graph()}: {@code walkPerTurn} per 45 degrees between
     * walking steps, as the pathfinder charges, and {@code hopPerTurn} for the turn onto a hop
     * and off it, which is a swing of the view and not a curve in the walk. A route then lines
     * up for a hop by walking a little, instead of zigzagging hop to hop to wherever a sure
     * landing is. Charged from each cell's cheapest way in (one node per cell).
     */
    public static TurningCostModel turns(double walkPerTurn, double hopPerTurn) {
        return new TurningCostModel() {
            @Override
            public double cost(long from, long to, MoveType type) {
                throw new IllegalStateException("priced by the graph");
            }

            @Override
            public double cost(long before, long from, long to, MoveType type) {
                throw new IllegalStateException("priced by the graph");
            }

            @Override
            public double turnCost(long before, long from, long to, MoveType type) {
                boolean hop = type == MoveType.WARP || type == MoveType.TRANSMIT
                        || far(before, from);
                return (hop ? hopPerTurn : walkPerTurn) * TurnPenalty.turn(before, from, to);
            }
        };
    }

    /** Whether a move from a to b is longer than any walking step. */
    private static boolean far(long a, long b) {
        return Math.abs(Pos.x(a) - Pos.x(b)) > 2 || Math.abs(Pos.z(a) - Pos.z(b)) > 2;
    }

    /**
     * A heuristic that stays admissible with hops: no move covers ground more cheaply than a
     * hop of full reach (its cost over the longest feet-to-feet distance a hop can span), so
     * that rate times the straight-line distance never overestimates, given walking moves that
     * cost at least {@code walkFloor} per block of straight-line distance.
     */
    public Heuristic heuristic(double walkFloor) {
        // Feet to feet can be a little longer than eye to block: the eye is up to 1.54 above
        // the feet, and the landing a block above the block hit.
        double rate = Math.min(walkFloor, cost / (range + 3));
        return (dx, dy, dz) -> rate * Math.sqrt((double) dx * dx + (double) dy * dy
                + (double) dz * dz);
    }
}
