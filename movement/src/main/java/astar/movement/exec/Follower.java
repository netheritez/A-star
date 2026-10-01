package astar.movement.exec;

import astar.movement.Keys;
import astar.movement.plan.Curve;
import astar.movement.plan.ExecutionPlan;
import astar.movement.plan.ExecutionPlan.Kind;
import astar.movement.plan.ExecutionPlan.Node;
import astar.movement.plan.ExecutionPlan.Segment;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Pure pursuit along a stretch of an {@link ExecutionPlan}: each tick it finds how far along
 * the route the player is, aims at a point a little further on, and picks the keys that keep
 * the player under the plan's speed limits.
 *
 * <p>The yaw is the steering: forward is the only direction key it uses on the ground (and a
 * side key while strafing, see {@link Executor}). Speed comes from four gaits, from fastest to
 * slowest: sprint (forward and sprint), walk (forward), coast (forward and back together,
 * which cancel, so the player slides to a stop) and brake (back alone, when sliding isn't
 * slowing it enough). Coasting holds back as well as forward because back cancels the game's
 * double tap: pressing forward again soon after letting go would otherwise start a sprint. It
 * never sneaks, as a player doesn't crouch to slow down; the plan's speed limits keep it off
 * edges instead.
 *
 * <p>Jumps up and drops are timed from the game's air physics ({@link #airTravel}): it jumps on
 * the tick a jump would come down over the top, and walks off a drop slowly enough that the
 * fall lands before the route below turns. In the air it faces where it lands and holds
 * forward, nothing or back to come down inside the room there.
 */
public final class Follower {

    /** How close to the end counts as there, across the ground, in blocks. */
    public static final double GOAL_RADIUS = 0.3;
    /**
     * Ground friction times air drag: how much of a tick's move is carried into the next. The
     * velocity the game reports is what's carried, so a player sprinting at 0.28 blocks a tick
     * reports about 0.153.
     */
    static final double SLIDE = 0.546;
    /**
     * Forward's push per tick on ordinary ground: movement speed 0.1 times the input's 0.98
     * (the game scales the ground push by 0.216 / friction³, which is 1 at friction 0.6).
     */
    private static final double WALK_PUSH = 0.098;
    private static final double SPRINT_PUSH = 1.3;
    private static final double STEP = 0.6;
    /** How much slower it may go per block further from a drop's edge, in blocks per tick. */
    private static final double GROUND_BRAKE = 0.3;
    /**
     * How far above or below a segment's ends the feet may be and still be on it: more
     * than a jump's rise.
     */
    private static final double LEVEL = 1.3;
    /** The closest it aims ahead, in blocks. */
    private static final double MIN_AIM = 0.3;
    /** How far out it aims along a jump's or a drop's line, at the least, in blocks. */
    private static final double LINE_AIM = 2;
    /** How close below a jump's top the feet have to be for the jump to be done. */
    private static final double JUMP_DONE = 0.3;
    /**
     * How far off a jump's direction, in degrees, the route may run for a running jump, or
     * past a landing and still count as straight on.
     */
    private static final double ALIGNED = 10;
    /** How fast the player may be moving across a jump's direction as it jumps. */
    private static final double DRIFTING = 0.08;
    /** How close to a jump's edge it jumps from standing still. */
    private static final double STANDING_JUMP = 0.7;
    /** How far past a jump's edge it still tries again, after landing short of the top. */
    private static final double JUMP_RETRY = 0.6;
    /** Air drag: how much of the speed across the ground is carried into the next tick. */
    private static final double AIR_SLIDE = 0.91;
    /** Forward's push per tick in the air, unsprinted. */
    private static final double AIR_PUSH = 0.02;
    private static final double JUMP_SPEED = 0.42;
    private static final double SPRINT_JUMP_BOOST = 0.2;
    private static final double GRAVITY = 0.08;
    private static final double VERTICAL_DRAG = 0.98;
    /** How far past a jump's edge the feet should come down: over the top, clear of its edge. */
    private static final double LAND_PAST_EDGE = 0.4;
    /** How far the middle of the body is past an edge when it stops being held up. */
    private static final double HALF_WIDTH = 0.3;
    /** How far past its node a landing may end: the body still inside the node's cell. */
    private static final double LAND_SLACK = 0.2;

    /** What the follower wants this tick. */
    public record Intent(double yaw, Keys keys, Gait gait) {}

    public enum Gait {
        SPRINT(new Keys(true, false, false, false, false, false, true), WALK_PUSH),
        WALK(new Keys(true, false, false, false, false, false, false), WALK_PUSH),
        /** Forward and back together, which cancel. */
        COAST(new Keys(true, false, true, false, false, false, false), 0),
        /** Back alone, pushing against the way it's going. */
        BRAKE(new Keys(false, false, true, false, false, false, false), -WALK_PUSH);

        final Keys keys;
        /** How much a tick of this gait adds to the ground speed on ordinary blocks, unsprinted. */
        final double push;

        Gait(Keys keys, double push) {
            this.keys = keys;
            this.push = push;
        }
    }

    /**
     * How it follows.
     *
     * @param lookahead the shortest distance ahead it aims, in blocks
     * @param lookaheadPerSpeed how much further it aims per block per tick of speed
     * @param sprintError the most the camera may point off the target yaw while sprinting,
     *     in degrees
     * @param walkError beyond this it stops pressing forward and turns in place
     */
    public record Settings(double lookahead, double lookaheadPerSpeed, double sprintError,
            double walkError) {

        public static final Settings DEFAULT = new Settings(0.6, 2.2, 20, 35);
    }

    private final Settings settings;
    private final List<Node> nodes;
    /**
     * How many times a normal player's ground speed the player moves: scales the push of
     * every gait on the ground. The air push and the sprint jump's boost stay the same.
     */
    private final double pace;
    private final double sprintSpeed;
    /** The plan's rounded corners inside this stretch, in order. */
    private final List<Curve> curves = new ArrayList<>();
    /** Where each of those starts, as a distance along the stretch. */
    private double[] curveFrom = new double[0];
    private final int first;
    private final int last;
    private final double[] along;
    /** The jumps up and drops in the stretch, in order. */
    private final List<AirMove> airMoves;
    private int segment;
    /** The jump or drop the player is in the air over, or null. */
    private AirMove flying;
    private double progress;
    private double offset;
    /** How far along the stretch the last {@link #decide} aimed. */
    private double aimed;

    /**
     * Follows nodes {@code first} to {@code last} of a plan.
     *
     * @throws IllegalArgumentException if the range is empty or out of the plan
     */
    public Follower(ExecutionPlan plan, int first, int last, Settings settings) {
        if (first < 0 || last >= plan.nodes().size() || first > last) {
            throw new IllegalArgumentException("nodes " + first + " to " + last + " aren't in a "
                    + plan.nodes().size() + "-node plan");
        }
        this.settings = settings;
        this.nodes = plan.nodes();
        this.pace = plan.speed();
        this.sprintSpeed = plan.sprintSpeed();
        this.first = first;
        this.last = last;
        along = new double[last - first + 1];
        for (int i = first + 1; i <= last; i++) {
            Node a = nodes.get(i - 1);
            Node b = nodes.get(i);
            along[i - first] = along[i - first - 1] + Math.hypot(b.x() - a.x(), b.z() - a.z());
        }
        for (Curve c : plan.curves()) {
            if (c.node() > first && c.node() < last) {
                curves.add(c);
            }
        }
        curveFrom = new double[curves.size()];
        for (int k = 0; k < curves.size(); k++) {
            curveFrom[k] = along[curves.get(k).node() - first] - curves.get(k).trim();
        }
        airMoves = new ArrayList<>();
        for (Segment s : plan.segments()) {
            if ((s.kind() == Kind.JUMP_UP || s.kind() == Kind.DROP) && s.from() >= first
                    && s.to() <= last) {
                airMoves.add(airMove(s.from(), s.to(), s.kind() == Kind.JUMP_UP));
            }
        }
        segment = first;
    }

    /** Whether the keys may push up to 45 degrees off the camera this tick (W with A or D). */
    private boolean strafe;

    /**
     * Lets the next {@link #decide} count a heading within 45 degrees either side of the camera
     * as reachable with a side key, not just the camera's own.
     */
    void strafe(boolean on) {
        strafe = on;
    }

    /** Whether it aims at the stretch's end now, which it keeps to from then on. */
    private boolean aimingAtEnd;

    /** Distance along the stretch at the player's last known place, from its first node. */
    public double progress() {
        return progress;
    }

    /** How far the player was from the route's line at the last update. */
    public double offset() {
        return offset;
    }

    /** The stretch's length across the ground. */
    public double length() {
        return along[along.length - 1];
    }

    /** The node the player is on or just past. */
    public int node() {
        return segment;
    }

    /** Distance across the ground from the player to the stretch's last node. */
    public double toEnd(PlayerController.Observation p) {
        Node end = nodes.get(last);
        return Math.hypot(end.x() - p.x(), end.z() - p.z());
    }

    /** Finds where the player is along the route, looking a few segments ahead. */
    public void locate(PlayerController.Observation p) {
        if (first == last) {
            progress = 0;
            offset = toEnd(p);
            return;
        }
        double best = Double.MAX_VALUE;
        double bestOffset = offset;
        int bestSegment = segment;
        double bestAlong = progress;
        int end = Math.min(last - 1, segment + 4);
        if (flying != null) {
            // In the air it's over the move it jumped or dropped for, however the route winds
            // near there.
            end = Math.min(end, flying.roomEnd());
        }
        for (int i = Math.max(first, segment - 1); i <= end; i++) {
            Node a = nodes.get(i);
            Node b = nodes.get(i + 1);
            double dx = b.x() - a.x();
            double dz = b.z() - a.z();
            double len2 = dx * dx + dz * dz;
            double t = len2 == 0 ? 0
                    : Math.max(0, Math.min(1, ((p.x() - a.x()) * dx + (p.z() - a.z()) * dz)
                            / len2));
            double d = Math.hypot(a.x() + t * dx - p.x(), a.z() + t * dz - p.z());
            // A route that doubles back under or over itself (down a drop and back) is told
            // apart by height.
            double dy = Math.max(0, Math.max(p.y() - Math.max(a.y(), b.y()),
                    Math.min(a.y(), b.y()) - p.y()));
            // (Not in the air over a drop: it's above where it lands until it's down.)
            double score = dy > LEVEL && flying == null ? d + dy : d;
            // Ties go forward, so the player isn't pulled back to a corner it has passed.
            if (score <= best + 1e-9) {
                best = score;
                bestOffset = d;
                bestSegment = i;
                bestAlong = along[i - first] + t * Math.sqrt(len2);
            }
        }
        segment = bestSegment;
        progress = bestAlong;
        offset = bestOffset;
    }

    /** The point {@code distance} along the stretch, as {x, z}. */
    public double[] pointAt(double distance) {
        // Round a corner along its curve.
        int k = Arrays.binarySearch(curveFrom, distance);
        k = k >= 0 ? k : -k - 2;
        if (k >= 0) {
            Curve c = curves.get(k);
            double f = (distance - curveFrom[k]) / (2 * c.trim());
            if (f <= 1) {
                return c.at(f);
            }
        }
        if (distance >= length()) {
            Node n = nodes.get(last);
            return new double[] {n.x(), n.z()};
        }
        int i = Math.max(segment, first);
        while (i < last && along[i + 1 - first] < distance) {
            i++;
        }
        while (i > first && along[i - first] > distance) {
            i--;
        }
        Node a = nodes.get(i);
        Node b = nodes.get(Math.min(i + 1, last));
        double len = along[Math.min(i + 1, last) - first] - along[i - first];
        double t = len == 0 ? 0 : (distance - along[i - first]) / len;
        return new double[] {a.x() + t * (b.x() - a.x()), a.z() + t * (b.z() - a.z())};
    }

    /** How far along the stretch the last {@link #decide} aimed: the point it steers for. */
    public double aimed() {
        return aimed;
    }

    /**
     * The point {@code distance} along the stretch, as {x, y, z}, with y the floor height
     * between its nodes.
     */
    public double[] placeAt(double distance) {
        double[] xz = pointAt(distance);
        int i = first;
        while (i < last && along[i + 1 - first] < distance) {
            i++;
        }
        Node a = nodes.get(i);
        Node b = nodes.get(Math.min(i + 1, last));
        double len = along[Math.min(i + 1, last) - first] - along[i - first];
        double t = len == 0 ? 0 : Math.max(0, Math.min(1, (distance - along[i - first]) / len));
        return new double[] {xz[0], a.y() + t * (b.y() - a.y()), xz[1]};
    }

    /**
     * The point {@code blocks} past the player's place on the route, as {x, y, z} with y on the
     * floor, measured along the way it goes: down a drop counts as well as across the ground,
     * so going down steps the point comes down them with the player instead of dropping out of
     * sight. It follows the plan's nodes past this stretch's end, so it doesn't stall at a
     * jump; it stops at the plan's last node.
     */
    public double[] placeAhead(double blocks) {
        int i = Math.max(first, Math.min(segment, last));
        // Across the ground from the stretch's first node, at node i and at the point reached.
        double nodeAt = along[i - first];
        double ground = Math.max(nodeAt, progress);
        double left = blocks;
        while (i + 1 < nodes.size()) {
            Node a = nodes.get(i);
            Node b = nodes.get(i + 1);
            double len = Math.hypot(b.x() - a.x(), b.z() - a.z());
            double part = nodeAt + len - ground;
            double fall = len > 1e-9 ? (b.y() - a.y()) * part / len : b.y() - a.y();
            double way = Math.hypot(part, fall);
            if (left <= way) {
                double at = ground + (way > 1e-9 ? part * left / way : 0);
                if (i + 1 <= last) {
                    // On this stretch: round its corners.
                    return placeAt(at);
                }
                double t = len > 1e-9 ? (at - nodeAt) / len : 1;
                return new double[] {a.x() + t * (b.x() - a.x()), a.y() + t * (b.y() - a.y()),
                        a.z() + t * (b.z() - a.z())};
            }
            left -= way;
            nodeAt += len;
            ground = nodeAt;
            i++;
        }
        Node end = nodes.get(nodes.size() - 1);
        return new double[] {end.x(), end.y(), end.z()};
    }

    /** The plan's speed limit at a distance along the stretch, between its nodes. */
    public double limitAt(double distance) {
        if (distance >= length()) {
            return nodes.get(last).speedLimit();
        }
        int i = first;
        while (i < last && along[i + 1 - first] < distance) {
            i++;
        }
        double a = nodes.get(i).speedLimit();
        double b = nodes.get(Math.min(i + 1, last)).speedLimit();
        double len = along[Math.min(i + 1, last) - first] - along[i - first];
        double t = len == 0 ? 0 : (distance - along[i - first]) / len;
        return a + t * (b - a);
    }

    /** The narrowest room on either side between two distances along the stretch. */
    double narrowest(double from, double to) {
        double w = Double.MAX_VALUE;
        for (int i = first; i <= last; i++) {
            double s = along[i - first];
            if (s >= from - 1 && s <= to + 1) {
                Node n = nodes.get(i);
                w = Math.min(w, Math.min(n.left().width(), n.right().width()));
            }
        }
        return w;
    }

    /**
     * A jump up or a drop: a move that leaves the ground at an edge and comes down on a floor
     * further on.
     *
     * @param from the node it leaves from
     * @param to the node it lands on
     * @param up whether it's a jump up (else a drop)
     * @param edge where the floor's edge is, as a distance along the stretch: halfway between
     *     the nodes, where a block's face is
     * @param room how far along the stretch the landing may end: past {@code to} for as long
     *     as the route goes on straight and level, and a little into the cell where it turns
     *     or ends
     * @param roomEnd the node where that room ends
     * @param ux the move's direction across the ground
     * @param uz the move's direction across the ground
     */
    record AirMove(int from, int to, boolean up, double edge, double room, int roomEnd,
            double ux, double uz) {

        /**
         * How far past the edge a jump should come down, along the move: {@link
         * #LAND_PAST_EDGE} over the top on each axis it crosses, so a diagonal jump lands as
         * far onto the block as a straight one (not just over its corner, which a thin block
         * like a pane doesn't fill).
         */
        double landPast() {
            return LAND_PAST_EDGE / Math.max(Math.abs(ux), Math.abs(uz));
        }
    }

    private AirMove airMove(int a, int b, boolean up) {
        Node na = nodes.get(a);
        Node nb = nodes.get(b);
        double len = Math.max(1e-9, Math.hypot(nb.x() - na.x(), nb.z() - na.z()));
        double ux = (nb.x() - na.x()) / len;
        double uz = (nb.z() - na.z()) / len;
        int k = b;
        while (k < last) {
            Node n = nodes.get(k);
            Node m = nodes.get(k + 1);
            double l = Math.hypot(m.x() - n.x(), m.z() - n.z());
            if (l < 1e-9 || !m.floor() || Math.abs(m.y() - n.y()) > STEP
                    || ((m.x() - n.x()) * ux + (m.z() - n.z()) * uz) / l
                            < Math.cos(Math.toRadians(ALIGNED))) {
                break;
            }
            k++;
        }
        return new AirMove(a, b, up, (along[a - first] + along[b - first]) / 2,
                along[k - first] + LAND_SLACK, k, ux, uz);
    }

    /**
     * How far the body goes across the ground before it lands on a floor {@code height} above
     * its feet (below, if negative), from the air: {@code v} its speed across the ground and
     * {@code vy} its upward speed, both for the next tick, and {@code push} what the keys add
     * each tick. The game's order: push, move, then drag and gravity.
     */
    static double airTravel(double v, double vy, double height, double push) {
        double x = 0;
        double y = 0;
        for (int t = 0; t < 200; t++) {
            v += push;
            x += v;
            y += vy;
            if (vy < 0 && y <= height) {
                return x;
            }
            v *= AIR_SLIDE;
            vy = (vy - GRAVITY) * VERTICAL_DRAG;
        }
        return x;
    }

    /**
     * How far across the ground a jump started this tick would carry the body before it lands
     * {@code rise} higher: the jump tick itself, pushed and dragged as on the ground (plus the
     * sprint jump's boost), then the air.
     */
    static double jumpTravel(double v, double rise, boolean sprinting, double groundPush,
            double airPush) {
        double move = v + groundPush + (sprinting ? SPRINT_JUMP_BOOST : 0);
        double vy = (JUMP_SPEED - GRAVITY) * VERTICAL_DRAG;
        return move + airTravel(move * SLIDE, vy, rise - JUMP_SPEED, airPush);
    }

    /**
     * The jump or drop the player is coming up to on the ground, still below its top (a jump)
     * or above its landing (a drop), or null.
     */
    AirMove upcoming(PlayerController.Observation p) {
        for (AirMove m : airMoves) {
            if (m.edge() - 3 > progress) {
                break;
            }
            double y = nodes.get(m.to()).y();
            // A jump is to come until the feet are up at its top past its edge (a jump across
            // a gap has its top no higher than where it starts); a drop until they're down.
            boolean before = !m.up() ? p.y() > y + STEP
                    : progress < m.edge()
                            ? p.y() < y + STEP && p.y() < nodes.get(m.from()).y() + STEP / 2
                            : p.y() < y - JUMP_DONE;
            if (before && progress <= m.edge() + (m.up() ? JUMP_RETRY : 1)) {
                return m;
            }
        }
        return null;
    }

    /** Speed across the ground in the direction of a move. */
    private static double along(PlayerController.Observation p, AirMove m) {
        return p.vx() * m.ux() + p.vz() * m.uz();
    }

    /**
     * The fastest a drop's edge may be walked off so that the fall, without pressing on,
     * lands before the landing's room runs out.
     */
    double dropLimit(AirMove m, double fromY) {
        double depth = fromY - nodes.get(m.to()).y();
        double perSpeed = airTravel(1, -GRAVITY * VERTICAL_DRAG, -depth, 0);
        return Math.max(0, m.room() - m.edge() - HALF_WIDTH) / perSpeed;
    }

    /**
     * Whether to jump this tick: on the ground below a jump's top, when a jump now would come
     * down over the top, or when pressed against the face. After landing short (on the low
     * side, a little past where the edge was reckoned to be) it tries again.
     */
    boolean jumpNow(PlayerController.Observation p, AirMove m, Gait gait, double error) {
        if (m == null || !m.up() || !p.onGround()) {
            return false;
        }
        // A jump onto the low half of a stair has a node only half a block up, but the stair's
        // back is a full block high: that's what the jump has to clear.
        double rise = Math.max(1, nodes.get(m.to()).y() - p.y());
        double ahead = m.edge() - progress;
        if (ahead < -JUMP_RETRY) {
            return false;
        }
        // Only once lined up: on a stretch of route that runs the way the jump goes, and not
        // drifting across it. (Bumping into the side of a turn before it isn't the jump.)
        Node a = nodes.get(segment);
        Node b = nodes.get(Math.min(segment + 1, last));
        double len = Math.hypot(b.x() - a.x(), b.z() - a.z());
        if (len > 1e-9 && ((b.x() - a.x()) * m.ux() + (b.z() - a.z()) * m.uz()) / len
                < Math.cos(Math.toRadians(ALIGNED))) {
            return false;
        }
        if (Math.abs(p.vx() * m.uz() - p.vz() * m.ux()) > DRIFTING) {
            return false;
        }
        if (p.horizontalCollision() && ahead <= 1.0) {
            return true;
        }
        if (error > settings.walkError()) {
            return false;
        }
        if (ahead <= STANDING_JUMP && p.groundSpeed() < 0.02) {
            // Stopped at the edge (over a gap): jump from where it stands.
            return true;
        }
        boolean sprinting = p.sprinting() || gait == Gait.SPRINT;
        // (Braking, it jumps coasting: see decide.)
        double groundPush = Math.max(0, gait.push) * pace * (sprinting ? SPRINT_PUSH : 1);
        double v = Math.max(0, along(p, m));
        // Pressing on in the air where there's room to, as airKeys will.
        double land = progress + jumpTravel(v, rise, sprinting, groundPush,
                AIR_PUSH * (sprinting ? SPRINT_PUSH : 1));
        if (land > m.room()) {
            land = progress + jumpTravel(v, rise, sprinting, groundPush, 0);
        }
        return land >= m.edge() + m.landPast();
    }

    /**
     * Keys in the air over a jump or a drop: forward while the landing, pressing on, stays
     * inside the room there; nothing while it only does coasting; back if even coasting
     * would carry the body past it.
     */
    private Keys airKeys(PlayerController.Observation p, AirMove m) {
        double height = nodes.get(m.to()).y() - p.y();
        double v = along(p, m);
        double push = AIR_PUSH * (p.sprinting() ? SPRINT_PUSH : 1);
        double pressing = progress + airTravel(v, p.vy(), height, push);
        double coasting = progress + airTravel(v, p.vy(), height, 0);
        double past = m.edge() + (m.up() ? m.landPast() : HALF_WIDTH);
        if (pressing <= m.room() || coasting < past) {
            return Gait.WALK.keys;
        }
        if (coasting <= m.room()) {
            return Keys.NONE;
        }
        return new Keys(false, false, true, false, false, false, false);
    }

    /** Yaw in degrees for moving from one point toward another (the game's convention). */
    public static double yawToward(double fromX, double fromZ, double toX, double toZ) {
        return Math.toDegrees(Math.atan2(-(toX - fromX), toZ - fromZ));
    }

    /**
     * What to do this tick. Call {@link #locate} first.
     *
     * @return the yaw to aim at and the keys to hold
     */
    public Intent decide(PlayerController.Observation p) {
        double speed = p.groundSpeed();
        // The last tick's move, near enough: the carried velocity undone.
        double move = speed / SLIDE;
        // Along the route as well as straight there: a route can come back past its end.
        double remaining = Math.max(toEnd(p), length() - progress);
        double lookahead = settings.lookahead() + settings.lookaheadPerSpeed() * move;
        // In a tight spot aim closer, so the cut across a corner stays inside the room there.
        double room = narrowest(progress, progress + lookahead);
        lookahead = Math.min(lookahead, Math.max(settings.lookahead(), 3 * room + 0.4));
        AirMove nextMove = upcoming(p);
        double reach = lookahead;
        if (nextMove != null) {
            // Don't cut across the top of a jump or the landing of a drop: aim no further than
            // where it lands until it's there.
            lookahead = Math.min(lookahead, Math.max(MIN_AIM,
                    along[nextMove.to() - first] - progress));
        }
        // Once aiming at the end it keeps to it, so the aim doesn't flick back off it as the
        // player slows there.
        boolean atEnd = aimingAtEnd || remaining < lookahead || progress + lookahead >= length();
        aimingAtEnd = atEnd;
        aimed = atEnd ? length() : progress + lookahead;
        double[] aim = atEnd ? new double[] {nodes.get(last).x(), nodes.get(last).z()}
                : pointAt(aimed);
        if (!atEnd && nextMove != null && !nextMove.up()
                && aimed >= along[nextMove.from() - first] - 1e-9) {
            // Aimed short of a drop, a point that close swings the camera round as the player
            // drifts a little across it: aim on along the drop's own line instead, as far as
            // it would have, which keeps to the same line without the swing. Only once the aim
            // is on the drop (short of it the way in may head elsewhere, and a point pushed on
            // from there leaves the route), and not up a jump, whose top the feet must meet.
            double on = Math.max(reach, LINE_AIM) - lookahead;
            aim = new double[] {aim[0] + on * nextMove.ux(), aim[1] + on * nextMove.uz()};
        }
        double yaw = remaining < 1e-6 ? p.yaw() : yawToward(p.x(), p.z(), aim[0], aim[1]);
        double error = Math.abs(AimController.wrap(yaw - p.yaw()));
        if (strafe) {
            // Forward and a side key push 45 degrees off the camera.
            error = Math.min(error, Math.abs(Math.abs(AimController.wrap(yaw - p.yaw())) - 45));
        }

        Gait gait = Gait.COAST;
        // Aim to stop on the end itself; anywhere within the goal radius will do once stopped,
        // so an end already passed inside it isn't turned back for.
        if (remaining <= GOAL_RADIUS / 3 || (remaining <= GOAL_RADIUS && error > 90)) {
            yaw = p.yaw();
        } else {
            // How far the next tick will move in each gait: what's left of the velocity plus
            // the gait's push. Sprinting goes on until forward is let go, so while sprinting
            // every gait that holds forward pushes like a sprint.
            double limit = Math.min(limitAt(progress), limitAt(progress + 2 * move));
            if (nextMove != null && !nextMove.up() && p.onGround()) {
                // Slow enough at the edge that the fall lands inside the room below.
                double toEdge = Math.max(0, nextMove.edge() + HALF_WIDTH - progress);
                limit = Math.min(limit, dropLimit(nextMove, p.y()) + GROUND_BRAKE * toEdge);
            }
            for (Gait g : Gait.values()) {
                if (g == Gait.COAST) {
                    break;
                }
                if (g == Gait.SPRINT && (error > settings.sprintError()
                        || limit < sprintSpeed - 1e-6)) {
                    continue;
                }
                if (error > settings.walkError()) {
                    break;
                }
                double push = g.push * pace
                        * (p.sprinting() || g == Gait.SPRINT ? SPRINT_PUSH : 1);
                double next = speed + push;
                // Stay under the limit, and be able to slide to a stop by the end.
                if (next <= limit + 1e-4 && next / (1 - SLIDE) <= remaining + GOAL_RADIUS / 3) {
                    gait = g;
                    break;
                }
            }
            if (gait == Gait.COAST && p.onGround() && speed > limit + 1e-4
                    && speed > -Gait.BRAKE.push * pace) {
                // Sliding alone would still be too fast here: push back against it.
                gait = Gait.BRAKE;
            }
        }
        if (gait == Gait.COAST && remaining > GOAL_RADIUS / 3 && speed < 0.005
                && error <= 2 * settings.walkError()) {
            // Stopped short, with every gait too fast for the limits here: creep on anyway.
            gait = Gait.WALK;
        }
        Keys keys = gait.keys;
        if (p.onGround()) {
            flying = null;
        } else if (flying == null && nextMove != null && !nextMove.up()
                && progress >= nextMove.edge() - 1) {
            // Walked off a drop's edge.
            flying = nextMove;
        }
        if (flying != null) {
            // In the air: face along the move, toward where it lands, so forward and back
            // mean on and back.
            Node to = nodes.get(flying.to());
            // (A point on along the move's line past where it lands, so the yaw doesn't swing
            // round the landing as the player comes over it.)
            // Up a jump the feet have to come down on the block, so it steers for the landing
            // itself until nearly there; down a drop anywhere in the room below will do.
            double airYaw = flying.up() && progress < along[flying.to() - first] - MIN_AIM
                    ? yawToward(p.x(), p.z(), to.x(), to.z())
                    : yawToward(p.x(), p.z(), to.x() + LINE_AIM * flying.ux(),
                            to.z() + LINE_AIM * flying.uz());
            return new Intent(airYaw, airKeys(p, flying), gait);
        }
        if (jumpNow(p, nextMove, gait, error)) {
            flying = nextMove;
            if (gait == Gait.BRAKE) {
                // Not backwards off the top.
                keys = Gait.COAST.keys;
            }
            keys = new Keys(keys.forward(), keys.left(), keys.back(), keys.right(), true,
                    false, keys.sprint());
        }
        return new Intent(yaw, keys, gait);
    }
}
