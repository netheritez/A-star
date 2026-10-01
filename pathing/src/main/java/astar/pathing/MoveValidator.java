package astar.pathing;

import astar.core.BlockPoint;
import astar.core.MoveSource.MoveSink;
import astar.core.MoveType;
import astar.core.Pos;
import java.util.ArrayList;
import java.util.List;

/**
 * The movement rules, as pure checks over a {@link BlockView} for one {@link EntityProfile}.
 *
 * <p>A node is the cell the entity's feet are in, and its <b>elevation</b> is the exact height
 * the feet rest at: {@code y} on top of a full block, {@code y + 0.5} on a slab or on the low
 * step of stairs, {@code y + 1/16} on carpet. Nothing stands on a fence or wall (too thin on top;
 * a swimmer over one is held at {@code y + 0.5}). Moves compare elevations the way Minecraft
 * does:
 *
 * <ul>
 *   <li>a rise of up to {@code stepHeight} (0.6) is a walk: slabs, stair steps, carpet;
 *   <li>a rise of up to {@code jumpHeight} (1.25) is a jump; anything higher is impossible,
 *       so 1.5-tall fences can't be jumped;
 *   <li>a fall of more than {@code stepHeight} is a drop, up to {@code maxDrop}, or from any
 *       height into water.
 * </ul>
 *
 * <p>Water and ladders hold the entity up. A cell of either is somewhere to be, with the feet
 * at its bottom, even with nothing underneath:
 *
 * <ul>
 *   <li>every move out of water is a swim (level, up onto a bank or down), and so is a level
 *       move into water and a move straight up into a water cell;
 *   <li>a move straight up or down between a ladder cell and the cell below it is a climb, and
 *       so is a level move onto or off a ladder where there's no floor to stand on;
 *   <li>everything else (dropping into a pool, stepping off a ladder's top onto a ledge,
 *       letting go of a ladder) follows the rules above.
 * </ul>
 *
 * <p>The body is {@code height} tall and must fit through everything it passes: at both ends,
 * and in the space it sweeps while stepping, jumping or falling.
 *
 * <p>Every block is read as its {@link BlockType#shape shape} for this entity: a door it can
 * open is air, one it can't is a wall; soul sand is a 14/16 floor, a cobweb is air, and so on.
 * What those blocks cost is up to {@link TerrainCostModel}.
 */
public final class MoveValidator {
    /** Returned by {@link #dropLanding} when there is nowhere safe to land. */
    public static final int NO_LANDING = Integer.MIN_VALUE;
    /**
     * Floors this close in height count as level for straight lines: carpet (1/16), farmland
     * and dirt paths (15/16) and soul sand (14/16) next to full blocks.
     */
    public static final double LEVEL = 0.125;
    private static final double EPS = 1e-9;
    private static final int[][] CARDINAL = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] DIAGONAL = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
    /** One across and two along: the headings about 27 and 63 degrees off the axes. */
    private static final int[][] KNIGHT = {{1, 2}, {2, 1}, {-1, 2}, {-2, 1}, {1, -2}, {2, -1},
            {-1, -2}, {-2, -1}};

    private final BlockView world;
    private final EntityProfile profile;
    private final BlockType[] shapes;

    public MoveValidator(BlockView world, EntityProfile profile) {
        this.world = world;
        this.profile = profile;
        this.shapes = BlockType.shapes(profile.opensDoors());
    }

    /**
     * The block at (x, y, z) as this entity moves through it: its {@link BlockType#shape shape},
     * so an open-able door is air or a wall depending on the entity. Every rule reads blocks
     * through this.
     */
    BlockType at(int x, int y, int z) {
        return shapes[world.blockAt(x, y, z).ordinal()];
    }

    public BlockView world() {
        return world;
    }

    public EntityProfile profile() {
        return profile;
    }

    // ---- Collision geometry ----------------------------------------------------------------

    /**
     * How far up from the bottom of cell {@code k} the column (x, z) is blocked (0 = open).
     * Includes the top half-block of a 1.5-tall block in the cell below. Hazards and the void
     * block the whole cell.
     */
    double collisionIn(int x, int k, int z) {
        return collision(at(x, k, z), at(x, k - 1, z));
    }

    private static double collision(BlockType b, BlockType below) {
        double c = (b == BlockType.HAZARD || b == BlockType.VOID) ? 1 : b.height();
        if (c < 0.5 && below == BlockType.TALL) {
            c = 0.5;
        }
        return c;
    }

    /** Whether nothing solid is anywhere in the column (x, z) between heights a and b. */
    boolean clearBand(int x, int z, double a, double b) {
        return freeTop(x, z, a, b) >= b - EPS;
    }

    /**
     * How high the column (x, z) is clear above height a: the bottom of the first thing solid
     * above it, or {@code limit} if nothing is below that. So the band from a to any b up to
     * the limit is clear exactly when b is at most this. One scan answers every such band.
     */
    double freeTop(int x, int z, double a, double limit) {
        int k = (int) Math.floor(a + EPS);
        BlockType below = at(x, k - 1, z);
        for (; k < limit - EPS; k++) {
            BlockType b = at(x, k, z);
            double c = collision(b, below);
            if (c > 0 && k + c > a + EPS) {
                return Math.max(k, a);
            }
            below = b;
        }
        return limit;
    }

    /**
     * The height the entity's feet rest at when standing in cell (x, y, z), or {@code NaN} if it
     * can't stand there: no floor in the cell or right below it, a hazard underfoot, or not
     * enough headroom for its body.
     */
    public double elevation(int x, int y, int z) {
        double e = floorAt(x, y, z);
        return !Double.isNaN(e) && clearBand(x, z, e, e + profile.height()) ? e : Double.NaN;
    }

    /** The floor the feet would rest on in cell (x, y, z), ignoring headroom; NaN if none. */
    private double floorAt(int x, int y, int z) {
        BlockType here = at(x, y, z);
        if (!here.passable() && !here.isPartialFloor()) {
            return Double.NaN; // inside a full block, a hazard or the void
        }
        return floorOf(y, here, at(x, y - 1, z));
    }

    /** {@link #floorAt} from the blocks already read: in the cell and the one below. */
    private static double floorOf(int y, BlockType here, BlockType below) {
        if (!here.passable() && !here.isPartialFloor()) {
            return Double.NaN;
        }
        // No standing on the top of a fence or wall below: it's a thin post, and the body falls
        // off it (in water, the post holds up a swimmer, though).
        if (below == BlockType.TALL && here.height() < 0.5 && !here.holds()) {
            return Double.NaN;
        }
        double floor = collision(here, below);
        if (floor > 0) {
            return y + floor; // a slab, stairs, carpet...
        }
        // On top of a full block or of stairs, or held up by water or a ladder.
        return below.supports() || here.holds() ? y : Double.NaN;
    }

    /**
     * How an entity with its feet in this cell moves on the level, from the blocks in the cell
     * and below it: {@link MoveType#SWIM} in water, {@link MoveType#CLIMB} on a ladder with no
     * floor under it, or null when it walks.
     */
    private static MoveType heldBy(BlockType here, BlockType below) {
        if (here == BlockType.WATER) {
            return MoveType.SWIM;
        }
        if ((here == BlockType.CLIMBABLE || here == BlockType.SCAFFOLDING)
                && collision(here, below) == 0 && !below.supports()) {
            return MoveType.CLIMB;
        }
        return null;
    }

    /** Standing on a floor, not swimming or hanging on a ladder. */
    boolean onFoot(int x, int y, int z) {
        return heldBy(at(x, y, z), at(x, y - 1, z)) == null;
    }

    public double elevation(BlockPoint p) {
        return elevation(p.x(), p.y(), p.z());
    }

    /** Somewhere to stand, with headroom. */
    public boolean canStand(int x, int y, int z) {
        return !Double.isNaN(elevation(x, y, z));
    }

    /** Air (no collision) for the entity's full height, starting at the bottom of cell y. */
    public boolean isClear(int x, int y, int z) {
        return clearBand(x, z, y, y + profile.height());
    }

    /**
     * Whether the edge of the entity's body, at feet height {@code y}, may pass through this
     * column: clear for its full height and not right above a hazard. It needs no floor.
     */
    public boolean canBrushPast(int x, int y, int z) {
        return canBrushPast(x, (double) y, z);
    }

    boolean canBrushPast(int x, double e, int z) {
        return clearBand(x, z, e, e + profile.height())
                && at(x, (int) Math.floor(e - EPS), z) != BlockType.HAZARD;
    }

    // ---- Moves -------------------------------------------------------------------------------

    /**
     * Every move out of cell (x, y, z): to each neighbouring column (a walk, jump, drop, swim or
     * climb to each floor there that the body can reach), diagonals on level ground if enabled,
     * on stairs, the in-place step between the low step and the top, and swimming or climbing
     * straight up and down.
     */
    public void moves(int x, int y, int z, boolean diagonal, MoveSink sink) {
        moves(x, y, z, diagonal ? 8 : 4, sink);
    }

    /**
     * The same, heading in 4, 8 or 16 directions. With 16, the moves one block across and two
     * along are added where the body can walk the straight line on level ground
     * ({@link #canWalkStraight}), as {@link MoveType#DIAGONAL} moves.
     */
    public void moves(int x, int y, int z, int directions, MoveSink sink) {
        boolean diagonal = directions >= 8;
        BlockType here = at(x, y, z);
        BlockType under = at(x, y - 1, z);
        double eA = floorOf(y, here, under);
        if (Double.isNaN(eA)) {
            return;
        }
        // How far up the body can reach from here, found once: no move's ceiling is higher.
        double reach = Math.max(profile.jumpHeight(), profile.stepHeight()) + profile.height();
        double hereTop = freeTop(x, z, eA, eA + reach);
        if (hereTop < eA + profile.height() - EPS) {
            return; // no headroom: can't stand here
        }
        MoveType held = heldBy(here, under);
        for (int[] d : CARDINAL) {
            neighbours(x, z, eA, hereTop, held, d[0], d[1], false, sink);
        }
        if (diagonal) {
            for (int[] d : DIAGONAL) {
                neighbours(x, z, eA, hereTop, held, d[0], d[1], true, sink);
            }
        }
        if (directions >= 16 && held == null) {
            BlockPoint from = new BlockPoint(x, y, z);
            for (int[] d : KNIGHT) {
                if (canWalkStraight(from, new BlockPoint(x + d[0], y, z + d[1]))) {
                    sink.accept(Pos.pack(x + d[0], y, z + d[1]), MoveType.DIAGONAL);
                }
            }
        }
        // Up and down the same column. On stairs: step between the low step (in the stairs'
        // cell) and the top (the cell above). Otherwise swim or climb into a water or ladder
        // cell above, or out of this one into the cell below.
        // A stair step with water above it is a swim, like every other move into or out of water.
        // Scaffolding is climbed down into from on top of it too (sneaking). Bubble columns
        // carry a swimmer one way only: no swimming down in a rising one, or up in a sinking one.
        BlockType above = at(x, y + 1, z);
        if ((here == BlockType.STAIRS || above.holds()) && !Double.isNaN(elevation(x, y + 1, z))
                && !(here != BlockType.STAIRS && sinks(x, y, z))) {
            sink.accept(Pos.pack(x, y + 1, z), here == BlockType.STAIRS
                    ? (above == BlockType.WATER ? MoveType.SWIM : MoveType.WALK) : holdMove(above));
        }
        if ((under == BlockType.STAIRS || here.holds() || under == BlockType.SCAFFOLDING)
                && !Double.isNaN(elevation(x, y - 1, z))
                && !(under != BlockType.STAIRS && rises(x, y - 1, z))) {
            sink.accept(Pos.pack(x, y - 1, z), under == BlockType.STAIRS
                    ? (here == BlockType.WATER ? MoveType.SWIM : MoveType.WALK)
                    : holdMove(here.holds() ? here : under));
        }
    }

    /** Whether a bubble column drags down in cell (x, y, z) or the one above it. */
    private boolean sinks(int x, int y, int z) {
        return world.blockAt(x, y, z) == BlockType.BUBBLE_DOWN
                || world.blockAt(x, y + 1, z) == BlockType.BUBBLE_DOWN;
    }

    /** Whether a bubble column lifts in cell (x, y, z) or the one above it. */
    private boolean rises(int x, int y, int z) {
        return world.blockAt(x, y, z) == BlockType.BUBBLE_UP
                || world.blockAt(x, y + 1, z) == BlockType.BUBBLE_UP;
    }

    /** How much further than the drop limit a fall into water may go, in blocks. */
    private static final int WATER_FALL = 384;

    /** Moving up or down within water is a swim; on a ladder, a climb. */
    private static MoveType holdMove(BlockType b) {
        return b == BlockType.WATER ? MoveType.SWIM : MoveType.CLIMB;
    }

    private void neighbours(int x, int z, double eA, double hereTop, MoveType held, int dx, int dz,
            boolean diagonal, MoveSink sink) {
        int tx = x + dx;
        int tz = z + dz;
        // Diagonal jumps and drops only if the profile allows them.
        boolean leaps = !diagonal || profile.diagonalLeaps();
        double rise = profile.canJump() && leaps ? profile.jumpHeight() : profile.stepHeight();
        double fall = leaps ? Math.max(profile.stepHeight(), profile.maxDrop()) : profile.stepHeight();
        int top = (int) Math.floor(eA + rise + EPS);
        int bottom = (int) Math.floor(eA - fall - EPS);
        // A fall into water is safe from any height: below the drop limit, only into water.
        int deepest = leaps && profile.maxDrop() > 0 ? bottom - WATER_FALL : bottom;
        // Scanning down, the first cell that isn't air hides every floor below it: to reach a
        // lower floor the body would pass through it. (It's below the move's ceiling because a
        // rise is always less than the body's height; for a body shorter than that, scan all.)
        boolean stopAtFirst = rise < profile.height();
        BlockType cell = at(tx, top, tz);
        boolean last = false;
        for (int y2 = top; y2 >= deepest && !last; y2--) {
            // Not air: this cell may hold a floor, but nothing below it can be reached.
            last = stopAtFirst && !cell.passable();
            // Each block is read once: the cell below this one is the next one scanned.
            boolean floorCell = cell.passable() || cell.isPartialFloor();
            BlockType below = floorCell || !last ? at(tx, y2 - 1, tz) : null;
            // Standing on scaffolding from above: its top holds the body, so nothing below.
            last |= stopAtFirst && below == BlockType.SCAFFOLDING && cell != BlockType.SCAFFOLDING;
            double eB = floorCell ? floorOf(y2, cell, below) : Double.NaN;
            BlockType target = cell;
            cell = below;
            boolean deep = y2 < bottom;
            if (Double.isNaN(eB) || (deep && target != BlockType.WATER)) {
                continue;
            }
            // Rising bubbles hold a body up at the top of their column: it can't fall past.
            if (target == BlockType.WATER && y2 < top
                    && world.blockAt(tx, y2, tz) == BlockType.BUBBLE_UP
                    && world.blockAt(tx, y2 + 1, tz) == BlockType.BUBBLE_UP) {
                continue;
            }
            MoveType type = deep ? MoveType.DROP : classify(eB - eA, diagonal);
            if (type == null) {
                continue;
            }
            if (held == MoveType.SWIM) {
                type = MoveType.SWIM; // every move out of water, up or down too
            } else if (type == MoveType.WALK || type == MoveType.DIAGONAL) {
                type = levelMove(type, held, heldBy(target, below));
            }
            // Room for the body over both floors, up to the higher one plus its height. This
            // also covers the headroom to stand on the target floor.
            double ceiling = Math.max(eA, eB) + profile.height();
            if (ceiling > hereTop + EPS || freeTop(tx, tz, eB, ceiling) < ceiling - EPS) {
                continue;
            }
            if (diagonal) {
                // The body passes the corner: both side columns must be clear, not over lava,
                // over every height it passes, from the lower floor to the higher one's
                // headroom (so the whole of a diagonal jump or fall).
                double low = Math.min(eA, eB);
                if (!sideClear(tx, z, low, ceiling) || !sideClear(x, tz, low, ceiling)) {
                    continue;
                }
            }
            sink.accept(Pos.pack(tx, y2, tz), type);
        }
    }

    /**
     * Whether the edge of the body may pass over (x, z) while its feet are between lo and hi:
     * clear all the way up and not above a hazard, or a floor at about the same height.
     */
    private boolean canBrushPast(int x, int y, int z, double lo, double hi) {
        if (clearBand(x, z, lo, hi + profile.height())
                && at(x, (int) Math.floor(lo - EPS), z) != BlockType.HAZARD) {
            return true;
        }
        double e = elevation(x, y, z);
        return !Double.isNaN(e)
                && Math.max(hi, e) - Math.min(lo, e) <= LEVEL + EPS
                && clearBand(x, z, e, Math.max(hi, e) + profile.height());
    }

    private boolean sideClear(int x, int z, double low, double ceiling) {
        return clearBand(x, z, low, ceiling)
                && at(x, (int) Math.floor(low - EPS), z) != BlockType.HAZARD;
    }

    /**
     * A level move is a swim if either end is in water, else a climb if either end hangs on a
     * ladder, else the walk or diagonal it was.
     */
    private static MoveType levelMove(MoveType walk, MoveType from, MoveType to) {
        if (from == MoveType.SWIM || to == MoveType.SWIM) {
            return MoveType.SWIM;
        }
        return from != null ? from : to != null ? to : walk;
    }

    /** What kind of move a change in elevation is, or null if it's impossible. */
    private MoveType classify(double delta, boolean diagonal) {
        if (Math.abs(delta) <= profile.stepHeight() + EPS) {
            return diagonal ? MoveType.DIAGONAL : MoveType.WALK;
        }
        if (diagonal && !profile.diagonalLeaps()) {
            return null;
        }
        if (delta > 0) {
            return profile.canJump() && delta <= profile.jumpHeight() + EPS ? MoveType.JUMP_UP : null;
        }
        return -delta <= profile.maxDrop() + EPS ? MoveType.DROP : null;
    }

    // ---- Single-move checks (convenient for tests and tools) --------------------------------

    private MoveType moveTo(int x, int y, int z, int dx, int y2, int dz) {
        MoveType[] found = {null};
        long target = Pos.pack(x + dx, y2, z + dz);
        moves(x, y, z, dx != 0 && dz != 0, (to, type) -> {
            if (to == target) {
                found[0] = type;
            }
        });
        return found[0];
    }

    /** A walk (a rise or fall within the step height) into the neighbouring column. */
    public boolean canWalk(int x, int y, int z, int dx, int dz) {
        for (int y2 = y - 1; y2 <= y + 1; y2++) {
            if (moveTo(x, y, z, dx, y2, dz) == MoveType.WALK) {
                return true;
            }
        }
        return false;
    }

    /** A jump up into the neighbouring column (a rise above the step height). */
    public boolean canJumpUp(int x, int y, int z, int dx, int dz) {
        for (int y2 = y; y2 <= y + 2; y2++) {
            if (moveTo(x, y, z, dx, y2, dz) == MoveType.JUMP_UP) {
                return true;
            }
        }
        return false;
    }

    /**
     * Steps off into the neighbouring column and falls to the first floor there.
     *
     * @return the landing cell's y, or {@link #NO_LANDING} if there's no drop that way
     */
    public int dropLanding(int x, int y, int z, int dx, int dz) {
        int[] landing = {NO_LANDING};
        long column = Pos.pack(x + dx, 0, z + dz);
        moves(x, y, z, false, (to, type) -> {
            if (type == MoveType.DROP && Pos.pack(Pos.x(to), 0, Pos.z(to)) == column) {
                landing[0] = Math.max(landing[0], Pos.y(to));
            }
        });
        return landing[0];
    }

    /** A diagonal step on (nearly) level ground, not clipping a corner or brushing lava. */
    public boolean canWalkDiagonal(int x, int y, int z, int dx, int dz) {
        for (int y2 = y - 1; y2 <= y + 1; y2++) {
            if (moveTo(x, y, z, dx, y2, dz) == MoveType.DIAGONAL) {
                return true;
            }
        }
        return false;
    }

    // ---- Straight lines, for smoothing --------------------------------------------------------

    /**
     * Whether the entity can walk in a straight line, at any angle, from the centre of one block
     * to the centre of another, on (nearly) level floor the whole way.
     *
     * <p>The body is a capsule: the centre line widened by half the entity's width. Every cell
     * the centre line passes through must be a floor in the same layer (to stand on, not
     * water or a ladder with nothing under it), all within {@link
     * #LEVEL} of each other in height (so carpet and dirt paths don't break a line, but slabs
     * and stairs do); every other cell the capsule overlaps, including one the line only touches
     * at a corner, must be clear for the body at every height it walks at, and not above lava.
     * For a single level grid step this is exactly the walk and diagonal rules.
     *
     * <p>Lines also keep off {@link BlockType#special special} blocks: no door, cobweb or berry
     * bush where the body passes, and no soul sand, honey or magma underfoot on the centre line.
     * Those steps keep their grid route, which the search priced.
     */
    public boolean canWalkStraight(BlockPoint from, BlockPoint to) {
        if (from.y() != to.y() || Double.isNaN(elevation(from))) {
            return false;
        }
        int y = from.y();
        double ax = from.x() + 0.5;
        double az = from.z() + 0.5;
        double bx = to.x() + 0.5;
        double bz = to.z() + 0.5;
        double r = profile.width() / 2;

        int minX = (int) Math.floor(Math.min(ax, bx) - r);
        int maxX = (int) Math.floor(Math.max(ax, bx) + r);
        // Only the cells the body can reach: a strip along the line, not its bounding box.
        int[] rows = Geometry.rowsNear(ax, az, bx, bz, r, minX, maxX);
        int columns = maxX - minX + 1;

        // The floors under the centre line, and the range of heights the feet walk at.
        double lo = Double.POSITIVE_INFINITY;
        double hi = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < columns; i++) {
            int cx = minX + i;
            for (int cz = rows[2 * i]; cz <= rows[2 * i + 1]; cz++) {
                if (Geometry.crossesInterior(ax, az, bx, bz, cx, cz)) {
                    double e = elevation(cx, y, cz);
                    if (Double.isNaN(e) || !onFoot(cx, y, cz)
                            || world.blockAt(cx, y - 1, cz).special() || !plainBody(cx, y, cz)) {
                        return false;
                    }
                    lo = Math.min(lo, e);
                    hi = Math.max(hi, e);
                }
            }
        }
        if (hi - lo > LEVEL + EPS) {
            return false;
        }
        // The cells the body's edge passes over.
        for (int i = 0; i < columns; i++) {
            int cx = minX + i;
            for (int cz = rows[2 * i]; cz <= rows[2 * i + 1]; cz++) {
                if (!Geometry.crossesInterior(ax, az, bx, bz, cx, cz)
                        && Geometry.distanceToCell(ax, az, bx, bz, cx, cz) < r
                        && (!canBrushPast(cx, y, cz, lo, hi) || !plainBody(cx, y, cz))) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * The cells a straight walk from the centre of one block to the centre of another passes
     * through, in order and each in its layer, over ground that may rise and fall by steps the
     * feet take without jumping (slabs, stairs, up to {@link EntityProfile#stepHeight}) between
     * one cell and the next; null if it can't be walked that way.
     *
     * <p>The same rules as {@link #canWalkStraight} otherwise: every cell under the centre line is
     * a plain floor with headroom, and every other cell the body's edge passes over is clear for
     * it from the lowest floor near it to the highest, or a floor of its own no higher than a
     * step above those. On level ground it's the same line as {@link #canWalkStraight}'s. A line
     * that passes exactly through a corner where the ground steps is refused (the grid can't
     * cut across a step).
     */
    public List<BlockPoint> walkAlong(BlockPoint from, BlockPoint to) {
        double start = elevation(from);
        if (Double.isNaN(start) || from.equals(to)) {
            return null;
        }
        double ax = from.x() + 0.5;
        double az = from.z() + 0.5;
        double bx = to.x() + 0.5;
        double bz = to.z() + 0.5;
        double dx = bx - ax;
        double dz = bz - az;
        List<Double> ts = new ArrayList<>();
        ts.add(0.0);
        ts.add(1.0);
        crossings(ax, dx, ts);
        crossings(az, dz, ts);
        double[] t = ts.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        // The columns the line crosses, in order.
        List<int[]> columns = new ArrayList<>();
        int lastX = from.x();
        int lastZ = from.z();
        for (int i = 0; i + 1 < t.length; i++) {
            if (t[i + 1] - t[i] < EPS) {
                continue;
            }
            double mid = (t[i] + t[i + 1]) / 2;
            int cx = (int) Math.floor(ax + mid * dx);
            int cz = (int) Math.floor(az + mid * dz);
            if (cx != lastX || cz != lastZ) {
                columns.add(new int[] {cx, cz});
                lastX = cx;
                lastZ = cz;
            }
        }
        if (lastX != to.x() || lastZ != to.z()) {
            return null;
        }
        // Each column's layer: a stair's cell can hold two floors (its step and its back), so
        // pick them together, the one nearest the last floor first.
        List<BlockPoint> cells = new ArrayList<>(columns.size() + 1);
        List<Double> floors = new ArrayList<>(columns.size() + 1);
        cells.add(from);
        floors.add(start);
        int[] budget = {64 + 4 * columns.size()};
        if (!layers(columns, 0, to, cells, floors, budget)) {
            return null;
        }
        // The cells the body's edge passes over, against the floors of the line's cells near
        // each.
        double r = profile.width() / 2;
        int minX = (int) Math.floor(Math.min(ax, bx) - r);
        int maxX = (int) Math.floor(Math.max(ax, bx) + r);
        int[] rows = Geometry.rowsNear(ax, az, bx, bz, r, minX, maxX);
        for (int i = 0; i < maxX - minX + 1; i++) {
            int cx = minX + i;
            for (int cz = rows[2 * i]; cz <= rows[2 * i + 1]; cz++) {
                if (Geometry.crossesInterior(ax, az, bx, bz, cx, cz)
                        || Geometry.distanceToCell(ax, az, bx, bz, cx, cz) >= r) {
                    continue;
                }
                double lo = Double.POSITIVE_INFINITY;
                double hi = Double.NEGATIVE_INFINITY;
                int y = 0;
                for (int k = 0; k < cells.size(); k++) {
                    BlockPoint c = cells.get(k);
                    if (Math.abs(c.x() - cx) <= 1 && Math.abs(c.z() - cz) <= 1) {
                        if (floors.get(k) < lo) {
                            y = c.y();
                        }
                        lo = Math.min(lo, floors.get(k));
                        hi = Math.max(hi, floors.get(k));
                    }
                }
                if (lo > hi || !brushesPast(cx, y, cz, lo, hi)) {
                    return null;
                }
            }
        }
        return cells;
    }

    /** Picks the layers of {@code columns} from {@code k} on, after the cells so far. */
    private boolean layers(List<int[]> columns, int k, BlockPoint to, List<BlockPoint> cells,
            List<Double> floors, int[] budget) {
        if (k == columns.size()) {
            return true;
        }
        if (--budget[0] < 0) {
            return false;
        }
        BlockPoint last = cells.get(cells.size() - 1);
        double lastFloor = floors.get(floors.size() - 1);
        int cx = columns.get(k)[0];
        int cz = columns.get(k)[1];
        boolean corner = Math.abs(cx - last.x()) + Math.abs(cz - last.z()) == 2;
        double reach = corner ? LEVEL : profile.stepHeight();
        int[] ys = new int[3];
        double[] es = new double[3];
        int n = 0;
        for (int dy = -1; dy <= 1; dy++) {
            int y = last.y() + dy;
            if (k == columns.size() - 1 && y != to.y()) {
                continue;
            }
            double e = elevation(cx, y, cz);
            if (!Double.isNaN(e) && Math.abs(e - lastFloor) <= reach + EPS
                    && onFoot(cx, y, cz) && !world.blockAt(cx, y - 1, cz).special()
                    && plainBody(cx, y, cz)) {
                int at = n++;
                while (at > 0 && Math.abs(es[at - 1] - lastFloor) > Math.abs(e - lastFloor)) {
                    ys[at] = ys[at - 1];
                    es[at] = es[at - 1];
                    at--;
                }
                ys[at] = y;
                es[at] = e;
            }
        }
        for (int i = 0; i < n; i++) {
            cells.add(new BlockPoint(cx, ys[i], cz));
            floors.add(es[i]);
            if (layers(columns, k + 1, to, cells, floors, budget)) {
                return true;
            }
            cells.remove(cells.size() - 1);
            floors.remove(floors.size() - 1);
        }
        return false;
    }

    /**
     * Whether the body's edge can pass over cell (x, z) while the feet go from {@code lo} to
     * {@code hi} beside it: clear all that way up, or a floor there it would step onto, and
     * nothing special.
     */
    private boolean brushesPast(int x, int y, int z, double lo, double hi) {
        if (clearBand(x, z, lo, hi + profile.height())
                && at(x, (int) Math.floor(lo - EPS), z) != BlockType.HAZARD) {
            return plainBody(x, y, z);
        }
        for (int dy : new int[] {0, 1, -1}) {
            double e = elevation(x, y + dy, z);
            if (!Double.isNaN(e) && e <= hi + profile.stepHeight() + EPS && e >= lo - EPS
                    && clearBand(x, z, e, Math.max(hi, e) + profile.height())
                    && plainBody(x, y + dy, z)) {
                return true;
            }
        }
        return false;
    }

    /** Where a line from {@code start} along {@code d} crosses each whole number, as fractions. */
    private static void crossings(double start, double d, List<Double> ts) {
        if (Math.abs(d) < EPS) {
            return;
        }
        double end = start + d;
        for (double g = Math.ceil(Math.min(start, end)); g <= Math.max(start, end); g++) {
            double t = (g - start) / d;
            if (t > EPS && t < 1 - EPS) {
                ts.add(t);
            }
        }
    }

    /** No {@link BlockType#special special} block in the cells the body fills, feet at y. */
    private boolean plainBody(int x, int y, int z) {
        for (int k = y; k < y + profile.height() + LEVEL; k++) {
            if (world.blockAt(x, k, z).special()) {
                return false;
            }
        }
        return true;
    }
}
