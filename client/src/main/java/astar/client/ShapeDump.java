package astar.client;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPOutputStream;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Writes the collision shape of every block state in the game to a table, so that code
 * outside the game (the map importer) can give each block its real shape.
 *
 * <p>The table is gzipped text. {@code shape <id> <friction> <speed> <jump> <ys> <boxes>}
 * lines come first: the shape's y coordinates ({@code ,}-separated) and its boxes ({@code
 * minX,minY,minZ,maxX,maxY,maxZ} each, {@code ;}-separated, or {@code -} for none). Then one
 * {@code <block>[<properties>] <shape id>} line per block state, with the properties sorted by
 * name. States that are a plain full cube or have no collision, with ordinary friction and
 * speed, are left out: the importer's own mapping already gets those right.
 */
final class ShapeDump {

    private ShapeDump() {}

    /** Writes the table to {@code file}; returns how many states it lists. */
    static int write(Path file) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Map<String, Integer> shapeIds = new HashMap<>();
        List<String> shapes = new ArrayList<>();
        List<String> states = new ArrayList<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                VoxelShape shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE,
                        BlockPos.ZERO, CollisionContext.empty());
                boolean plainFriction = block.getFriction() == 0.6F
                        && block.getSpeedFactor() == 1.0F && block.getJumpFactor() == 1.0F;
                boolean cube = shape.toAabbs().size() == 1 && isCube(shape.toAabbs().get(0));
                if (plainFriction && (shape.isEmpty() || cube)) {
                    continue;
                }
                String line = describe(block, shape);
                Integer id = shapeIds.get(line);
                if (id == null) {
                    id = shapes.size();
                    shapeIds.put(line, id);
                    shapes.add("shape " + id + " " + line);
                }
                states.add(key(state) + " " + id);
            }
        }
        try (Writer out = new OutputStreamWriter(new GZIPOutputStream(
                Files.newOutputStream(file)), StandardCharsets.UTF_8)) {
            out.write("# collision shapes from Minecraft "
                    + SharedConstants.getCurrentVersion().name() + "\n");
            for (String s : shapes) {
                out.write(s + "\n");
            }
            for (String s : states) {
                out.write(s + "\n");
            }
        }
        return states.size();
    }

    /** {@code minecraft:oak_stairs[facing=east,half=bottom,...]}, properties sorted by name. */
    private static String key(BlockState state) {
        Map<String, String> props = new TreeMap<>();
        for (Property<?> p : state.getProperties()) {
            props.put(p.getName(), value(state, p));
        }
        StringBuilder b = new StringBuilder(
                BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        if (!props.isEmpty()) {
            b.append('[');
            props.forEach((k, v) -> b.append(k).append('=').append(v).append(','));
            b.setCharAt(b.length() - 1, ']');
        }
        return b.toString();
    }

    private static <T extends Comparable<T>> String value(BlockState state, Property<T> p) {
        return p.getName(state.getValue(p));
    }

    private static String describe(Block block, VoxelShape shape) {
        StringBuilder b = new StringBuilder();
        b.append(block.getFriction()).append(' ').append(block.getSpeedFactor()).append(' ')
                .append(block.getJumpFactor()).append(' ');
        if (shape.isEmpty()) {
            b.append("- -");
            return b.toString();
        }
        List<String> ys = new ArrayList<>();
        for (double y : shape.getCoords(Direction.Axis.Y)) {
            ys.add(Double.toString(y));
        }
        b.append(String.join(",", ys)).append(' ');
        List<String> boxes = new ArrayList<>();
        for (AABB a : shape.toAabbs()) {
            boxes.add(a.minX + "," + a.minY + "," + a.minZ + "," + a.maxX + "," + a.maxY + ","
                    + a.maxZ);
        }
        b.append(String.join(";", boxes));
        return b.toString();
    }

    private static boolean isCube(AABB a) {
        return a.minX == 0 && a.minY == 0 && a.minZ == 0 && a.maxX == 1 && a.maxY == 1
                && a.maxZ == 1;
    }
}
