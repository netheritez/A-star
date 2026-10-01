package astar.mcworld;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;

/**
 * The real collision shapes of Minecraft's blocks, from a table the client mod wrote out of
 * the game ({@code -Dastar.shapes.dump}, see {@code ShapeDump}). It lists every block state
 * whose shape isn't a plain full cube or nothing, or whose friction, speed or jump factor
 * isn't the usual: stairs, slabs, fences, walls, ice, soul sand and so on.
 *
 * <p>Bamboo and pointed dripstone are left out: their shapes move with a random offset that
 * depends on where the block is, which the table can't give.
 */
public final class BlockShapes {

    /**
     * One block's shape.
     *
     * @param boxes {@code {minX, minY, minZ, maxX, maxY, maxZ}} each, relative to the block's
     *     corner; empty if it has no collision
     * @param pointsY the heights the shape is divided at (the game's {@code getCoords(Y)})
     * @param friction 0.6 for most blocks, 0.98 for ice
     * @param speed 0.4 for soul sand and honey, else 1
     * @param jump 0.5 for honey, else 1
     * @param kind {@code "fence"}, {@code "wall"}, {@code "gate"} or {@code ""}
     */
    public record Shape(List<double[]> boxes, List<Double> pointsY, float friction, float speed,
            float jump, String kind) {

        public Shape {
            boxes = List.copyOf(boxes);
            pointsY = List.copyOf(pointsY);
        }
    }

    private static final String RESOURCE = "/astar/mcworld/block-shapes.txt.gz";
    private static final BlockShapes BUILT_IN = load();

    private final Map<String, Shape> byState;
    private final String version;

    private BlockShapes(Map<String, Shape> byState, String version) {
        this.byState = byState;
        this.version = version;
    }

    /** The table that ships with this code. */
    public static BlockShapes builtIn() {
        return BUILT_IN;
    }

    /** The Minecraft version the table came from, for example {@code 26.3}. */
    public String version() {
        return version;
    }

    /** How many block states the table lists. */
    public int size() {
        return byState.size();
    }

    /** The state's real shape, or null if the importer's own mapping already gets it right. */
    public Shape shape(BlockState state) {
        return byState.get(key(state.name(), state.properties()));
    }

    /** {@code minecraft:oak_stairs[facing=east,half=bottom,...]}, properties sorted by name. */
    static String key(String name, Map<String, String> properties) {
        String id = name.contains(":") ? name : "minecraft:" + name;
        if (properties.isEmpty()) {
            return id;
        }
        StringBuilder b = new StringBuilder(id).append('[');
        new TreeMap<>(properties).forEach((k, v) -> b.append(k).append('=').append(v).append(','));
        b.setCharAt(b.length() - 1, ']');
        return b.toString();
    }

    private static BlockShapes load() {
        try (InputStream in = BlockShapes.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " is missing");
            }
            return read(new BufferedReader(new InputStreamReader(new GZIPInputStream(in),
                    StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static BlockShapes read(BufferedReader in) throws IOException {
        List<String[]> shapes = new ArrayList<>();
        Map<String, Shape> byState = new HashMap<>();
        // Shapes are shared by many states, and the kind depends on the block, not the shape.
        Map<String, Shape> interned = new HashMap<>();
        String version = "";
        for (String line = in.readLine(); line != null; line = in.readLine()) {
            if (line.startsWith("# collision shapes from Minecraft ")) {
                version = line.substring("# collision shapes from Minecraft ".length()).trim();
                continue;
            }
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split(" ");
            if (f[0].equals("shape")) {
                int id = Integer.parseInt(f[1]);
                while (shapes.size() <= id) {
                    shapes.add(null);
                }
                shapes.set(id, f);
                continue;
            }
            String state = f[0];
            String name = state.contains("[") ? state.substring(0, state.indexOf('[')) : state;
            if (name.equals("minecraft:bamboo") || name.equals("minecraft:pointed_dripstone")) {
                continue;
            }
            String kind = kind(name);
            String id = f[1];
            byState.put(state, interned.computeIfAbsent(id + " " + kind,
                    k -> shape(shapes.get(Integer.parseInt(id)), kind)));
        }
        return new BlockShapes(byState, version);
    }

    /** {@code shape <id> <friction> <speed> <jump> <ys> <boxes>}, with {@code - -} for none. */
    private static Shape shape(String[] f, String kind) {
        List<Double> ys = new ArrayList<>();
        List<double[]> boxes = new ArrayList<>();
        if (!f[5].equals("-")) {
            for (String y : f[5].split(",")) {
                ys.add(Double.parseDouble(y));
            }
            for (String box : f[6].split(";")) {
                String[] c = box.split(",");
                double[] b = new double[6];
                for (int i = 0; i < 6; i++) {
                    b[i] = Double.parseDouble(c[i]);
                }
                boxes.add(b);
            }
        }
        return new Shape(boxes, ys, Float.parseFloat(f[2]), Float.parseFloat(f[3]),
                Float.parseFloat(f[4]), kind);
    }

    /** As the client's trace recorder names them (the game's fence and wall tags). */
    private static String kind(String name) {
        if (name.endsWith("_fence_gate")) {
            return "gate";
        }
        if (name.endsWith("_fence")) {
            return "fence";
        }
        return name.endsWith("_wall") ? "wall" : "";
    }
}
