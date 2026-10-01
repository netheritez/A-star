package astar.movement.exec;

import astar.core.DefaultCostModel;
import astar.core.Heuristics;
import astar.core.TurnPenalty;
import astar.pathing.ArrayBlockView;
import astar.pathing.BlockView;
import astar.pathing.EntityProfile;
import astar.pathing.TerrainCosts;
import astar.pathing.WorldPathfinder;

/** How /goto searches: the pathfinder the mod plans with, so the route tool can time the same. */
public final class GotoPathfinder {

    /**
     * What climbing and swimming cost per block, well above walking, so routes go around
     * ladders and water, which the executor can't do yet, unless there's no other way.
     */
    public static final double AVOID = 20;
    /**
     * What a drop costs, plus so much per block fallen: twice the library's, so routes walk
     * down stairs and slopes rather than stepping off ledges, unless going round is much
     * longer. On 200 Mines routes that halves the one-block drops for 1% more walking.
     */
    public static final double DROP_BASE = 2;
    public static final double DROP_PER_BLOCK = 1;
    public static final DefaultCostModel COSTS = new DefaultCostModel(
            DefaultCostModel.DEFAULT.walk(), DefaultCostModel.DEFAULT.diagonal(),
            DefaultCostModel.DEFAULT.jumpUp(), DROP_BASE, DROP_PER_BLOCK,
            DefaultCostModel.DEFAULT.stepInPlace(), AVOID, AVOID);

    private GotoPathfinder() {}

    /**
     * Doors stay shut (adventure mode can't open them), so routes go around them. 16 headings
     * and a small cost per turn, so open ground is crossed in fewer, gentler turns. Jumps and
     * drops go diagonally too. Steps along walls and ledges cost a little more ({@link
     * TerrainCosts#WALL}), so routes keep to the middle of the way, as a player walks.
     */
    public static WorldPathfinder create(ArrayBlockView view) {
        return new WorldPathfinder(view,
                EntityProfile.DEFAULT.withOpensDoors(false), 16, COSTS,
                TerrainCosts.DEFAULT.withWall(TerrainCosts.WALL), Heuristics.SIXTEEN_XZ, TurnPenalty.DEFAULT_PER_TURN);
    }

    /**
     * The same rules for a rough route across a whole map (saved chunks, say): 8 headings and
     * no turn cost, as it only guides where the next stretch goes, and a map-sized view has no
     * move graph to make 16 headings cheap.
     */
    public static WorldPathfinder across(BlockView view) {
        return new WorldPathfinder(view, EntityProfile.DEFAULT.withOpensDoors(false), 8, COSTS,
                TerrainCosts.DEFAULT.withWall(TerrainCosts.WALL), Heuristics.OCTILE_XZ, 0);
    }
}
