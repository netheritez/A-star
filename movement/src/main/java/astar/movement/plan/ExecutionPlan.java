package astar.movement.plan;

import astar.core.BlockPoint;
import astar.core.MoveType;
import java.util.ArrayList;
import java.util.List;

/**
 * A route turned into what the executor follows: where each step's floor really is, how much
 * room there is on either side, where the doors are, and how fast each point may be passed.
 * {@link PlanBuilder} makes one from a grid path.
 *
 * @param nodes one per path step, in order
 * @param segments the nodes grouped into straight runs and single special moves
 * @param speed how many times a normal player's ground speed the player moves (2.4 with
 *     Speed VII): the walking and sprinting speeds the limits were set from
 * @param curves the corners rounded off, in order along the route
 */
public record ExecutionPlan(List<Node> nodes, List<Segment> segments, double speed,
        List<Curve> curves) {

    /** Walking speed on flat ground, in blocks per tick (4.317 blocks a second). */
    public static final double WALK_SPEED = 0.21585;
    /** Sprinting speed on flat ground, in blocks per tick (5.612 blocks a second). */
    public static final double SPRINT_SPEED = 0.28061;

    public ExecutionPlan {
        nodes = List.copyOf(nodes);
        segments = List.copyOf(segments);
        curves = List.copyOf(curves);
    }

    /** For a player at normal speed, with sharp corners. */
    public ExecutionPlan(List<Node> nodes, List<Segment> segments) {
        this(nodes, segments, 1, List.of());
    }

    /**
     * The route as it's walked, {x, y, z} points with y on the floor: through the nodes, and
     * around each rounded corner along its curve instead.
     */
    public List<double[]> track() {
        List<double[]> out = new ArrayList<>();
        double[] along = new double[nodes.size()];
        for (int i = 1; i < nodes.size(); i++) {
            Node a = nodes.get(i - 1);
            Node b = nodes.get(i);
            along[i] = along[i - 1] + Math.hypot(b.x() - a.x(), b.z() - a.z());
        }
        int c = 0;
        for (int i = 0; i < nodes.size(); i++) {
            while (c < curves.size() && along[curves.get(c).node()] - curves.get(c).trim()
                    < along[i] - 1e-9) {
                Curve curve = curves.get(c++);
                double from = along[curve.node()] - curve.trim();
                for (int k = 0; k < curve.x().length; k++) {
                    double f = (double) k / (curve.x().length - 1);
                    out.add(new double[] {curve.x()[k], floorAt(along, from + 2 * f
                            * curve.trim()), curve.z()[k]});
                }
            }
            boolean inside = false;
            for (Curve curve : curves) {
                double s = along[curve.node()];
                inside |= along[i] > s - curve.trim() + 1e-9 && along[i] < s + curve.trim() - 1e-9;
            }
            if (!inside) {
                Node n = nodes.get(i);
                out.add(new double[] {n.x(), n.y(), n.z()});
            }
        }
        return out;
    }

    /** The floor's height a distance along the nodes, between them. */
    private double floorAt(double[] along, double s) {
        int i = 0;
        while (i + 1 < nodes.size() && along[i + 1] < s) {
            i++;
        }
        Node a = nodes.get(i);
        Node b = nodes.get(Math.min(i + 1, nodes.size() - 1));
        double len = along[Math.min(i + 1, nodes.size() - 1)] - along[i];
        double t = len <= 0 ? 0 : Math.max(0, Math.min(1, (s - along[i]) / len));
        return a.y() + t * (b.y() - a.y());
    }

    /** Walking speed on flat ground for this player, in blocks per tick. */
    public double walkSpeed() {
        return WALK_SPEED * speed;
    }

    /** Sprinting speed on flat ground for this player, in blocks per tick. */
    public double sprintSpeed() {
        return SPRINT_SPEED * speed;
    }

    /**
     * One step of the route.
     *
     * @param cell the grid cell the feet are in
     * @param via how the step was reached ({@code null} for the start)
     * @param x the point to pass through: the middle of the cell
     * @param y the height of the floor the feet stand on there, which may be inside the cell
     *     (a slab) or below it; the cell's own y when there's no floor (a ladder, water, a
     *     jump's peak)
     * @param z the middle of the cell
     * @param floor whether there's a floor to stand on at {@code y}
     * @param distance distance along the route from the start to here, across the ground
     * @param left how far the middle of the body can move left of the route here, and what
     *     stops it (left of the direction the route leaves this node in)
     * @param right the same on the right
     * @param door a door or fence gate in this cell that has to be opened, or null
     * @param speedLimit the fastest this point may be passed, in blocks per tick: lower before
     *     corners with a drop on the outside, jumps, drops, ladders, water and doors, and zero
     *     at the goal
     */
    public record Node(BlockPoint cell, MoveType via, double x, double y, double z,
            boolean floor, double distance, Side left, Side right, Door door,
            double speedLimit) {}

    /**
     * Room beside the route.
     *
     * @param width how far the middle of the body can move sideways before a wall stops it or
     *     it would fall off an edge, at most {@link PlanBuilder#MAX_WIDTH}
     * @param edge what's there
     */
    public record Side(double width, Edge edge) {}

    public enum Edge {
        /** A block the body would bump into. Harmless to brush. */
        WALL,
        /** An edge the player would fall off (more than a step down). */
        DROP,
        /** Nothing in reach. */
        OPEN
    }

    /**
     * A door or fence gate on the route.
     *
     * @param pos the block to use (the lower half, for a door)
     * @param kind {@code "door"} or {@code "gate"}
     */
    public record Door(BlockPoint pos, String kind) {}

    /**
     * Nodes {@code from} to {@code to} (inclusive), a straight run of walking in one direction
     * on one level, or one special move (a jump up, a drop, climbing, swimming) from {@code
     * from} to {@code to = from + 1}.
     */
    public record Segment(int from, int to, Kind kind) {}

    public enum Kind {
        STRAIGHT, JUMP_UP, DROP, CLIMB, SWIM
    }

    /** The route's length across the ground, in blocks. */
    public double length() {
        return nodes.isEmpty() ? 0 : nodes.get(nodes.size() - 1).distance();
    }
}
