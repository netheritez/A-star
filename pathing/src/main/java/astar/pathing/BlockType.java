package astar.pathing;

/**
 * How the pathfinder sees a block: mainly how tall its collision box is. Adapters map real
 * blocks onto these.
 *
 * <p>Every shape is approximated as full width (a 1 x 1 footprint) rising from the bottom of
 * its cell to {@link #height()}:
 *
 * <ul>
 *   <li>{@link #AIR}: no collision (air, flowers, torches, open doors...)
 *   <li>{@code PARTIAL_1} ... {@code PARTIAL_15}: 1/16 to 15/16 of a block, such as carpet
 *       (1), bottom slabs (8), soul sand (14) or farmland and dirt paths (15). You stand
 *       <i>in</i> the cell, on top of it.
 *   <li>{@link #STAIRS}: bottom-half stairs. You can stand on the low step (0.5) in the cell,
 *       or on top of the stairs (1.0), which counts as the cell above.
 *   <li>{@link #SOLID}: a full block.
 *   <li>{@link #TALL}: 1.5 blocks (fences, walls, closed fence gates). Too high to jump, and
 *       too thin on top to stand on.
 *   <li>{@link #HAZARD}: lava, fire and the like: never passable or safe to stand on.
 *   <li>{@link #VOID}: unloaded or outside the world: treated as a solid barrier.
 *   <li>{@link #WATER}: no collision, but the entity swims in it rather than walking, and it
 *       holds the entity up: a water cell is somewhere to be even with nothing below.
 *   <li>{@link #CLIMBABLE}: ladders and vines. No collision; an entity in the cell is held by
 *       it and can climb up and down.
 *   <li>{@link #SCAFFOLDING}: climbed like a ladder inside (up by jumping, down by sneaking),
 *       and stood on from above, like a full block; walked into from the side.
 * </ul>
 *
 * <p>Blocks that change how a cell is crossed, not just its shape. The pathfinder moves as if
 * each were its {@link #shape shape}; {@link TerrainCostModel} charges for the difference.
 *
 * <ul>
 *   <li>{@link #DOOR} and {@link #GATE}: a closed door (one cell per half) or fence gate that can
 *       be opened by hand. An entity that {@link EntityProfile#opensDoors() opens doors} walks
 *       through as if it were air; for any other it's a full block, or a 1.5-tall fence. (Iron
 *       doors need redstone, so they're imported as plain {@link #SOLID}.)
 *   <li>{@link #SOUL_SAND} (14/16) and {@link #HONEY} (15/16): floors that slow the entity.
 *   <li>{@link #COBWEB}: no collision, but it slows the entity almost to a stop.
 *   <li>{@link #MAGMA}: a full block that hurts to stand on.
 *   <li>{@link #BERRY_BUSH}: no collision, but it slows and hurts whatever walks through it.
 *   <li>{@link #CACTUS}: hurts on contact, so it's a {@link #HAZARD}: never entered or brushed.
 *   <li>{@link #POWDER_SNOW}: entities sink into it and freeze, so it's avoided: a barrier
 *       that can't be stood on (the shape of {@link #VOID}).
 *   <li>{@link #FLOWING_WATER}: water that isn't a source, whose current pushes a swimmer; swims
 *       into it cost more ({@link TerrainCosts#current}).
 *   <li>{@link #BUBBLE_UP} and {@link #BUBBLE_DOWN}: bubble columns, water that lifts (over soul
 *       sand) or drags down (over magma): no swimming down in the first or up in the second.
 *   <li>{@link #THIN}: a block too thin or hollow on top to stand on, though it's more than a
 *       step high: glass panes, iron bars, cauldrons, hoppers, lanterns, chains... (whatever
 *       real shape doesn't hold a body up across its whole top). A barrier, like {@link #VOID}.
 * </ul>
 *
 * <p>New kinds go at the end: {@link ArrayBlockView} stores ordinals.
 */
public enum BlockType {
    AIR(0),
    PARTIAL_1(1), PARTIAL_2(2), PARTIAL_3(3), PARTIAL_4(4), PARTIAL_5(5), PARTIAL_6(6),
    PARTIAL_7(7), PARTIAL_8(8), PARTIAL_9(9), PARTIAL_10(10), PARTIAL_11(11), PARTIAL_12(12),
    PARTIAL_13(13), PARTIAL_14(14), PARTIAL_15(15),
    STAIRS(8),
    SOLID(16),
    TALL(24),
    HAZARD(16),
    VOID(16),
    WATER(0),
    CLIMBABLE(0),
    DOOR(16),
    GATE(24),
    SOUL_SAND(14),
    HONEY(15),
    COBWEB(0),
    MAGMA(16),
    BERRY_BUSH(0),
    CACTUS(16),
    POWDER_SNOW(16),
    SCAFFOLDING(0),
    FLOWING_WATER(0),
    BUBBLE_UP(0),
    BUBBLE_DOWN(0),
    THIN(16);

    private static final BlockType[] SHAPE_OPENER = new BlockType[values().length];
    private static final BlockType[] SHAPE_OTHER = new BlockType[values().length];

    static {
        for (BlockType b : values()) {
            SHAPE_OPENER[b.ordinal()] = b.shapeFor(true);
            SHAPE_OTHER[b.ordinal()] = b.shapeFor(false);
        }
    }

    private final int sixteenths;

    BlockType(int sixteenths) {
        this.sixteenths = sixteenths;
    }

    /** A partial block {@code n}/16 tall, for n = 1..15; 0 is {@link #AIR}, 16 {@link #SOLID}. */
    public static BlockType partial(int n) {
        if (n <= 0) {
            return AIR;
        }
        if (n >= 16) {
            return SOLID;
        }
        return values()[PARTIAL_1.ordinal() + n - 1];
    }

    /**
     * The plain block this one moves like, for an entity that can or can't open doors: one of
     * the kinds from {@link #AIR} to {@link #CLIMBABLE}, or {@link #SCAFFOLDING}. Those are
     * their own shape.
     */
    public BlockType shape(boolean opensDoors) {
        return (opensDoors ? SHAPE_OPENER : SHAPE_OTHER)[ordinal()];
    }

    /** The whole shape table for one kind of entity, indexed by ordinal. */
    static BlockType[] shapes(boolean opensDoors) {
        return (opensDoors ? SHAPE_OPENER : SHAPE_OTHER).clone();
    }

    private BlockType shapeFor(boolean opensDoors) {
        return switch (this) {
            case DOOR -> opensDoors ? AIR : SOLID;
            case GATE -> opensDoors ? AIR : TALL;
            case SOUL_SAND -> PARTIAL_14;
            case HONEY -> PARTIAL_15;
            case COBWEB, BERRY_BUSH -> AIR;
            case MAGMA -> SOLID;
            case CACTUS -> HAZARD;
            case POWDER_SNOW, THIN -> VOID;
            case FLOWING_WATER, BUBBLE_UP, BUBBLE_DOWN -> WATER;
            default -> this;
        };
    }

    /** A door or fence gate that can be opened by hand. */
    public boolean opens() {
        return this == DOOR || this == GATE;
    }

    /** Slows an entity standing on it ({@link #SOUL_SAND}, {@link #HONEY}). */
    public boolean slowFloor() {
        return this == SOUL_SAND || this == HONEY;
    }

    /** Hurts an entity standing on it ({@link #MAGMA}) or in it ({@link #BERRY_BUSH}). */
    public boolean hurts() {
        return this == MAGMA || this == BERRY_BUSH;
    }

    /**
     * Changes how the cell is crossed beyond its shape: opened, slow or hurting. Straight-line
     * smoothing never crosses these.
     */
    public boolean special() {
        return opens() || slowFloor() || hurts() || this == COBWEB;
    }

    /** How tall the collision box is, in blocks (0 to 1.5). */
    public double height() {
        return sixteenths / 16.0;
    }

    /**
     * No collision at all: the entity's body can occupy the whole cell (air, water, ladders).
     * This and the other shape questions below are about the plain kinds; ask them of {@link
     * #shape} for the others.
     */
    public boolean passable() {
        return this == AIR || this == WATER || this == CLIMBABLE || this == SCAFFOLDING;
    }

    /**
     * Holds the entity up with nothing to stand on: it swims in water and climbs ladders. Such
     * a cell is somewhere to be at its bottom, {@code y}, even over a drop.
     */
    public boolean holds() {
        return this == WATER || this == CLIMBABLE || this == SCAFFOLDING;
    }

    /** A floor in its own cell: you stand inside the cell, on top of it (slabs, carpet, stairs). */
    public boolean isPartialFloor() {
        return (ordinal() >= PARTIAL_1.ordinal() && ordinal() <= PARTIAL_15.ordinal()) || this == STAIRS;
    }

    /**
     * Something to stand on from the cell above: full blocks, the top of stairs and
     * scaffolding. (Fences, walls, hazards and the void never are.)
     */
    public boolean supports() {
        return this == SOLID || this == STAIRS || this == SCAFFOLDING;
    }

    /** Water whose current pushes a swimmer: flowing water and bubble columns. */
    public boolean current() {
        return this == FLOWING_WATER || this == BUBBLE_UP || this == BUBBLE_DOWN;
    }

    /** Lava, fire, cactus and the like. */
    public boolean dangerous() {
        return this == HAZARD || this == CACTUS;
    }
}
