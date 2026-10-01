package astar.movement.trace;

import astar.movement.Keys;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A whole recorded trace, read back from the JSON Lines {@link TraceWriter} writes. */
public record Trace(TraceHeader header, List<BlockRecord> blocks, List<TickRecord> ticks,
        List<TraceEvent> events) {

    /** The format version this code writes and reads. */
    public static final int FORMAT = 1;

    /**
     * Tick flags, one letter each: on the Ground, Horizontal collision, Vertical collision,
     * spRinting, sNeaking, in Water, Climbing.
     */
    public static final String FLAGS = "GHVRNWC";

    public Trace {
        blocks = List.copyOf(blocks);
        ticks = List.copyOf(ticks);
        events = List.copyOf(events);
    }

    public static Trace read(Path file) throws IOException {
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return read(r);
        }
    }

    /**
     * Reads a trace. Blocks recorded more than once (when the player moved and nearby blocks
     * were recorded again) keep their latest record.
     *
     * @throws IllegalArgumentException naming the line, for a malformed trace
     */
    public static Trace read(Reader in) throws IOException {
        BufferedReader r = in instanceof BufferedReader br ? br : new BufferedReader(in);
        TraceHeader header = null;
        Map<Long, BlockRecord> blocks = new LinkedHashMap<>();
        List<TickRecord> ticks = new ArrayList<>();
        List<TraceEvent> events = new ArrayList<>();
        String line;
        int n = 0;
        while ((line = r.readLine()) != null) {
            n++;
            if (line.isBlank()) {
                continue;
            }
            try {
                Map<String, Object> m = map(Json.parse(line), "a line");
                String type = str(m, "type");
                if (header == null && !type.equals("header")) {
                    throw new IllegalArgumentException("a trace starts with its header");
                }
                switch (type) {
                    case "header" -> {
                        if (header != null) {
                            throw new IllegalArgumentException("a second header");
                        }
                        header = header(m);
                    }
                    case "block" -> {
                        BlockRecord b = block(m);
                        blocks.remove(key(b));
                        blocks.put(key(b), b);
                    }
                    case "tick" -> ticks.add(tick(m));
                    case "event" -> events.add(new TraceEvent(num(m, "t").asLong(),
                            str(m, "text")));
                    default -> { } // Newer line types are skipped, so old readers keep working.
                }
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("trace line " + n + ": " + e.getMessage(), e);
            }
        }
        if (header == null) {
            throw new IllegalArgumentException("the trace is empty");
        }
        return new Trace(header, new ArrayList<>(blocks.values()), ticks, events);
    }

    private static long key(BlockRecord b) {
        return ((long) b.x() & 0x3FFFFFF) << 38 | ((long) b.z() & 0x3FFFFFF) << 12
                | ((long) b.y() & 0xFFF);
    }

    private static TraceHeader header(Map<String, Object> m) {
        int format = num(m, "format").asInt();
        if (format != FORMAT) {
            throw new IllegalArgumentException("trace format " + format + ", but this reads "
                    + FORMAT);
        }
        Map<String, Double> attributes = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : map(m.get("attributes"), "attributes").entrySet()) {
            if (e.getValue() instanceof Json.Num v) {
                attributes.put(e.getKey(), v.asDouble());
            }
        }
        return new TraceHeader(format, str(m, "game"), str(m, "scenario"), str(m, "started"),
                attributes);
    }

    private static BlockRecord block(Map<String, Object> m) {
        List<Object> p = list(m, "p", 3);
        List<BlockRecord.Box> boxes = new ArrayList<>();
        for (Object o : list(m, "boxes", -1)) {
            List<?> c = (List<?>) o;
            if (c.size() != 6) {
                throw new IllegalArgumentException("a box has 6 numbers");
            }
            boxes.add(new BlockRecord.Box(d(c.get(0)), d(c.get(1)), d(c.get(2)), d(c.get(3)),
                    d(c.get(4)), d(c.get(5))));
        }
        return new BlockRecord(((Json.Num) p.get(0)).asInt(), ((Json.Num) p.get(1)).asInt(),
                ((Json.Num) p.get(2)).asInt(), str(m, "state"), boxes, num(m, "slip").asFloat(),
                num(m, "vel").asFloat(), num(m, "jump").asFloat(), str(m, "fluid"),
                num(m, "fh").asDouble(), bool(m, "climb"),
                m.get("kind") instanceof String k ? k : "", pointsY(m));
    }

    private static List<Double> pointsY(Map<String, Object> m) {
        if (!m.containsKey("ys")) {
            return List.of();
        }
        List<Double> ys = new ArrayList<>();
        for (Object o : list(m, "ys", -1)) {
            ys.add(d(o));
        }
        return ys;
    }

    private static TickRecord tick(Map<String, Object> m) {
        List<Object> mv = list(m, "mv", 2);
        String flags = str(m, "flags");
        for (char c : flags.toCharArray()) {
            if (FLAGS.indexOf(c) < 0) {
                throw new IllegalArgumentException("unknown flag '" + c + "'");
            }
        }
        return new TickRecord(num(m, "t").asLong(), Keys.parse(str(m, "keys")),
                ((Json.Num) mv.get(0)).asFloat(), ((Json.Num) mv.get(1)).asFloat(),
                num(m, "yaw").asFloat(), num(m, "pitch").asFloat(), vec(m, "p0"), vec(m, "v0"),
                vec(m, "p"), vec(m, "v"), has(flags, 'G'), has(flags, 'H'), has(flags, 'V'),
                has(flags, 'R'), has(flags, 'N'), has(flags, 'W'), has(flags, 'C'),
                num(m, "fall").asDouble());
    }

    private static boolean has(String flags, char c) {
        return flags.indexOf(c) >= 0;
    }

    private static Vec3 vec(Map<String, Object> m, String key) {
        List<Object> v = list(m, key, 3);
        return new Vec3(d(v.get(0)), d(v.get(1)), d(v.get(2)));
    }

    private static double d(Object o) {
        if (!(o instanceof Json.Num n)) {
            throw new IllegalArgumentException("expected a number, got " + o);
        }
        return n.asDouble();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o, String what) {
        if (!(o instanceof Map)) {
            throw new IllegalArgumentException(what + " should be an object");
        }
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Map<String, Object> m, String key, int size) {
        if (!(m.get(key) instanceof List<?> l) || (size >= 0 && l.size() != size)) {
            throw new IllegalArgumentException("\"" + key + "\" should be a list"
                    + (size >= 0 ? " of " + size : ""));
        }
        return (List<Object>) l;
    }

    private static String str(Map<String, Object> m, String key) {
        if (!(m.get(key) instanceof String s)) {
            throw new IllegalArgumentException("\"" + key + "\" should be text");
        }
        return s;
    }

    private static Json.Num num(Map<String, Object> m, String key) {
        if (!(m.get(key) instanceof Json.Num n)) {
            throw new IllegalArgumentException("\"" + key + "\" should be a number");
        }
        return n;
    }

    private static boolean bool(Map<String, Object> m, String key) {
        if (!(m.get(key) instanceof Boolean b)) {
            throw new IllegalArgumentException("\"" + key + "\" should be true or false");
        }
        return b;
    }
}
