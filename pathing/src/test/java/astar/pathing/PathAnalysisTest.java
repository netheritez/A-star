package astar.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import astar.core.BlockPoint;
import astar.core.MoveType;
import astar.core.PathStep;
import java.util.List;
import org.junit.jupiter.api.Test;

class PathAnalysisTest {

    private static PathStep s(int x, int y, int z, MoveType via) {
        return new PathStep(new BlockPoint(x, y, z), via);
    }

    @Test
    void straightPathHasOnlyEnds() {
        List<PathStep> path = List.of(
                s(0, 1, 0, null), s(1, 1, 0, MoveType.WALK), s(2, 1, 0, MoveType.WALK));
        assertEquals(List.of(path.get(0), path.get(2)), PathAnalysis.keyNodes(path));
    }

    @Test
    void cornersAreKeyNodes() {
        List<PathStep> path = List.of(
                s(0, 1, 0, null), s(1, 1, 0, MoveType.WALK), s(1, 1, 1, MoveType.WALK),
                s(1, 1, 2, MoveType.WALK));
        assertEquals(List.of(path.get(0), path.get(1), path.get(3)), PathAnalysis.keyNodes(path));
    }

    @Test
    void jumpsAndDropsAreKeyNodes() {
        List<PathStep> path = List.of(
                s(0, 1, 0, null),
                s(1, 1, 0, MoveType.WALK),
                s(2, 2, 0, MoveType.JUMP_UP),   // the move changes (a jump)
                s(3, 2, 0, MoveType.WALK),      // and back to walking
                s(4, 2, 0, MoveType.WALK),
                s(5, 1, 0, MoveType.DROP),
                s(6, 1, 0, MoveType.WALK));
        assertEquals(
                List.of(path.get(0), path.get(1), path.get(2), path.get(4), path.get(5), path.get(6)),
                PathAnalysis.keyNodes(path));
    }

    @Test
    void stairsAndSlopesInAStraightLineAreNotKeyNodes() {
        List<PathStep> path = List.of(
                s(0, 1, 0, null), s(1, 2, 0, MoveType.WALK), s(2, 3, 0, MoveType.WALK),
                s(3, 3, 0, MoveType.WALK), s(4, 2, 0, MoveType.WALK),
                s(4, 3, 0, MoveType.WALK),      // straight up the stair's own column
                s(5, 3, 0, MoveType.WALK));
        assertEquals(List.of(path.get(0), path.get(6)), PathAnalysis.keyNodes(path));
    }

    @Test
    void diagonalAnd16WayTurnsAreKeyNodes() {
        List<PathStep> path = List.of(
                s(0, 1, 0, null), s(1, 1, 1, MoveType.DIAGONAL), s(2, 1, 2, MoveType.DIAGONAL),
                s(4, 1, 3, MoveType.DIAGONAL), s(6, 1, 4, MoveType.DIAGONAL));
        assertEquals(List.of(path.get(0), path.get(2), path.get(4)), PathAnalysis.keyNodes(path));
    }

    @Test
    void shortPaths() {
        assertEquals(List.of(), PathAnalysis.keyNodes(List.of()));
        List<PathStep> one = List.of(s(1, 1, 1, null));
        assertEquals(one, PathAnalysis.keyNodes(one));
    }

    @Test
    void oneBlockDownIsADescentAndMoreIsADrop() {
        List<PathStep> path = List.of(
                new PathStep(new BlockPoint(0, 10, 0), null),
                new PathStep(new BlockPoint(1, 9, 0), MoveType.DROP),
                new PathStep(new BlockPoint(2, 6, 0), MoveType.DROP),
                new PathStep(new BlockPoint(3, 6, 0), MoveType.WALK));
        assertEquals(true, PathAnalysis.isDescent(path, 1));
        assertEquals(false, PathAnalysis.isDescent(path, 2));
        assertEquals(false, PathAnalysis.isDescent(path, 3));
        assertEquals(1, PathAnalysis.descents(path));
    }
}
