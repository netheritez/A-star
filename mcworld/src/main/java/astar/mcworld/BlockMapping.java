package astar.mcworld;

import astar.pathing.BlockType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Maps Minecraft block states onto the pathfinder's block types, by collision height.
 *
 * <ul>
 *   <li>No collision (flowers, grass, torches, signs, rails, open doors, gates and trapdoors):
 *       {@link BlockType#AIR}.
 *   <li>Closed doors and fence gates that open by hand: {@link BlockType#DOOR} and {@link
 *       BlockType#GATE}. Iron doors need redstone, so a closed one is {@code SOLID}. Closed
 *       trapdoors keep their shape: a 3/16 floor at the bottom of the cell, or solid at the top
 *       (opening them isn't modelled; mobs don't).
 *   <li>Water, and what only grows in it (seagrass, kelp), and any passable block that's
 *       waterlogged: {@link BlockType#WATER}; flowing water (level above 0) is {@link
 *       BlockType#FLOWING_WATER}, and bubble columns {@link BlockType#BUBBLE_UP} or {@link
 *       BlockType#BUBBLE_DOWN} (drag).
 *   <li>Ladders and vines (cave, twisting and weeping vines too), waterlogged or not: {@link
 *       BlockType#CLIMBABLE}. Scaffolding is {@link BlockType#SCAFFOLDING}.
 *   <li>Partial heights, in sixteenths: bottom slabs 8, bottom-half stairs {@link
 *       BlockType#STAIRS}, carpets 1, snow layers 2 per layer above the first, mud and chests
 *       14, farmland and dirt paths 15, beds 9, and so on.
 *   <li>Terrain with a cost: {@link BlockType#SOUL_SAND}, {@link BlockType#HONEY}, {@link
 *       BlockType#COBWEB}, {@link BlockType#MAGMA} and {@link BlockType#BERRY_BUSH} (the
 *       bush's first, sapling-like stage doesn't hurt, so it's air).
 *   <li>Fences and walls: {@link BlockType#TALL} (1.5).
 *   <li>{@link BlockType#CACTUS} and {@link BlockType#POWDER_SNOW}, avoided.
 *   <li>Lava, fire and wither roses: {@link BlockType#HAZARD}.
 *   <li>Everything else, including top slabs, upside-down stairs and modded blocks: {@code
 *       SOLID}.
 * </ul>
 *
 * <p>Heights come from Minecraft's collision shapes, simplified to the full width of the block.
 * Given the real shape, {@link #refine} turns a block too thin or hollow on top to stand on
 * (glass panes, iron bars, cauldrons, hoppers...) into {@link BlockType#THIN}.
 */
public final class BlockMapping {
    private BlockMapping() {}

    private static final Set<String> NO_COLLISION = Set.of(
            "air", "cave_air", "void_air", "light", "structure_void",
            "short_grass", "grass", "tall_grass", "fern", "large_fern", "dead_bush",
            "dandelion", "poppy", "blue_orchid", "allium", "azure_bluet", "oxeye_daisy",
            "cornflower", "lily_of_the_valley", "torchflower", "sunflower", "lilac", "rose_bush",
            "peony", "pitcher_plant", "pink_petals", "wildflowers", "leaf_litter", "closed_eyeblossom",
            "open_eyeblossom", "bush", "firefly_bush", "short_dry_grass", "tall_dry_grass",
            "brown_mushroom", "red_mushroom", "crimson_fungus", "warped_fungus",
            "crimson_roots", "warped_roots", "nether_sprouts", "hanging_roots", "spore_blossom",
            "sugar_cane", "glow_lichen", "sculk_vein",
            "wheat", "carrots", "potatoes", "beetroots", "melon_stem", "pumpkin_stem",
            "attached_melon_stem", "attached_pumpkin_stem", "nether_wart", "torchflower_crop",
            "pitcher_crop", "small_dripleaf",
            "big_dripleaf_stem", "tripwire", "tripwire_hook", "redstone_wire", "lever",
            "nether_portal", "end_gateway", "pale_hanging_moss", "frogspawn");

    private static final Set<String> HAZARD = Set.of(
            "lava", "fire", "soul_fire", "wither_rose", "end_portal");

    /** Blocks with a type of their own. */
    private static final Map<String, BlockType> TERRAIN = Map.of(
            "soul_sand", BlockType.SOUL_SAND, "honey_block", BlockType.HONEY,
            "cobweb", BlockType.COBWEB, "magma_block", BlockType.MAGMA,
            "cactus", BlockType.CACTUS, "powder_snow", BlockType.POWDER_SNOW);

    private static final Set<String> WATER = Set.of(
            "water", "bubble_column", "seagrass", "tall_seagrass", "kelp", "kelp_plant");

    /** Minecraft's climbable tag, less scaffolding (a type of its own). */
    private static final Set<String> CLIMBABLE = Set.of(
            "ladder", "vine", "cave_vines", "cave_vines_plant", "twisting_vines",
            "twisting_vines_plant", "weeping_vines", "weeping_vines_plant");

    /** Blocks shorter than a full block, by id: height in sixteenths. */
    private static final Map<String, Integer> HEIGHTS = Map.ofEntries(
            Map.entry("mud", 14),
            Map.entry("farmland", 15), Map.entry("dirt_path", 15),
            Map.entry("chest", 14), Map.entry("trapped_chest", 14), Map.entry("ender_chest", 14),
            Map.entry("enchanting_table", 12), Map.entry("stonecutter", 9),
            Map.entry("daylight_detector", 6), Map.entry("cake", 8), Map.entry("end_portal_frame", 13),
            Map.entry("repeater", 2), Map.entry("comparator", 2), Map.entry("lily_pad", 1),
            Map.entry("flower_pot", 6), Map.entry("sculk_sensor", 8), Map.entry("calibrated_sculk_sensor", 8),
            Map.entry("sculk_shrieker", 8), Map.entry("conduit", 11), Map.entry("lectern", 14),
            Map.entry("sea_pickle", 6));

    public static BlockType map(BlockState state) {
        String id = state.path();
        boolean open = "true".equals(state.property("open"));
        // An open gate or trapdoor can hold water; with nothing to stand on, that's water.
        BlockType openShape = "true".equals(state.property("waterlogged"))
                ? BlockType.WATER : BlockType.AIR;

        if (HAZARD.contains(id)
                || ((id.equals("campfire") || id.equals("soul_campfire"))
                        && !"false".equals(state.property("lit")))) {
            return BlockType.HAZARD;
        }
        if (id.equals("bubble_column")) {
            // Over magma it drags down; over soul sand it lifts.
            return "true".equals(state.property("drag")) ? BlockType.BUBBLE_DOWN : BlockType.BUBBLE_UP;
        }
        if (id.equals("water") && state.property("level") != null
                && !"0".equals(state.property("level"))) {
            return BlockType.FLOWING_WATER;
        }
        if (WATER.contains(id)) {
            return BlockType.WATER;
        }
        if (id.equals("scaffolding")) {
            return BlockType.SCAFFOLDING;
        }
        // Thin stalks whose shape is offset at random, so the shape table leaves them out.
        if (id.equals("bamboo") || id.equals("pointed_dripstone")) {
            return BlockType.THIN;
        }
        BlockType terrain = TERRAIN.get(id);
        if (terrain != null) {
            return terrain;
        }
        if (id.equals("sweet_berry_bush")) {
            return "0".equals(state.property("age")) ? BlockType.AIR : BlockType.BERRY_BUSH;
        }
        if (CLIMBABLE.contains(id)) {
            return BlockType.CLIMBABLE;
        }
        if (id.equals("campfire") || id.equals("soul_campfire")) {
            return BlockType.partial(7); // unlit
        }
        if (id.endsWith("_fence_gate")) {
            return open ? openShape : BlockType.GATE;
        }
        if (id.endsWith("_door")) {
            return open ? BlockType.AIR : id.equals("iron_door") ? BlockType.SOLID : BlockType.DOOR;
        }
        if (id.endsWith("_trapdoor")) {
            if (open) {
                return openShape;
            }
            return "top".equals(state.property("half")) ? BlockType.SOLID : BlockType.partial(3);
        }
        if (isTall(state)) {
            return BlockType.TALL;
        }
        if (id.endsWith("_slab")) {
            return "bottom".equals(state.property("type")) ? BlockType.partial(8) : BlockType.SOLID;
        }
        if (id.endsWith("_stairs")) {
            return "top".equals(state.property("half")) ? BlockType.SOLID : BlockType.STAIRS;
        }
        if (id.equals("snow")) {
            int layers = parseInt(state.property("layers"), 1);
            return BlockType.partial(2 * (layers - 1)); // 1 layer: no collision
        }
        if (id.endsWith("_carpet")) {
            return BlockType.partial(1);
        }
        if (id.endsWith("_bed")) {
            return BlockType.partial(9);
        }
        if (id.startsWith("potted_")) {
            return BlockType.partial(6); // a flower pot
        }
        if (id.endsWith("candle_cake")) {
            return BlockType.partial(8); // a cake
        }
        if (id.equals("candle") || id.endsWith("_candle")) {
            return BlockType.partial(6); // up to four candles; they can be stood on
        }
        if (id.equals("piston_head")) {
            return BlockType.SOLID; // not a mob head
        }
        if (id.endsWith("_head") || id.endsWith("_skull")) {
            return id.contains("_wall_") ? BlockType.SOLID : BlockType.partial(8);
        }
        Integer h = HEIGHTS.get(id);
        if (h != null) {
            return BlockType.partial(h);
        }
        if (NO_COLLISION.contains(id) || isThinDecoration(id)) {
            // A passable block standing in water is still water.
            return "true".equals(state.property("waterlogged")) ? BlockType.WATER : BlockType.AIR;
        }
        return BlockType.SOLID;
    }

    /**
     * {@code type} checked against the block's real collision shape: a full or partial block
     * more than a step high whose top wouldn't hold a body up wherever it stands on the cell
     * (glass panes, iron bars, cauldrons, hoppers, lanterns, chains...) is {@link
     * BlockType#THIN}, so no route walks across it. Other kinds, and blocks with no real shape
     * given (full cubes), stay as they are.
     *
     * @param boxes the collision boxes, each {minX, minY, minZ, maxX, maxY, maxZ} within the
     *     block (0 to 1, up to 1.5)
     */
    public static BlockType refine(BlockType type, List<double[]> boxes) {
        boolean floor = type == BlockType.SOLID
                || (type.isPartialFloor() && type != BlockType.STAIRS && type.height() > STEP);
        if (!floor || boxes == null || boxes.isEmpty()) {
            return type;
        }
        return holdsBody(boxes) ? type : BlockType.THIN;
    }

    /** A step: what a body walks up onto without jumping (as with the step height). */
    private static final double STEP = 0.6;

    /** Half a player's width, less a little: how far from the top a body may stand and not fall. */
    private static final double REACH = 0.25;

    /**
     * Whether the top of the shape holds up a body standing anywhere on the cell: every point
     * of the cell is within {@link #REACH} (across x and z) of the top face.
     */
    static boolean holdsBody(List<double[]> boxes) {
        double top = 0;
        for (double[] b : boxes) {
            top = Math.max(top, b[4]);
        }
        List<double[]> face = new ArrayList<>();
        for (double[] b : boxes) {
            if (b[4] >= top - 1e-6) {
                face.add(b);
            }
        }
        int n = 32;
        for (int i = 0; i <= n; i++) {
            for (int k = 0; k <= n; k++) {
                double px = (double) i / n;
                double pz = (double) k / n;
                double best = Double.MAX_VALUE;
                for (double[] b : face) {
                    double dx = Math.max(0, Math.max(b[0] - px, px - b[3]));
                    double dz = Math.max(0, Math.max(b[2] - pz, pz - b[5]));
                    best = Math.min(best, Math.max(dx, dz));
                }
                if (best > REACH + 1e-9) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Fences, walls and closed fence gates: 1.5 blocks tall, so mobs can't jump over them. */
    public static boolean isTall(BlockState state) {
        String id = state.path();
        if (id.endsWith("_fence_gate")) {
            return !"true".equals(state.property("open"));
        }
        return id.endsWith("_fence") || (id.endsWith("_wall") && !isThinDecoration(id));
    }

    /** Families of blocks with no (or negligible) collision, matched by suffix. */
    private static boolean isThinDecoration(String id) {
        return id.endsWith("_sapling") || id.equals("mangrove_propagule") || id.endsWith("_tulip")
                || id.endsWith("_button") || id.endsWith("_pressure_plate")
                || id.endsWith("_sign") || id.endsWith("_wall_sign") || id.endsWith("_hanging_sign")
                || id.endsWith("_banner") || id.endsWith("_wall_banner")
                || id.equals("torch") || id.endsWith("_torch") || id.endsWith("wall_torch")
                || id.equals("rail") || id.endsWith("_rail")
                || id.endsWith("_coral") || id.endsWith("_coral_fan") || id.endsWith("_coral_wall_fan");
    }

    private static int parseInt(String s, int fallback) {
        try {
            return s == null ? fallback : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
