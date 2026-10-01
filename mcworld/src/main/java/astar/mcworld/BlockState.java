package astar.mcworld;

import java.util.Map;

/**
 * A block as stored in a chunk palette: its id and its properties.
 *
 * @param name for example {@code minecraft:oak_fence}
 * @param properties for example {@code {open=false, waterlogged=false}}
 */
public record BlockState(String name, Map<String, String> properties) {

    public static final BlockState AIR = new BlockState("minecraft:air", Map.of());

    public String property(String key) {
        return properties.get(key);
    }

    /** The id without the {@code minecraft:} namespace; modded ids keep theirs. */
    public String path() {
        return name.startsWith("minecraft:") ? name.substring("minecraft:".length()) : name;
    }

    public boolean isAir() {
        return name.equals("minecraft:air") || name.equals("minecraft:cave_air")
                || name.equals("minecraft:void_air") || name.equals("air")
                || name.equals("cave_air") || name.equals("void_air");
    }
}
