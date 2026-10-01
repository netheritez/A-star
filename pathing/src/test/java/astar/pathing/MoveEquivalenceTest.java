package astar.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import astar.core.MoveType;
import astar.core.Pos;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * {@link MoveValidator#moves} reads each column once and checks the copy, for speed. This keeps
 * the direct version, which reads the world for every check, and requires the same moves from
 * every cell of many random worlds, including water, ladders, doors and terrain. Both read
 * blocks as the entity's shapes ({@link MoveValidator#at}).
 */
class MoveEquivalenceTest {
    private static final double EPS = 1e-9;
    private static final int[][] CARDINAL = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] DIAGONAL = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    /** The straightforward move generator: every check reads the world. */
    private static TreeMap<Long, MoveType> reference(MoveValidator v, int x, int y, int z,
            boolean diagonal) {
        TreeMap<Long, MoveType> out = new TreeMap<>();
        EntityProfile p = v.profile();
        double eA = elevation(v, x, y, z);
        if (Double.isNaN(eA)) {
            return out;
        }
        for (int pass = 0; pass < (diagonal ? 2 : 1); pass++) {
            boolean diag = pass == 1;
            for (int[] d : diag ? DIAGONAL : CARDINAL) {
                int tx = x + d[0];
                int tz = z + d[1];
                boolean leaps = !diag || p.diagonalLeaps();
                double rise = p.canJump() && leaps ? p.jumpHeight() : p.stepHeight();
                double fall = leaps ? Math.max(p.stepHeight(), p.maxDrop()) : p.stepHeight();
                int bottom = (int) Math.floor(eA - fall - EPS);
                // Into water, a fall may go any way down (well past the drop limit).
                int deepest = leaps && p.maxDrop() > 0 ? bottom - 384 : bottom;
                for (int y2 = (int) Math.floor(eA + rise + EPS); y2 >= deepest; y2--) {
                    double eB = elevation(v, tx, y2, tz);
                    if (Double.isNaN(eB)) {
                        continue;
                    }
                    double delta = eB - eA;
                    MoveType type;
                    if (y2 < bottom) {
                        if (v.at(tx, y2, tz) != BlockType.WATER) {
                            continue;
                        }
                        type = MoveType.DROP;
                    } else if (Math.abs(delta) <= p.stepHeight() + EPS) {
                        type = diag ? MoveType.DIAGONAL : MoveType.WALK;
                    } else if (!leaps) {
                        continue;
                    } else if (delta > 0) {
                        if (!p.canJump() || delta > p.jumpHeight() + EPS) {
                            continue;
                        }
                        type = MoveType.JUMP_UP;
                    } else if (-delta <= p.maxDrop() + EPS) {
                        type = MoveType.DROP;
                    } else {
                        continue;
                    }
                    MoveType a = held(v, x, y, z);
                    MoveType b = held(v, tx, y2, tz);
                    if (a == MoveType.SWIM) {
                        type = MoveType.SWIM;
                    } else if (type == MoveType.WALK || type == MoveType.DIAGONAL) {
                        if (a == MoveType.SWIM || b == MoveType.SWIM) {
                            type = MoveType.SWIM;
                        } else if (a == MoveType.CLIMB || b == MoveType.CLIMB) {
                            type = MoveType.CLIMB;
                        }
                    }
                    double ceiling = Math.max(eA, eB) + p.height();
                    if (!v.clearBand(x, z, eA, ceiling) || !v.clearBand(tx, tz, eB, ceiling)) {
                        continue;
                    }
                    if (diag) {
                        double low = Math.min(eA, eB);
                        if (!side(v, tx, z, low, ceiling) || !side(v, x, tz, low, ceiling)) {
                            continue;
                        }
                    }
                    out.put(Pos.pack(tx, y2, tz), type);
                }
            }
        }
        if (v.at(x, y, z) == BlockType.STAIRS && !Double.isNaN(elevation(v, x, y + 1, z))) {
            out.put(Pos.pack(x, y + 1, z),
                    v.at(x, y + 1, z) == BlockType.WATER ? MoveType.SWIM : MoveType.WALK);
        } else if (!Double.isNaN(elevation(v, x, y + 1, z))) {
            // Swim or climb up into water or a ladder.
            BlockType above = v.at(x, y + 1, z);
            if (above == BlockType.WATER) {
                out.put(Pos.pack(x, y + 1, z), MoveType.SWIM);
            } else if (above == BlockType.CLIMBABLE) {
                out.put(Pos.pack(x, y + 1, z), MoveType.CLIMB);
            }
        }
        if (v.at(x, y - 1, z) == BlockType.STAIRS && !Double.isNaN(elevation(v, x, y - 1, z))) {
            out.put(Pos.pack(x, y - 1, z),
                    v.at(x, y, z) == BlockType.WATER ? MoveType.SWIM : MoveType.WALK);
        } else if (!Double.isNaN(elevation(v, x, y - 1, z))) {
            // Swim or climb down out of water or off a ladder.
            BlockType here = v.at(x, y, z);
            if (here == BlockType.WATER) {
                out.put(Pos.pack(x, y - 1, z), MoveType.SWIM);
            } else if (here == BlockType.CLIMBABLE) {
                out.put(Pos.pack(x, y - 1, z), MoveType.CLIMB);
            }
        }
        return out;
    }

    /** SWIM in water, CLIMB on a ladder with no floor under it, null when standing. */
    private static MoveType held(MoveValidator v, int x, int y, int z) {
        BlockType here = v.at(x, y, z);
        if (here == BlockType.WATER) {
            return MoveType.SWIM;
        }
        boolean floor = v.collisionIn(x, y, z) > 0 || v.at(x, y - 1, z).supports();
        return here == BlockType.CLIMBABLE && !floor ? MoveType.CLIMB : null;
    }

    private static boolean side(MoveValidator v, int x, int z, double low, double ceiling) {
        return v.clearBand(x, z, low, ceiling)
                && v.at(x, (int) Math.floor(low - EPS), z) != BlockType.HAZARD;
    }

    private static double elevation(MoveValidator v, int x, int y, int z) {
        BlockType here = v.at(x, y, z);
        if (!here.passable() && !here.isPartialFloor()) {
            return Double.NaN;
        }
        if (v.at(x, y - 1, z) == BlockType.TALL && here.height() < 0.5 && !here.holds()) {
            return Double.NaN; // nothing stands on a fence
        }
        double floor = v.collisionIn(x, y, z);
        double e;
        if (floor > 0) {
            e = y + floor;
        } else if (v.at(x, y - 1, z).supports()) {
            e = y;
        } else if (here == BlockType.WATER || here == BlockType.CLIMBABLE) {
            e = y; // held up by the water or the ladder
        } else {
            return Double.NaN;
        }
        return v.clearBand(x, z, e, e + v.profile().height()) ? e : Double.NaN;
    }

    @Test
    void sameMovesAsTheDirectVersionOnRandomWorlds() {
        Random rng = new Random(77);
        EntityProfile[] profiles = {
            EntityProfile.PLAYER, EntityProfile.ZOMBIE, EntityProfile.PLAYER.withJumpHeight(0),
            EntityProfile.PLAYER.withMaxDrop(8), EntityProfile.PLAYER.withHeight(0.9),
            EntityProfile.PLAYER.withOpensDoors(false), EntityProfile.PLAYER.withDiagonalLeaps(false),
        };
        int moves = 0;
        long swims = 0;
        long climbs = 0;
        for (int trial = 0; trial < 600; trial++) {
            ArrayBlockView world = TestWorlds.random(rng, 2 + rng.nextInt(9), 2 + rng.nextInt(9));
            MoveValidator v = new MoveValidator(world, profiles[trial % profiles.length]);
            boolean diagonal = trial % 2 == 0;
            for (int x = -1; x <= world.sizeX(); x++) {
                for (int z = -1; z <= world.sizeZ(); z++) {
                    for (int y = -1; y <= world.sizeY(); y++) {
                        TreeMap<Long, MoveType> fast = new TreeMap<>();
                        v.moves(x, y, z, diagonal, fast::put);
                        TreeMap<Long, MoveType> slow = reference(v, x, y, z, diagonal);
                        assertEquals(slow, fast, "moves from (" + x + ", " + y + ", " + z + "), trial " + trial);
                        assertEquals(Double.isNaN(elevation(v, x, y, z)) ? Double.NaN : elevation(v, x, y, z),
                                v.elevation(x, y, z), "elevation at (" + x + ", " + y + ", " + z + ")");
                        moves += fast.size();
                        swims += fast.values().stream().filter(t -> t == MoveType.SWIM).count();
                        climbs += fast.values().stream().filter(t -> t == MoveType.CLIMB).count();
                    }
                }
            }
        }
        assertTrue(moves > 40_000, "only " + moves + " moves compared");
        assertTrue(swims > 2_000 && climbs > 1_000, swims + " swims and " + climbs + " climbs compared");
    }
}
