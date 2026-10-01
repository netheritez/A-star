package astar.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;

/**
 * A flat test course for recording calibration traces, built with commands in a world with
 * cheats on.
 *
 * <p>The origin is the block the player stands in at the start of the open area; the floor is
 * the layer below it. Coordinates below are relative to the origin, x east and z south.
 * <ul>
 *   <li>Open area, z -24 to -2: flat floor for walking, sprinting, turning.
 *   <li>z 4: a bottom slab at x 8, a raised floor x 9 to 13, then a drop back down.
 *   <li>z 8 to 14: a wall three blocks high at x 8.
 *   <li>z 16: steps one, two and three blocks high from x 6, 9 and 12, then a drop.
 *   <li>z 21 to 23: packed ice from x 4 to 30.
 *   <li>z 27 to 29: soul sand from x 4 to 20.
 *   <li>z 32 to 36: a platform three blocks high from x -4 to 8.
 * </ul>
 */
final class TestCourse {

    private final BlockPos origin;

    TestCourse(BlockPos origin) {
        this.origin = origin;
    }

    BlockPos origin() {
        return origin;
    }

    /** The commands that build the course, in order. */
    List<String> commands() {
        List<String> c = new ArrayList<>();
        c.add("difficulty peaceful");
        c.add("gamemode survival");
        c.add("effect give @s minecraft:saturation infinite 255 true");
        c.add("forceload add " + x(-4) + " " + z(-24) + " " + x(44) + " " + z(40));
        c.add(fill(-4, -1, -24, 44, 7, 40, "air"));
        c.add(fill(-4, -1, -24, 44, -1, 40, "smooth_stone"));
        c.add("setblock " + pos(8, 0, 4) + " smooth_stone_slab[type=bottom]");
        c.add(fill(9, 0, 4, 13, 0, 4, "smooth_stone"));
        c.add(fill(8, 0, 8, 8, 2, 14, "stone"));
        c.add(fill(6, 0, 16, 14, 0, 16, "stone"));
        c.add(fill(9, 1, 16, 14, 1, 16, "stone"));
        c.add(fill(12, 2, 16, 14, 2, 16, "stone"));
        c.add(fill(4, -1, 21, 30, -1, 23, "packed_ice"));
        c.add(fill(4, -1, 27, 20, -1, 29, "soul_sand"));
        c.add(fill(-4, 0, 32, 8, 2, 36, "smooth_stone"));
        return c;
    }

    /** The command that puts the player at a course position, facing {@code yaw}. */
    String teleport(double x, double y, double z, float yaw) {
        return "tp @s " + (origin.getX() + x) + " " + (origin.getY() + y) + " "
                + (origin.getZ() + z) + " " + yaw + " 0";
    }

    private String fill(int x1, int y1, int z1, int x2, int y2, int z2, String block) {
        return "fill " + pos(x1, y1, z1) + " " + pos(x2, y2, z2) + " " + block;
    }

    private String pos(int x, int y, int z) {
        return x(x) + " " + (origin.getY() + y) + " " + z(z);
    }

    private int x(int x) {
        return origin.getX() + x;
    }

    private int z(int z) {
        return origin.getZ() + z;
    }
}
