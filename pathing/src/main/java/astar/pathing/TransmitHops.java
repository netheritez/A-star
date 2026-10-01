package astar.pathing;

import astar.core.MoveType;
import astar.core.Pos;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

/**
 * Instant Transmission hops (Hypixel's Aspect of the Void, right-clicked without sneaking),
 * worked out ahead of time over a map's {@link NavGraph} and added to it as moves, like {@link
 * WarpHops}.
 *
 * <p>Where a cast lands, as the player's casts on Hypixel showed: the eye moves along the view
 * up to {@link #DEFAULT_RANGE} blocks, stopping where the line meets a block; the player ends
 * up in the block the eye's end point is in, lowered to the feet, at its middle, pushed up out
 * of the floor if it's in it, and then falls straight down onto whatever is below. So a cast
 * aimed at the floor lands where the line meets it, and one over open ground lands a full range
 * away and drops.
 *
 * <p>Only casts that are sure are kept, as with etherwarp: the landing must come out the same
 * with the eye off the middle of the spot by {@link #EYE_JITTER} along x and z, with the view
 * off by {@link #ANGLE_JITTER} degrees either way, and with the feet taken either 1.62 or 1 block
 * under the eye's end (the casts fit both). Casts whose line meets a wall or a ceiling are left
 * out (where they stop isn't pinned down yet), and so is any line that passes a block other
 * than air or a full block, whose shape the map doesn't know well enough.
 *
 * <p>Casts start from anchors (at most one per {@code spacing}-sized box, and every spot a cast
 * lands on, so casts chain), in {@link #YAWS} directions at the pitches in {@link #PITCHES}.
 */
public final class TransmitHops {
    /** How far a cast moves the eye: 8 blocks and 4 from Tuned Transmission. */
    public static final double DEFAULT_RANGE = 12;
    /** What a cast costs, in blocks walked: the click and the server's answer. */
    public static final double DEFAULT_COST = 5;
    /**
     * Casts landing closer than this (blocks, feet to feet) are walked: a player casts for the
     * full reach, not a few blocks.
     */
    public static final double DEFAULT_MIN_LENGTH = 10;
    /** At most one anchor (besides landing spots) per box this many blocks across. */
    public static final int DEFAULT_SPACING = 3;
    /** The furthest a cast may drop the player after landing, in blocks. */
    public static final int MAX_FALL = 12;

    /**
     * What each cast after the first of an air chain ({@link Flights}) costs, in blocks
     * walked: the ticks from one click to the next.
     */
    public static final double CHAIN_COST = (HandTurn.chainWait(0) + 1) * 5.612 / 20;

    static final int YAWS = 32;
    static final double[] PITCHES = {-40, -25, -12, 0, 12, 25, 40, 55};
    /** The eye's height above the feet, standing (no sneaking for Instant Transmission). */
    static final double EYE_HEIGHT = 1.62;
    /** How far under the eye's end point the feet may be taken (the casts fit both). */
    static final double[] FEET_BELOW = {1.62, 1.0};
    static final double EYE_JITTER = 0.3;
    static final double ANGLE_JITTER = 0.5;
    /** A line meeting a block closer than this to the eye isn't cast (the server refuses). */
    static final double TOO_CLOSE = 1.8;
    /**
     * Ticks from a teleport showing to the next click of an air chain that keeps its view:
     * the very next tick, as a player spamming the click does.
     */
    public static final int CHAIN_WAIT = HandTurn.chainWait(0);

    /**
     * How far the player may have fallen since the last teleport when the next cast of a chain
     * goes off, the click coming {@code wait} ticks after the teleport shows: that far, or a
     * tick either way (the server may have the player a tick behind, or answer a tick late).
     */
    public static double[] chainFalls(int wait) {
        return wait < FALLS.length ? FALLS[wait].clone() : falls(wait);
    }

    /** {@link #chainFalls} not to be changed, shared: for the planner's inner loops. */
    static double[] chainFallsShared(int wait) {
        return wait < FALLS.length ? FALLS[wait] : falls(wait);
    }

    private static double[] falls(int wait) {
        return new double[] {HandTurn.fall(Math.max(0, wait - 1)), HandTurn.fall(wait),
            HandTurn.fall(wait + 1)};
    }

    /** {@link #chainFalls} worked out once for the waits a chain takes. */
    private static final double[][] FALLS = new double[64][];

    static {
        for (int w = 0; w < FALLS.length; w++) {
            FALLS[w] = falls(w);
        }
    }

    /**
     * How far above the rise the eye's end is aimed for a click {@code wait} ticks after the
     * teleport: halfway between the most and the least the feet may have fallen, so all of
     * them land in one block. In the air the feet go 1.62 under the eye's end (the rule the
     * Hypixel casts showed), not either of {@link #FEET_BELOW}.
     */
    static double chainLift(int wait) {
        double[] f = chainFalls(wait);
        return (f[2] + 1 + f[0]) / 2;
    }

    /**
     * Each cast of a chain is clicked with the view settled on it, so it is checked only this
     * far off, in degrees.
     */
    static final double CHAIN_ANGLE_JITTER = 0.1;
    /** Sprinting speed per tick, to price falling in blocks walked. */
    static final double SPRINT_PER_TICK = 5.612 / 20;

    private final NavGraph walking;
    private final double range;
    private final int anchors;
    private final int[] hopFrom;
    private final int[] hopTo;
    private final float[] yaw;
    private final float[] pitch;
    /** How many casts hop k chains in the air (1: a single cast). */
    private final byte[] chain;
    private final double[] cost;
    private final double buildMs;

    private TransmitHops(NavGraph walking, double range, int anchors, int[] hopFrom, int[] hopTo,
            float[] yaw, float[] pitch, byte[] chain, double[] cost, double buildMs) {
        this.walking = walking;
        this.range = range;
        this.anchors = anchors;
        this.hopFrom = hopFrom;
        this.hopTo = hopTo;
        this.yaw = yaw;
        this.pitch = pitch;
        this.chain = chain;
        this.cost = cost;
        this.buildMs = buildMs;
    }

    /**
     * What the casts {@link #build(NavGraph, ArrayBlockView) built with the defaults} depend
     * on besides the world and the graph: change it when how they're found changes.
     */
    public static final String CACHE_KEY = "transmission 1; range " + DEFAULT_RANGE + "; cost "
            + DEFAULT_COST + "; min " + DEFAULT_MIN_LENGTH + "; spacing " + DEFAULT_SPACING;

    /**
     * Writes these casts, for {@link #read} to load for the same world and graph instead of
     * building them again.
     */
    public void write(java.io.DataOutputStream out, ArrayBlockView world) throws java.io.IOException {
        HopFile.header(out, world, walking, CACHE_KEY);
        out.writeDouble(range);
        out.writeInt(anchors);
        out.writeInt(hopFrom.length);
        HopFile.ints(out, hopFrom);
        HopFile.ints(out, hopTo);
        HopFile.floats(out, yaw);
        HopFile.floats(out, pitch);
        out.write(chain);
        for (double c : cost) {
            out.writeDouble(c);
        }
    }

    /** Casts written by {@link #write} for {@code world} and {@code walking}, or null if not. */
    public static TransmitHops read(java.io.DataInputStream in, ArrayBlockView world,
            NavGraph walking) throws java.io.IOException {
        long t0 = System.nanoTime();
        if (!HopFile.header(in, world, walking, CACHE_KEY)) {
            return null;
        }
        double range = in.readDouble();
        int anchors = in.readInt();
        int n = in.readInt();
        int[] from = HopFile.ints(in, n);
        int[] to = HopFile.ints(in, n);
        float[] yaw = HopFile.floats(in, n);
        float[] pitch = HopFile.floats(in, n);
        byte[] chain = new byte[n];
        in.readFully(chain);
        double[] cost = new double[n];
        for (int k = 0; k < n; k++) {
            cost[k] = in.readDouble();
        }
        return new TransmitHops(walking, range, anchors, from, to, yaw, pitch, chain, cost,
                (System.nanoTime() - t0) / 1e6);
    }

    private record Hop(int to, float yaw, float pitch, int chain, double cost) {}

    /** With the defaults. */
    public static TransmitHops build(NavGraph walking, ArrayBlockView world) {
        return build(walking, world, DEFAULT_RANGE, DEFAULT_COST, DEFAULT_MIN_LENGTH,
                DEFAULT_SPACING);
    }

    /**
     * Finds every sure cast out of the anchors of {@code walking}'s cells, and out of every spot
     * one lands on.
     *
     * @param world the blocks {@code walking} was built on
     */
    public static TransmitHops build(NavGraph walking, ArrayBlockView world, double range,
            double castCost, double minLength, int spacing) {
        long t0 = System.nanoTime();
        long[] cells = walking.positions();
        java.util.Map<Long, Integer> boxes = new java.util.HashMap<>();
        for (int v = 0; v < cells.length; v++) {
            long p = cells[v];
            int x = Pos.x(p), y = Pos.y(p), z = Pos.z(p);
            if (standing(world, x, y, z)) {
                boxes.putIfAbsent(Pos.pack(Math.floorDiv(x, spacing), Math.floorDiv(y, spacing),
                        Math.floorDiv(z, spacing)), v);
            }
        }
        Set<Integer> seen = ConcurrentHashMap.newKeySet();
        int[] wave = boxes.values().stream().mapToInt(Integer::intValue).sorted().toArray();
        seen.addAll(boxes.values());
        ConcurrentHashMap<Integer, List<Hop>> found = new ConcurrentHashMap<>();
        double minSq = minLength * minLength;
        // Out of the anchors, then out of every spot those land on that isn't done yet, and so on.
        while (wave.length > 0) {
            int[] now = wave;
            Set<Integer> next = ConcurrentHashMap.newKeySet();
            IntStream.range(0, now.length).parallel().forEach(i -> {
                int a = now[i];
                List<Hop> hops = casts(walking, world, cells[a], range, castCost, minSq);
                found.put(a, hops);
                for (Hop h : hops) {
                    if (seen.add(h.to())) {
                        next.add(h.to());
                    }
                }
            });
            wave = next.stream().mapToInt(Integer::intValue).sorted().toArray();
        }
        int[] from = found.keySet().stream().mapToInt(Integer::intValue).sorted().toArray();
        int total = found.values().stream().mapToInt(List::size).sum();
        int[] hf = new int[total], ht = new int[total];
        float[] yw = new float[total], pt = new float[total];
        byte[] ch = new byte[total];
        double[] c = new double[total];
        int k = 0;
        for (int a : from) {
            for (Hop h : found.get(a)) {
                hf[k] = a;
                ht[k] = h.to();
                yw[k] = h.yaw();
                pt[k] = h.pitch();
                ch[k] = (byte) h.chain();
                c[k] = h.cost();
                k++;
            }
        }
        return new TransmitHops(walking, range, from.length, hf, ht, yw, pt, ch, c,
                (System.nanoTime() - t0) / 1e6);
    }

    /** The sure casts out of spot {@code p}, one per spot landed on (the first direction found). */
    private static List<Hop> casts(NavGraph walking, ArrayBlockView world, long p, double range,
            double castCost, double minSq) {
        int ax = Pos.x(p), ay = Pos.y(p), az = Pos.z(p);
        List<Hop> hops = new ArrayList<>();
        if (!standing(world, ax, ay, az)) {
            return hops;
        }
        int[] landing = new int[4];
        java.util.Set<Integer> reached = new java.util.HashSet<>();
        for (double pitch : PITCHES) {
            for (int i = 0; i < YAWS; i++) {
                double yaw = i * 360.0 / YAWS - 180;
                if (!sure(world, ax + 0.5, ay, az + 0.5, yaw, pitch, range, landing)) {
                    continue;
                }
                int lx = landing[0], ly = landing[1], lz = landing[2];
                double dx = lx - ax, dy = ly - ay, dz = lz - az;
                if (dx * dx + dy * dy + dz * dz < minSq) {
                    continue;
                }
                int to = walking.cell(Pos.pack(lx, ly, lz));
                if (to < 0 || !reached.add(to)) {
                    continue;
                }
                hops.add(new Hop(to, (float) yaw, (float) pitch, 1,
                        castCost + fallTicks(landing[3]) * SPRINT_PER_TICK));
            }
        }
        return hops;
    }

    /** The pitch that makes each cast of a chain climb {@code rise} blocks. */
    static double chainPitch(int rise, double range) {
        return chainPitch(rise, range, CHAIN_WAIT);
    }

    /** The same for a click {@code wait} ticks after the teleport shows. */
    static double chainPitch(int rise, double range, int wait) {
        return -Math.toDegrees(Math.asin((rise + chainLift(wait)) / range));
    }

    static final int FAILED = -1;
    static final int HIT = 0;
    static final int OPEN = 1;

    /**
     * One cast of a chain from feet (fx, fy, fz): {@link #OPEN} if it goes its full range
     * through air, {@link #HIT} if it meets the floor, the block the feet end in (before any
     * fall) in {@code cell}; {@link #FAILED} if that isn't the same with every nudge. The first
     * cast is from standing, with the eye off the middle as for any cast; the later ones are
     * from the middle of a block, fallen any of {@code falls} (null for the first).
     */
    static int chainStep(ArrayBlockView world, double fx, double fy, double fz, double yaw,
            double pitch, double range, double[] falls, int[] cell) {
        boolean first = falls == null;
        double[][] eyes = first
                ? new double[][] {{0, 0}, {-EYE_JITTER, 0}, {EYE_JITTER, 0}, {0, -EYE_JITTER},
                        {0, EYE_JITTER}}
                : new double[][] {{0, 0}};
        double[] belows = first ? FEET_BELOW : new double[] {FEET_BELOW[0]};
        if (first) {
            falls = new double[] {0};
        }
        double j = CHAIN_ANGLE_JITTER;
        double[][] turns = {{0, 0}, {-j, 0}, {j, 0}, {0, -j}, {0, j}};
        int[] at = new int[3];
        int kind = -2;
        for (double below : belows) {
            for (double f : falls) {
                for (double[] e : eyes) {
                    for (double[] t : turns) {
                        int k = teleport(world, fx + e[0], fy - f, fz + e[1], yaw + t[0],
                                pitch + t[1], range, below, at);
                        if (k == FAILED) {
                            return FAILED;
                        }
                        if (kind == -2) {
                            kind = k;
                            System.arraycopy(at, 0, cell, 0, 3);
                        } else if (k != kind || at[0] != cell[0] || at[1] != cell[1]
                                || at[2] != cell[2]) {
                            return FAILED;
                        }
                    }
                }
            }
        }
        return kind;
    }

    /**
     * Whether a cast from feet (fx, fy, fz) at the given view lands the same with every nudge;
     * if so, {@code out} holds the spot {x, y, z} it ends on and how far it fell.
     */
    static boolean sure(ArrayBlockView world, double fx, double fy, double fz, double yaw,
            double pitch, double range, int[] out) {
        if (!land(world, fx, fy, fz, yaw, pitch, range, FEET_BELOW[0], out)) {
            return false;
        }
        int x = out[0], y = out[1], z = out[2];
        int[] other = new int[4];
        double[][] eyes = {{0, 0}, {-EYE_JITTER, 0}, {EYE_JITTER, 0}, {0, -EYE_JITTER},
                {0, EYE_JITTER}};
        double[][] turns = {{0, 0}, {-ANGLE_JITTER, 0}, {ANGLE_JITTER, 0}, {0, -ANGLE_JITTER},
                {0, ANGLE_JITTER}};
        for (double below : FEET_BELOW) {
            for (double[] e : eyes) {
                for (double[] t : turns) {
                    if (!land(world, fx + e[0], fy, fz + e[1], yaw + t[0], pitch + t[1], range,
                            below, other) || other[0] != x || other[1] != y || other[2] != z) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * Where one cast ends: {x, y, z} of the spot the feet end on and the blocks fallen, in
     * {@code out}; false if the cast can't be told for sure (the line meets a wall, a ceiling, a
     * block that isn't full, or a block too close; the landing has no room; the fall is too long
     * or ends on something other than a full block).
     */
    static boolean land(ArrayBlockView world, double fx, double fy, double fz, double yaw,
            double pitch, double range, double feetBelow, int[] out) {
        if (teleport(world, fx, fy, fz, yaw, pitch, range, feetBelow, out) == FAILED) {
            return false;
        }
        int fell = fall(world, out[0], out[1], out[2]);
        if (fell < 0) {
            return false;
        }
        out[1] -= fell;
        out[3] = fell;
        return true;
    }

    /**
     * Where one cast puts the feet before they fall, as {x, y, z} in {@code out}: {@link #HIT}
     * if the line met the floor, {@link #OPEN} if it went its full range, {@link #FAILED} if the
     * cast can't be told for sure (the line meets a wall, a ceiling, a block that isn't full,
     * or a block too close; the landing has no room).
     */
    static int teleport(ArrayBlockView world, double fx, double fy, double fz, double yaw,
            double pitch, double range, double feetBelow, int[] out) {
        double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
        double dx = -Math.sin(yr) * Math.cos(pr), dy = -Math.sin(pr), dz = Math.cos(yr) * Math.cos(pr);
        double ex = fx, ey = fy + EYE_HEIGHT, ez = fz;
        int x = (int) Math.floor(ex), y = (int) Math.floor(ey), z = (int) Math.floor(ez);
        if (!air(world, x, y, z)) {
            return FAILED;
        }
        int sx = dx > 0 ? 1 : -1, sy = dy > 0 ? 1 : -1, sz = dz > 0 ? 1 : -1;
        double deltaX = dx == 0 ? Double.MAX_VALUE : Math.abs(1 / dx);
        double deltaY = dy == 0 ? Double.MAX_VALUE : Math.abs(1 / dy);
        double deltaZ = dz == 0 ? Double.MAX_VALUE : Math.abs(1 / dz);
        double nextX = dx == 0 ? Double.MAX_VALUE : (dx > 0 ? x + 1 - ex : ex - x) * deltaX;
        double nextY = dy == 0 ? Double.MAX_VALUE : (dy > 0 ? y + 1 - ey : ey - y) * deltaY;
        double nextZ = dz == 0 ? Double.MAX_VALUE : (dz > 0 ? z + 1 - ez : ez - z) * deltaZ;
        double t = range;
        int kind = OPEN;
        while (true) {
            double at;
            int axis;
            if (nextX <= nextY && nextX <= nextZ) {
                at = nextX;
                axis = 0;
            } else if (nextY <= nextZ) {
                at = nextY;
                axis = 1;
            } else {
                at = nextZ;
                axis = 2;
            }
            if (at > range) {
                break;
            }
            switch (axis) {
                case 0 -> {
                    x += sx;
                    nextX += deltaX;
                }
                case 1 -> {
                    y += sy;
                    nextY += deltaY;
                }
                default -> {
                    z += sz;
                    nextZ += deltaZ;
                }
            }
            if (!world.inBounds(x, y, z)) {
                return FAILED;
            }
            BlockType b = world.blockAt(x, y, z);
            if (b == BlockType.AIR) {
                continue;
            }
            // Only a floor met from above is pinned down, and only full blocks.
            if (b != BlockType.SOLID || axis != 1 || sy > 0 || at < TOO_CLOSE) {
                return FAILED;
            }
            t = at - 1e-3;
            kind = HIT;
            break;
        }
        double px = ex + dx * t, py = ey + dy * t, pz = ez + dz * t;
        int lx = (int) Math.floor(px), lz = (int) Math.floor(pz);
        int ly = (int) Math.floor(py - feetBelow);
        // Out of the floor, if the feet are in it.
        for (int up = 0; up < 3 && !(air(world, lx, ly, lz) && air(world, lx, ly + 1, lz)); up++) {
            ly++;
        }
        if (!air(world, lx, ly, lz) || !air(world, lx, ly + 1, lz)) {
            return FAILED;
        }
        out[0] = lx;
        out[1] = ly;
        out[2] = lz;
        return kind;
    }

    /**
     * How far the feet fall from block (x, y, z) onto a full block, or -1 if it's further than
     * {@link #MAX_FALL} or onto something else.
     */
    static int fall(ArrayBlockView world, int x, int y, int z) {
        int fell = 0;
        while (air(world, x, y - 1, z)) {
            y--;
            if (++fell > MAX_FALL) {
                return -1;
            }
        }
        if (!world.inBounds(x, y - 1, z) || world.blockAt(x, y - 1, z) != BlockType.SOLID) {
            return -1;
        }
        return fell;
    }

    /** A spot to cast from: a full block to stand on and air for the body. */
    static boolean standing(ArrayBlockView world, int x, int y, int z) {
        return world.inBounds(x, y - 1, z) && world.blockAt(x, y - 1, z) == BlockType.SOLID
                && air(world, x, y, z) && air(world, x, y + 1, z);
    }

    private static boolean air(ArrayBlockView world, int x, int y, int z) {
        return world.inBounds(x, y, z) && world.blockAt(x, y, z) == BlockType.AIR;
    }

    /** Ticks to fall {@code blocks} from standing still. */
    static int fallTicks(int blocks) {
        if (blocks >= 0 && blocks < FALL_TICKS.length) {
            return FALL_TICKS[blocks];
        }
        return ticksToFall(blocks);
    }

    /** {@link #fallTicks} worked out once for the falls a cast can end with. */
    private static final int[] FALL_TICKS = new int[MAX_FALL + 1];

    static {
        for (int b = 0; b < FALL_TICKS.length; b++) {
            FALL_TICKS[b] = ticksToFall(b);
        }
    }

    private static int ticksToFall(int blocks) {
        double v = 0, fallen = 0;
        int ticks = 0;
        while (fallen < blocks) {
            v = (v - 0.08) * 0.98;
            fallen -= v;
            ticks++;
        }
        return ticks;
    }

    public int anchors() {
        return anchors;
    }

    public int count() {
        return hopFrom.length;
    }

    public double buildMs() {
        return buildMs;
    }

    public double range() {
        return range;
    }

    public NavGraph walking() {
        return walking;
    }

    /** The same hops on a patch of the graph they were built on; null if it isn't one. */
    public TransmitHops onto(NavGraph g) {
        if (g == walking) {
            return this;
        }
        long[] was = walking.positions();
        long[] now = g.positions();
        if (now.length < was.length || Arrays.mismatch(was, 0, was.length, now, 0,
                was.length) >= 0) {
            return null;
        }
        return new TransmitHops(g, range, anchors, hopFrom, hopTo, yaw, pitch, chain, cost, 0);
    }

    /** The number of the cast from {@code from} to {@code to} (packed), or -1 if there's none. */
    public int index(long from, long to) {
        int a = walking.cell(from), b = walking.cell(to);
        for (int k = 0; k < hopFrom.length; k++) {
            if (hopFrom[k] == a && hopTo[k] == b) {
                return k;
            }
        }
        return -1;
    }

    /**
     * Where the player goes on a cast (or chain) from standing spot {@code from} (packed) at
     * this view, as the route expects it: the block each cast puts the feet in, then the one
     * they fall onto, if that's another. For drawing the route.
     */
    public static List<Long> cells(ArrayBlockView world, long from, float yaw, float pitch,
            int chain, double range) {
        List<Long> out = new ArrayList<>();
        double fx = Pos.x(from) + 0.5, fy = Pos.y(from), fz = Pos.z(from) + 0.5;
        int[] at = new int[3];
        for (int n = 0; n < chain; n++) {
            if (teleport(world, fx, fy, fz, yaw, pitch, range, FEET_BELOW[0], at) == FAILED) {
                return out;
            }
            out.add(Pos.pack(at[0], at[1], at[2]));
            fx = at[0] + 0.5;
            fy = at[1];
            fz = at[2] + 0.5;
        }
        int fell = fall(world, at[0], at[1], at[2]);
        if (fell > 0) {
            out.add(Pos.pack(at[0], at[1] - fell, at[2]));
        }
        return out;
    }

    /** What {@link #cellOf} returns for a cast that can't be told. */
    public static final long UNKNOWN = Long.MIN_VALUE;

    /**
     * The block a cast from feet (fx, fy, fz) at this view puts the feet in, before any fall,
     * packed; {@link #UNKNOWN} if the map can't tell (a wall, a ceiling, a block it doesn't
     * know the shape of). For checking a cast from where the player really is before the
     * click.
     */
    public static long cellOf(ArrayBlockView world, double fx, double fy, double fz, double yaw,
            double pitch, double range) {
        int[] at = new int[3];
        if (teleport(world, fx, fy, fz, yaw, pitch, range, FEET_BELOW[0], at) == FAILED) {
            return UNKNOWN;
        }
        return Pos.pack(at[0], at[1], at[2]);
    }

    /** How far off the view a cast on the move is checked, in degrees. */
    static final double MOVING_ANGLE_JITTER = 0.2;

    /**
     * A view near (yaw, pitch) for a cast on the move, from any of {@code feet} (each {x, y,
     * z}: where the server may take the player to be when the click arrives), whichever feet
     * height under the eye's end and with the view off by {@link #MOVING_ANGLE_JITTER}: one that
     * puts the feet in block {@code want} (packed, before any fall) if there is one, else in a
     * block next to it at the same height that falls as far (a player on the move lands a
     * little further on, and casts the next from there). The nearest such view to (yaw,
     * pitch), up to 8 degrees across and 6 up or down; null if there's none.
     */
    public static float[] aimFrom(ArrayBlockView world, double[][] feet, float yaw, float pitch,
            double range, long want) {
        return aimFrom(world, feet, yaw, pitch, range, want, 1, false);
    }

    /**
     * {@link #aimFrom} allowing up to {@code maxSlack} blocks off {@code want} (0: that block
     * only), for feet in the air ({@code air}: the eye's end checked 1.62 above the feet only,
     * as Hypixel casts mid-air) or on the ground.
     */
    public static float[] aimFrom(ArrayBlockView world, double[][] feet, float yaw, float pitch,
            double range, long want, int maxSlack, boolean air) {
        for (int slack = 0; slack <= maxSlack; slack++) {
            float[] best = null;
            double bestOff = Double.MAX_VALUE;
            for (double dp = -6; dp <= 6; dp += 0.5) {
                for (double dy = -8; dy <= 8; dy += 0.5) {
                    double off = Math.abs(dy) + Math.abs(dp);
                    double p = pitch + dp;
                    if (off >= bestOff || p < -90 || p > 90) {
                        continue;
                    }
                    if (castsTo(world, feet, yaw + dy, p, range, want, slack, air)) {
                        best = new float[] {(float) (yaw + dy), (float) p};
                        bestOff = off;
                    }
                }
            }
            if (best != null) {
                return best;
            }
        }
        return null;
    }

    /**
     * Whether a cast at (yaw, pitch) puts the feet in the same block from each of {@code feet},
     * and that block is {@code want} or, with {@code slack} 1, one next to it at the same height
     * that falls as far.
     */
    public static boolean castsTo(ArrayBlockView world, double[][] feet, double yaw,
            double pitch, double range, long want, int slack) {
        return castsTo(world, feet, yaw, pitch, range, want, slack, false);
    }

    /** {@link #castsTo} for feet in the air ({@code air}) or on the ground. */
    public static boolean castsTo(ArrayBlockView world, double[][] feet, double yaw,
            double pitch, double range, long want, int slack, boolean air) {
        double[] belows = air ? new double[] {FEET_BELOW[0]} : FEET_BELOW;
        int[] at = new int[3];
        double j = MOVING_ANGLE_JITTER;
        double[][] turns = {{0, 0}, {-j, 0}, {j, 0}, {0, -j}, {0, j}};
        int wx = Pos.x(want), wy = Pos.y(want), wz = Pos.z(want);
        int cx = 0, cy = 0, cz = 0;
        boolean first = true;
        for (double[] f : feet) {
            for (double below : belows) {
                for (double[] t : turns) {
                    if (teleport(world, f[0], f[1], f[2], yaw + t[0], pitch + t[1], range, below,
                            at) == FAILED) {
                        return false;
                    }
                    if (first) {
                        cx = at[0];
                        cy = at[1];
                        cz = at[2];
                        first = false;
                    } else if (at[0] != cx || at[1] != cy || at[2] != cz) {
                        return false;
                    }
                }
            }
        }
        if (cy != wy || Math.abs(cx - wx) > slack || Math.abs(cz - wz) > slack) {
            return false;
        }
        return slack == 0 || cx == wx && cz == wz
                || fall(world, cx, cy, cz) == fall(world, wx, wy, wz)
                        && fall(world, cx, cy, cz) >= 0;
    }

    /** How many casts hop {@code k} chains in the air (1 for a single cast). */
    public int chain(int k) {
        return chain[k];
    }

    /** The view {yaw, pitch} of cast {@code k}. */
    public float[] view(int k) {
        return new float[] {yaw[k], pitch[k]};
    }

    /** The casts as moves to add to the walking graph, leaving out those in {@code skip}. */
    public HopGraph.Moves moves(Set<Integer> skip) {
        return new HopGraph.Moves(MoveType.TRANSMIT, hopFrom, hopTo, cost, skip);
    }
}
