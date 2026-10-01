package astar.viz;

import java.awt.Color;

/** Colours for each thing the visualizer draws. */
public final class Palette {
    private Palette() {}

    public static final Color BACKGROUND = new Color(0xF7F7F5);
    public static final Color GRID_LINE = new Color(0xC8C8C2);
    public static final Color TEXT = new Color(0x2A2A2E);

    /** Air with a solid block below: somewhere the entity can stand. */
    public static final Color CELL = new Color(0xE4E4E0);
    /** A solid block at this level. */
    public static final Color WALL = new Color(0x3A3A3F);
    /** Air with nothing to stand on below. */
    public static final Color GAP = new Color(0xFFFFFF);
    /** A floor lower than a full block in this cell: slabs, carpet, snow, soul sand... */
    public static final Color PARTIAL = new Color(0xD9C29A);
    /** Bottom-half stairs: a low step here, and the top counts as the cell above. */
    public static final Color STAIRS = new Color(0xB8925A);
    /** A 1.5-tall block (fence, wall, closed gate) at this level, or its top half. */
    public static final Color TALL = new Color(0x7A5230);
    /** Lava, fire and the like, or air directly above them. */
    public static final Color HAZARD = new Color(0xE2583E);
    /** Water: swum through. */
    public static final Color WATER = new Color(0x6FA8DC);
    /** Ladders and vines: climbed. */
    public static final Color LADDER = new Color(0x9E9D24);
    /** Closed doors and fence gates: opened by entities that can. */
    public static final Color DOOR = new Color(0x8E6CB8);
    /** Soul sand, honey and cobwebs: slow to cross. */
    public static final Color SLOW = new Color(0x8C7B6B);
    /** Magma and berry bushes (or standing on magma): hurt. */
    public static final Color HURTS = new Color(0xF0A04B);

    /** Lookahead: nodes the search fully expanded. */
    public static final Color CLOSED = new Color(0x9DB4D3);
    /** Lookahead: frontier nodes still waiting in the open set. */
    public static final Color OPEN = new Color(0xB5DDA4);
    /** Replay: the node being expanded in the current step. */
    public static final Color CURRENT = new Color(0x3F6FB5);

    /** Skeleton: the path itself. */
    public static final Color PATH = new Color(0xF2B63D);
    public static final Color PATH_LINE = new Color(0xB5780B);

    /** The smoothed route: straight segments between waypoints. */
    public static final Color SMOOTH = new Color(0xC2185B);

    /** From above: every node of the path, as a small dot. */
    public static final Color PATH_NODE = new Color(0x202024);

    /** Key nodes: turning points along the path. */
    public static final Color KEY_NODE = new Color(0xD64545);
    /** Where the path jumps up onto this cell. */
    public static final Color JUMP = new Color(0x00838F);
    /** Where the path walks down one block onto this cell (a descent). */
    public static final Color DESCENT = new Color(0x4FB3E8);
    /** Where the path drops two or more blocks onto this cell. */
    public static final Color DROP = new Color(0x1E4FA8);

    /** Editor: outline around the cell under the mouse. */
    public static final Color HOVER = new Color(0x111114);

    public static final Color START = new Color(0x2E7D32);
    public static final Color GOAL = new Color(0x6A1B9A);
}
