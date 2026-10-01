package astar.pathing;

import astar.core.BlockPoint;
import astar.core.MoveType;
import astar.core.PathStep;
import java.util.ArrayList;
import java.util.List;

/** Pure helpers for analysing a finished path. */
public final class PathAnalysis {
    private PathAnalysis() {}

    /**
     * Key nodes of a path: the start, the goal, and every step where the path turns, seen from
     * above, or the kind of move changes (so where a jump, drop, swim or climb starts and ends).
     * Going up or down stairs or a slope in a straight line is not a turn, and neither is a
     * move straight up or down (on a ladder, or between a stair's low step and its top).
     */
    public static List<PathStep> keyNodes(List<PathStep> path) {
        if (path.size() <= 2) {
            return List.copyOf(path);
        }
        List<PathStep> keys = new ArrayList<>();
        keys.add(path.get(0));
        for (int i = 1; i + 1 < path.size(); i++) {
            PathStep prev = path.get(i - 1);
            PathStep cur = path.get(i);
            PathStep next = path.get(i + 1);
            if (turns(prev.pos(), cur.pos(), next.pos()) || cur.via() != next.via()) {
                keys.add(cur);
            }
        }
        keys.add(path.get(path.size() - 1));
        return keys;
    }

    /** Whether the heading seen from above changes at {@code b}; false if either move is vertical. */
    static boolean turns(BlockPoint a, BlockPoint b, BlockPoint c) {
        int dx1 = b.x() - a.x();
        int dz1 = b.z() - a.z();
        int dx2 = c.x() - b.x();
        int dz2 = c.z() - b.z();
        if ((dx1 == 0 && dz1 == 0) || (dx2 == 0 && dz2 == 0)) {
            return false;
        }
        // Same heading: parallel and pointing the same way (16-way moves included).
        return dx1 * dz2 != dz1 * dx2 || dx1 * dx2 + dz1 * dz2 < 0;
    }

    /**
     * Whether stepping from {@code from} to {@code to} is a descent: walking down one block
     * (off a step, a stair or a one-block ledge), rather than a drop of two or more.
     */
    public static boolean isDescent(BlockPoint from, BlockPoint to, MoveType via) {
        return via == MoveType.DROP && from.y() - to.y() == 1;
    }

    /** Whether step {@code i} of the path is a descent (see above). */
    public static boolean isDescent(List<PathStep> path, int i) {
        return i > 0 && isDescent(path.get(i - 1).pos(), path.get(i).pos(), path.get(i).via());
    }

    /** How many steps of the path are descents; the other DROP steps are drops. */
    public static int descents(List<PathStep> path) {
        int n = 0;
        for (int i = 1; i < path.size(); i++) {
            n += isDescent(path, i) ? 1 : 0;
        }
        return n;
    }

}
