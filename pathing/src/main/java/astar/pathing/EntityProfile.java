package astar.pathing;

/**
 * The entity being routed, sized in blocks. {@link #PLAYER} is the default.
 *
 * @param height how tall its body is (1.8 for a player, 1.95 for a zombie)
 * @param stepHeight how high it can step up without jumping (0.6 for players and mobs)
 * @param jumpHeight how high a jump can lift its feet (about 1.25); 0 means it can't jump
 * @param maxDrop the furthest it will fall in one move without taking damage
 * @param width how wide it is (0.6 for players and zombies). Straight-line smoothing keeps this
 *     much body clear of walls and hazards.
 * @param opensDoors whether it opens wooden doors and fence gates by hand (players and
 *     villagers do). For anything else a closed door is a wall.
 * @param diagonalLeaps whether it jumps up and drops down diagonally too, not only along x or
 *     z.
 */
public record EntityProfile(double height, double stepHeight, double jumpHeight, double maxDrop,
        double width, boolean opensDoors, boolean diagonalLeaps) {

    public static final EntityProfile PLAYER =
            new EntityProfile(1.8, 0.6, 1.25, 3, 0.6, true, true);
    /** Zombies can't open doors (on Hard they break them down, which isn't modelled). */
    public static final EntityProfile ZOMBIE =
            new EntityProfile(1.95, 0.6, 1.25, 3, 0.6, false, true);
    public static final EntityProfile DEFAULT = PLAYER;

    /** An entity that opens doors and leaps diagonally. */
    public EntityProfile(double height, double stepHeight, double jumpHeight, double maxDrop,
            double width) {
        this(height, stepHeight, jumpHeight, maxDrop, width, true, true);
    }

    /** One that leaps diagonally. */
    public EntityProfile(double height, double stepHeight, double jumpHeight, double maxDrop,
            double width, boolean opensDoors) {
        this(height, stepHeight, jumpHeight, maxDrop, width, opensDoors, true);
    }

    public EntityProfile {
        if (!(height > 0)) {
            throw new IllegalArgumentException("height must be > 0");
        }
        if (stepHeight < 0 || jumpHeight < 0 || maxDrop < 0) {
            throw new IllegalArgumentException("step, jump and drop must be >= 0");
        }
        if (jumpHeight > 0 && jumpHeight < stepHeight) {
            throw new IllegalArgumentException("a jump must reach at least as high as a step");
        }
        if (!(width > 0 && width <= 1)) {
            throw new IllegalArgumentException("width must be in (0, 1]: the footprint is one block");
        }
    }

    public EntityProfile withHeight(double h) {
        return new EntityProfile(h, stepHeight, jumpHeight, maxDrop, width, opensDoors,
                diagonalLeaps);
    }

    public EntityProfile withJumpHeight(double j) {
        return new EntityProfile(height, stepHeight, j, maxDrop, width, opensDoors,
                diagonalLeaps);
    }

    public EntityProfile withMaxDrop(double d) {
        return new EntityProfile(height, stepHeight, jumpHeight, d, width, opensDoors,
                diagonalLeaps);
    }

    public EntityProfile withWidth(double w) {
        return new EntityProfile(height, stepHeight, jumpHeight, maxDrop, w, opensDoors,
                diagonalLeaps);
    }

    public EntityProfile withOpensDoors(boolean opens) {
        return new EntityProfile(height, stepHeight, jumpHeight, maxDrop, width, opens,
                diagonalLeaps);
    }

    public EntityProfile withDiagonalLeaps(boolean leaps) {
        return new EntityProfile(height, stepHeight, jumpHeight, maxDrop, width, opensDoors,
                leaps);
    }

    public boolean canJump() {
        return jumpHeight > 0;
    }
}
