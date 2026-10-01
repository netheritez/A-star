package astar.pathing;

import astar.core.CostModel;
import astar.core.MoveType;
import astar.core.Pos;

/**
 * Prices moves across {@link BlockType#special special} blocks, on top of a base model:
 *
 * <pre>
 *   cost = base * slowdown where the move ends * (1 + wall share)
 *        + door (stepping into a door or gate from outside one)
 *        + damage (stepping onto magma or into a berry bush)
 * </pre>
 *
 * <p>The slowdown is {@link TerrainCosts#slow} when the feet land on soul sand or honey, {@link
 * TerrainCosts#web} when the body lands in a cobweb, {@link TerrainCosts#current} when it swims
 * into flowing water or a bubble column (the largest that applies), and 1 otherwise. Only
 * the end is looked at, so each move reads a few blocks rather than twice as many: crossing a
 * strip of soul sand costs the same either way, since every move onto it is charged once.
 *
 * <p>The wall share is {@link TerrainCosts#wall} for a walk, jump or drop that ends at an edge
 * ({@link Clearance} 0), half that one cell in, and 0 further in. Swims and climbs don't pay
 * it: ladders are always on a wall, and a pool's sides don't get in a swimmer's way.
 *
 * <p>Slowdowns are at least 1 and the extras at least 0, so every move still costs at least its
 * base cost, and at least its horizontal length: the heuristic stays admissible.
 */
public final class TerrainCostModel implements CostModel {
    private static final double EPS = 1e-9;

    private final CostModel base;
    private final BlockView world;
    private final EntityProfile profile;
    private final TerrainCosts terrain;
    private final Clearance clearance;

    public TerrainCostModel(CostModel base, BlockView world, EntityProfile profile,
            TerrainCosts terrain) {
        this(base, world, profile, terrain, new Clearance(new MoveValidator(world, profile)));
    }

    /** Measuring room with {@code clearance}, for the same world and entity. */
    public TerrainCostModel(CostModel base, BlockView world, EntityProfile profile,
            TerrainCosts terrain, Clearance clearance) {
        this.base = base;
        this.world = world;
        this.profile = profile;
        this.terrain = terrain;
        this.clearance = clearance;
    }

    public CostModel base() {
        return base;
    }

    public TerrainCosts terrain() {
        return terrain;
    }

    @Override
    public double cost(long from, long to, MoveType type) {
        int b = special(to);
        double c = base.cost(from, to, type);
        if (terrain.wall() > 0 && type != MoveType.SWIM && type != MoveType.CLIMB) {
            int room = clearance.at(to);
            c *= 1 + terrain.wall() * (Clearance.MAX - room) / Clearance.MAX;
        }
        if (b == 0) {
            return c; // the usual case: nothing special where the move ends
        }
        c *= slowdown(b);
        if ((b & DOOR) != 0 && (special(from) & DOOR) == 0) {
            c += terrain.door();
        }
        if ((b & HURTS) != 0) {
            c += terrain.damage();
        }
        return c;
    }

    private static final int SLOW = 1;
    private static final int WEB = 2;
    private static final int DOOR = 4;
    private static final int HURTS = 8;
    private static final int CURRENT = 16;

    private double slowdown(int flags) {
        double s = 1;
        if ((flags & SLOW) != 0) {
            s = terrain.slow();
        }
        if ((flags & WEB) != 0) {
            s = Math.max(s, terrain.web());
        }
        if ((flags & CURRENT) != 0) {
            s = Math.max(s, terrain.current());
        }
        return s;
    }

    /**
     * What's special where an entity stands with its feet in this cell: the floor it's on (the
     * cell itself for soul sand and honey, the one below for magma) and the cells its body fills.
     */
    private int special(long node) {
        int x = Pos.x(node);
        int y = Pos.y(node);
        int z = Pos.z(node);
        BlockType here = world.blockAt(x, y, z);
        BlockType below = world.blockAt(x, y - 1, z);
        int flags = below == BlockType.MAGMA ? HURTS : 0;
        if (here.current()) {
            flags |= CURRENT;
        }
        // The body runs from the floor (in this cell on a partial block, or half a block up over
        // a fence, swimming, as MoveValidator has it) up by its height.
        double floor = here.shape(profile.opensDoors()).height();
        if (floor < 0.5 && below == BlockType.TALL) {
            floor = 0.5;
        }
        double top = y + floor + profile.height();
        for (int k = y; k < top - EPS; k++) {
            BlockType b = k == y ? here : world.blockAt(x, k, z);
            if (b.slowFloor() && k == y) {
                flags |= SLOW;
            } else if (b == BlockType.COBWEB) {
                flags |= WEB;
            } else if (b.opens()) {
                flags |= DOOR;
            } else if (b.hurts()) {
                flags |= HURTS;
            }
        }
        return flags;
    }
}
