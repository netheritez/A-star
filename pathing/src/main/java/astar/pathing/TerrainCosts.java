package astar.pathing;

/**
 * What crossing {@link BlockType#special special} blocks adds to a move, on top of its move
 * type's cost. {@link TerrainCostModel} applies them.
 *
 * @param door added for opening a door or fence gate: stepping into one from outside it
 * @param slow how many times longer a move onto soul sand or honey takes (Minecraft moves you at
 *     0.4 of your speed on both, hence 2.5)
 * @param web how many times longer a move into a cobweb takes (0.25 of your speed across, hence 4)
 * @param damage added for stepping onto magma or into a berry bush: roughly how many blocks of
 *     walking one hit is worth avoiding
 * @param current how many times longer a swim into flowing water or a bubble column takes,
 *     whose current pushes the swimmer about (a rough figure: which way it pushes isn't known)
 * @param wall how much longer a step at the edge ({@link Clearance} 0: against a wall or at a
 *     drop) takes, as a share of the step: 0.5 makes it 1.5 times the cost. A step one cell in
 *     (clearance 1) pays half of that, and further in nothing. Keeps routes off walls and
 *     ledges, as a player would walk. Off (0) by default here; the route tool, the editor
 *     and {@code /goto} use {@link #WALL}.
 */
public record TerrainCosts(double door, double slow, double web, double damage,
        double current, double wall) {
    public static final TerrainCosts DEFAULT = new TerrainCosts(1, 2.5, 4, 5, 1.5, 0);
    /** The {@link #wall} cost the route tool, the editor and {@code /goto} use. */
    public static final double WALL = 0.3;
    /** Special blocks cost nothing extra: only their shape counts. */
    public static final TerrainCosts NONE = new TerrainCosts(0, 1, 1, 0, 1, 0);

    /** With the default {@link #current} and no {@link #wall} cost. */
    public TerrainCosts(double door, double slow, double web, double damage) {
        this(door, slow, web, damage, DEFAULT_CURRENT);
    }

    /** With no {@link #wall} cost. */
    public TerrainCosts(double door, double slow, double web, double damage, double current) {
        this(door, slow, web, damage, current, 0);
    }

    private static final double DEFAULT_CURRENT = 1.5;

    /** The same, with another {@link #wall} cost. */
    public TerrainCosts withWall(double wall) {
        return new TerrainCosts(door, slow, web, damage, current, wall);
    }

    /** Refuses values that would let a move cost less than its move type's cost. */
    public TerrainCosts {
        if (!(door >= 0) || !(damage >= 0) || !(wall >= 0)) {
            throw new IllegalArgumentException("door, damage and wall must be >= 0");
        }
        if (!(slow >= 1) || !(web >= 1) || !(current >= 1)) {
            throw new IllegalArgumentException("slow, web and current must be >= 1");
        }
    }

    public boolean isNone() {
        return equals(NONE);
    }
}
