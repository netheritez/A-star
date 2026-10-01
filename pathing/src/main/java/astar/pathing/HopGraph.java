package astar.pathing;

import astar.core.Heuristic;
import astar.core.MoveGraph;
import astar.core.MoveType;
import astar.core.Pos;
import astar.core.TurningCostModel;
import java.util.List;
import java.util.Set;

/**
 * A walking {@link NavGraph} with teleports added to it as moves (etherwarps from {@link
 * WarpHops}, Instant Transmissions from {@link TransmitHops}), so one ordinary A* picks walking
 * and teleports together.
 */
public final class HopGraph {
    private HopGraph() {}

    /**
     * What a teleport pays per block of it that doesn't bring the player nearer the goal, in
     * blocks walked: a player heads for the goal, not wherever a landing happens to be.
     */
    public static final double DEFAULT_DETOUR = 0.5;
    /** Etherwarps shorter than this pay for it: a player etherwarps to skip a good stretch. */
    public static final double DEFAULT_ETHER_LENGTH = 40;
    /** What an etherwarp pays per block it falls short of {@link #DEFAULT_ETHER_LENGTH}. */
    public static final double SHORT_ETHER = 0.15;
    /**
     * What walking pays per block that doesn't bring the player nearer the goal, on a trip that
     * teleports: so a route doesn't walk back to line up a teleport, or step sideways to cast
     * where one straight cast would do.
     */
    public static final double DEFAULT_WALK_DETOUR = 0.25;

    /**
     * Costs for a search to {@code goal} over a graph with teleports: the turns of {@link
     * WarpHops#turns}, and on top of each teleport {@code detour} per block of it that isn't
     * progress toward the goal (its length less how much nearer the goal it lands), and on each
     * etherwarp {@link #SHORT_ETHER} per block under {@code etherLength}, and {@code walkDetour}
     * the same way on every walking move. All of it is extra,
     * never less than the move's time, so the heuristic stays admissible.
     */
    public static TurningCostModel costs(double walkPerTurn, double hopPerTurn, long goal,
            double detour, double etherLength, double walkDetour) {
        TurningCostModel turns = WarpHops.turns(walkPerTurn, hopPerTurn);
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
                double c = turns.turnCost(before, from, to, type);
                if (type == MoveType.WARP || type == MoveType.TRANSMIT) {
                    c += extra(from, to, type, goal, detour, etherLength);
                } else if (walkDetour > 0) {
                    c += extra(from, to, type, goal, walkDetour, 0);
                }
                return c;
            }
        };
    }

    /** What {@link #costs} adds to a teleport from {@code from} to {@code to}. */
    public static double extra(long from, long to, MoveType type, long goal, double detour,
            double etherLength) {
        double len = dist(from, to);
        double c = detour * Math.max(0, len - (dist(from, goal) - dist(to, goal)));
        if (type == MoveType.WARP) {
            c += SHORT_ETHER * Math.max(0, etherLength - len);
        }
        return c;
    }

    private static double dist(long a, long b) {
        double dx = Pos.x(a) - Pos.x(b), dy = Pos.y(a) - Pos.y(b), dz = Pos.z(a) - Pos.z(b);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * One kind of teleport: move k goes from cell {@code from[k]} to cell {@code to[k]} (walking
     * graph ids) for {@code cost[k]}, leaving out the ones numbered in {@code skip}.
     */
    public record Moves(MoveType type, int[] from, int[] to, double[] cost, Set<Integer> skip) {}

    /** {@code walking} with all of {@code sets} added. */
    public static MoveGraph of(NavGraph walking, List<Moves> sets) {
        int[] ws = walking.moveStart();
        int[] wt = walking.moveTo();
        double[] wc = walking.moveCost();
        byte[] wy = walking.moveType();
        int n = walking.cells();
        int[] extra = new int[n + 1];
        for (Moves s : sets) {
            for (int k = 0; k < s.from().length; k++) {
                if (!s.skip().contains(k)) {
                    extra[s.from()[k] + 1]++;
                }
            }
        }
        int[] start = new int[n + 1];
        for (int v = 0; v < n; v++) {
            start[v + 1] = start[v] + (ws[v + 1] - ws[v]) + extra[v + 1];
        }
        int m = start[n];
        int[] to = new int[m];
        double[] price = new double[m];
        byte[] type = new byte[m];
        int[] fill = new int[n];
        for (int v = 0; v < n; v++) {
            int len = ws[v + 1] - ws[v];
            System.arraycopy(wt, ws[v], to, start[v], len);
            System.arraycopy(wc, ws[v], price, start[v], len);
            System.arraycopy(wy, ws[v], type, start[v], len);
            fill[v] = start[v] + len;
        }
        for (Moves s : sets) {
            byte t = (byte) s.type().ordinal();
            for (int k = 0; k < s.from().length; k++) {
                if (s.skip().contains(k)) {
                    continue;
                }
                int e = fill[s.from()[k]]++;
                to[e] = s.to()[k];
                price[e] = s.cost()[k];
                type[e] = t;
            }
        }
        NavGraph w = walking;
        return new MoveGraph() {
            @Override
            public int cell(long pos) {
                return w.cell(pos);
            }

            @Override
            public long[] positions() {
                return w.positions();
            }

            @Override
            public int[] moveStart() {
                return start;
            }

            @Override
            public int[] moveTo() {
                return to;
            }

            @Override
            public double[] moveCost() {
                return price;
            }

            @Override
            public byte[] moveType() {
                return type;
            }

            @Override
            public byte[] moveHeading() {
                return null;
            }
        };
    }

    /**
     * The least any of the teleports in {@code sets} costs per block of straight-line distance
     * it covers, and {@code walkFloor} for walking: that rate times the straight-line distance
     * never overestimates a trip.
     */
    public static double rate(NavGraph walking, List<Moves> sets, double walkFloor) {
        long[] cells = walking.positions();
        double rate = walkFloor;
        for (Moves s : sets) {
            for (int k = 0; k < s.from().length; k++) {
                long a = cells[s.from()[k]], b = cells[s.to()[k]];
                double dx = Pos.x(a) - Pos.x(b), dy = Pos.y(a) - Pos.y(b), dz = Pos.z(a) - Pos.z(b);
                double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (d > 0) {
                    rate = Math.min(rate, s.cost()[k] / d);
                }
            }
        }
        return rate;
    }

    /** The straight-line heuristic at {@code rate} per block. */
    public static Heuristic heuristic(double rate) {
        return (dx, dy, dz) -> rate * Math.sqrt((double) dx * dx + (double) dy * dy
                + (double) dz * dz);
    }
}
