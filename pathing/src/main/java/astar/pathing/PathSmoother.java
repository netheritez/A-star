package astar.pathing;

import astar.core.BlockPoint;
import astar.core.MoveType;
import astar.core.PathStep;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a block-by-block path into a few waypoints joined by straight lines (string pulling).
 *
 * <ul>
 *   <li>Flat stretches (walks and diagonals at one height) are merged greedily: from each
 *       waypoint, the next one is the furthest later step still reachable by
 *       {@link MoveValidator#canWalkStraight}.
 *   <li>Jumps and drops are never smoothed over: the takeoff and landing blocks are both kept.
 *   <li>Neither are swims and climbs: every block of them is kept.
 * </ul>
 *
 * <p>Every straight segment is walkable by the validator's rules, and since each one replaces
 * a grid route between the same two points, the smoothed path is never longer than the raw one.
 *
 * <p>{@link #keepingRoom Keeping room}, a line also never passes closer to a wall or ledge
 * than the steps it replaces did ({@link Clearance}): a path the search kept off the walls
 * isn't pulled back against them at the corners.
 */
public final class PathSmoother {
    private final MoveValidator validator;
    private final Clearance room;
    private final boolean overSteps;

    public PathSmoother(MoveValidator validator) {
        this(validator, null, false);
    }

    private PathSmoother(MoveValidator validator, Clearance room, boolean overSteps) {
        this.validator = validator;
        this.room = room;
        this.overSteps = overSteps;
    }

    /** The same, keeping its lines as far off walls and ledges as the path's steps were. */
    public PathSmoother keepingRoom(Clearance clearance) {
        return new PathSmoother(validator, clearance, overSteps);
    }

    /**
     * The same, with lines that run on over ground that steps up or down by slabs and stairs
     * ({@link MoveValidator#walkAlong}) instead of stopping at every step: a staircase of slabs
     * walked at an angle is one line, not the grid's zigzag.
     */
    public PathSmoother overSteps() {
        return new PathSmoother(validator, room, true);
    }

    public List<Waypoint> smooth(List<PathStep> path) {
        if (path.isEmpty()) {
            return List.of();
        }
        List<Waypoint> out = new ArrayList<>();
        out.add(new Waypoint(path.get(0).pos(), Waypoint.Kind.START));

        int anchor = 0; // always the step just before i
        int i = 1;
        while (i < path.size()) {
            PathStep step = path.get(i);
            Waypoint.Kind kept = kept(step.via());
            if (kept != null) {
                out.add(new Waypoint(step.pos(), kept));
                anchor = i++;
                continue;
            }
            // A flat step: the raw step itself is valid, so reach at least this far.
            BlockPoint from = path.get(anchor).pos();
            int best = i;
            int least = room == null ? 0 : Math.min(roomAt(from), roomAt(step.pos()));
            for (int j = i + 1; j < path.size() && isFlat(path.get(j)); j++) {
                BlockPoint to = path.get(j).pos();
                if (room != null) {
                    least = Math.min(least, roomAt(to));
                }
                if (overSteps) {
                    List<BlockPoint> cells = validator.walkAlong(from, to);
                    if (cells == null || (room != null && least > 0 && !keepsRoom(cells, least))) {
                        break;
                    }
                } else if (!validator.canWalkStraight(from, to)
                        || (room != null && least > 0 && !keepsRoom(from, to, least))) {
                    break;
                }
                best = j;
            }
            out.add(new Waypoint(path.get(best).pos(), Waypoint.Kind.STRAIGHT));
            anchor = best;
            i = best + 1;
        }
        return List.copyOf(out);
    }

    private int roomAt(BlockPoint p) {
        return room.at(p.x(), p.y(), p.z());
    }

    /** Whether every one of a line's cells has at least {@code least} room. */
    private boolean keepsRoom(List<BlockPoint> cells, int least) {
        for (int k = 1; k < cells.size() - 1; k++) {
            if (roomAt(cells.get(k)) < least) {
                return false;
            }
        }
        return true;
    }

    /** Whether every cell the line's centre crosses has at least {@code least} room. */
    private boolean keepsRoom(BlockPoint from, BlockPoint to, int least) {
        double dx = to.x() - from.x();
        double dz = to.z() - from.z();
        int samples = (int) Math.ceil(Math.max(Math.abs(dx), Math.abs(dz)) * 8);
        int lastX = Integer.MIN_VALUE;
        int lastZ = Integer.MIN_VALUE;
        for (int k = 1; k < samples; k++) {
            double t = (double) k / samples;
            int x = (int) Math.floor(from.x() + 0.5 + dx * t);
            int z = (int) Math.floor(from.z() + 0.5 + dz * t);
            if (x == lastX && z == lastZ) {
                continue;
            }
            lastX = x;
            lastZ = z;
            if (room.at(x, from.y(), z) < least) {
                return false;
            }
        }
        return true;
    }

    /** The waypoint kind for a move that is kept exactly, or null for a flat one. */
    private static Waypoint.Kind kept(MoveType via) {
        return switch (via) {
            case JUMP_UP -> Waypoint.Kind.JUMP_UP;
            case DROP -> Waypoint.Kind.DROP;
            case SWIM -> Waypoint.Kind.SWIM;
            case CLIMB -> Waypoint.Kind.CLIMB;
            case WARP -> Waypoint.Kind.WARP;
            case TRANSMIT -> Waypoint.Kind.TRANSMIT;
            case WALK, DIAGONAL -> null;
        };
    }

    private static boolean isFlat(PathStep step) {
        return step.via() == MoveType.WALK || step.via() == MoveType.DIAGONAL;
    }

    /** Length of the polyline through the waypoints (or any points), in blocks. */
    public static double length(List<BlockPoint> points) {
        double total = 0;
        for (int i = 0; i + 1 < points.size(); i++) {
            BlockPoint a = points.get(i);
            BlockPoint b = points.get(i + 1);
            int dx = b.x() - a.x();
            int dy = b.y() - a.y();
            int dz = b.z() - a.z();
            total += Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        return total;
    }

    public static List<BlockPoint> positions(List<Waypoint> waypoints) {
        return waypoints.stream().map(Waypoint::pos).toList();
    }
}
