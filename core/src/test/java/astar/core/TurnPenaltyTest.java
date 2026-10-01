package astar.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class TurnPenaltyTest {
    private static final double EPS = 1e-9;

    private static double turn(int dx1, int dz1, int dx2, int dz2) {
        long before = Pos.pack(-dx1, 0, -dz1);
        long from = Pos.pack(0, 0, 0);
        long to = Pos.pack(dx2, 0, dz2);
        return TurnPenalty.turn(before, from, to);
    }

    @Test
    void turnsAreMeasuredIn45DegreeUnits() {
        assertEquals(0, turn(1, 0, 1, 0), EPS);
        assertEquals(0, turn(1, 1, 1, 1), EPS); // a diagonal run is straight
        assertEquals(1, turn(1, 0, 1, 1), EPS);
        assertEquals(2, turn(1, 0, 0, 1), EPS);
        assertEquals(4, turn(1, 0, -1, 0), EPS);
        assertEquals(Math.toDegrees(Math.atan2(1, 2)) / 45, turn(1, 0, 2, 1), EPS);
        assertEquals(0, turn(0, 0, 1, 0), EPS); // straight up or down has no heading
    }

    /** Headings changed along a path, seen from above. */
    private static int headingChanges(List<PathStep> path) {
        int n = 0;
        for (int i = 2; i < path.size(); i++) {
            BlockPoint a = path.get(i - 2).pos();
            BlockPoint b = path.get(i - 1).pos();
            BlockPoint c = path.get(i).pos();
            if (b.x() - a.x() != c.x() - b.x() || b.z() - a.z() != c.z() - b.z()) {
                n++;
            }
        }
        return n;
    }

    @Test
    void theHeadingTableMatchesTheTurnCost() {
        TurnPenalty p = new TurnPenalty(DefaultCostModel.DEFAULT, 0.3);
        double[] table = p.turnCostByHeading();
        long from = Pos.pack(10, 5, 10);
        for (int in = 0; in < 26; in++) {
            for (int out = 1; out < 26; out++) {
                long before = Pos.offset(from, -TurnPenalty.headingX(in), 0,
                        -TurnPenalty.headingZ(in));
                long to = Pos.offset(from, TurnPenalty.headingX(out), 0, TurnPenalty.headingZ(out));
                assertEquals(p.turnCost(before, from, to, MoveType.WALK), table[in * 26 + out],
                        EPS, in + " -> " + out);
            }
        }
    }

    @Test
    void aTurnCostMakesStraightAndDiagonalRuns() {
        TestGrid grid = TestGrid.of(true,
                "................",
                "................",
                "................",
                "................",
                "................",
                "................");
        BlockPoint start = new BlockPoint(0, 0, 0);
        BlockPoint goal = new BlockPoint(15, 0, 5);
        CostModel costs = DefaultCostModel.DEFAULT;
        SearchResult plain = AStar.findPath(start, goal, grid, costs, Heuristics.OCTILE_XZ);
        SearchResult turning = AStar.findPath(start, goal, grid,
                new TurnPenalty(costs, 0.05), Heuristics.OCTILE_XZ);
        // Same length, but one bend: five diagonals and ten straight steps, in one run each.
        assertEquals(plain.path().size(), turning.path().size());
        assertEquals(1, headingChanges(turning.path()));
        assertEquals(10 + 5 * Math.sqrt(2) + 0.05, turning.cost(), EPS);
        assertTrue(headingChanges(plain.path()) >= 1);
    }

    @Test
    void noTurnCostChangesNothing() {
        TestGrid grid = TestGrid.of(true, "........", ".##.....", "....#...", "........");
        BlockPoint start = new BlockPoint(0, 0, 0);
        BlockPoint goal = new BlockPoint(7, 0, 3);
        SearchResult a = AStar.findPath(start, goal, grid, DefaultCostModel.DEFAULT,
                Heuristics.OCTILE_XZ);
        SearchResult b = AStar.findPath(start, goal, grid,
                new TurnPenalty(DefaultCostModel.DEFAULT, 0), Heuristics.OCTILE_XZ);
        assertEquals(a.path(), b.path());
        assertEquals(a.cost(), b.cost(), EPS);
    }
}
