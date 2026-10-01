package astar.mcworld;

import java.io.DataInput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal reader for Minecraft's NBT format. Tags become plain Java values:
 *
 * <ul>
 *   <li>byte, short, int, long, float, double: {@link Byte} ... {@link Double}
 *   <li>byte array, int array, long array: {@code byte[]}, {@code int[]}, {@code long[]}
 *   <li>string: {@link String}; list: {@link List}; compound: {@code Map<String, Object>}
 * </ul>
 */
public final class Nbt {
    private Nbt() {}

    /** Reads a root tag (type, name, payload) and returns its payload. */
    public static Map<String, Object> readRoot(DataInput in) throws IOException {
        int type = in.readUnsignedByte();
        if (type != 10) {
            throw new IOException("Root tag must be a compound, got type " + type);
        }
        in.readUTF(); // root name, usually empty
        return compound(in);
    }

    private static Map<String, Object> compound(DataInput in) throws IOException {
        Map<String, Object> map = new LinkedHashMap<>();
        while (true) {
            int type = in.readUnsignedByte();
            if (type == 0) {
                return map;
            }
            String name = in.readUTF();
            map.put(name, payload(in, type));
        }
    }

    private static Object payload(DataInput in, int type) throws IOException {
        return switch (type) {
            case 1 -> in.readByte();
            case 2 -> in.readShort();
            case 3 -> in.readInt();
            case 4 -> in.readLong();
            case 5 -> in.readFloat();
            case 6 -> in.readDouble();
            case 7 -> {
                byte[] a = new byte[length(in)];
                in.readFully(a);
                yield a;
            }
            case 8 -> in.readUTF(); // NBT strings are Java's modified UTF-8
            case 9 -> {
                int elementType = in.readUnsignedByte();
                int n = in.readInt();
                // The length comes from the file: don't trust it for the up-front allocation.
                List<Object> list = new ArrayList<>(Math.max(0, Math.min(n, 1024)));
                for (int i = 0; i < n; i++) {
                    list.add(payload(in, elementType));
                }
                yield list;
            }
            case 10 -> compound(in);
            case 11 -> {
                int[] a = new int[length(in)];
                for (int i = 0; i < a.length; i++) {
                    a[i] = in.readInt();
                }
                yield a;
            }
            case 12 -> {
                long[] a = new long[length(in)];
                for (int i = 0; i < a.length; i++) {
                    a[i] = in.readLong();
                }
                yield a;
            }
            default -> throw new IOException("Unknown NBT tag type " + type);
        };
    }

    private static int length(DataInput in) throws IOException {
        int n = in.readInt();
        if (n < 0) {
            throw new IOException("Negative NBT array length " + n);
        }
        return n;
    }

    // ---- Typed access -----------------------------------------------------------------

    @SuppressWarnings("unchecked")
    public static Map<String, Object> compound(Map<String, Object> tag, String key) {
        Object v = tag.get(key);
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Map<String, Object> tag, String key) {
        Object v = tag.get(key);
        return v instanceof List<?> l ? (List<Object>) l : List.of();
    }

    public static int intValue(Map<String, Object> tag, String key, int fallback) {
        Object v = tag.get(key);
        return v instanceof Number n ? n.intValue() : fallback;
    }

    public static String string(Map<String, Object> tag, String key) {
        Object v = tag.get(key);
        return v instanceof String s ? s : null;
    }
}
