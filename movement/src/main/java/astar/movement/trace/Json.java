package astar.movement.trace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Just enough JSON for trace files, with no library.
 *
 * <p>Numbers are written with {@link Double#toString} or {@link Float#toString} and parsed back
 * from their original text ({@link Num}), so every value reads back bit for bit.
 */
final class Json {

    private Json() {}

    /** A number as it was written; read it as the type it was written as. */
    record Num(String text) {
        double asDouble() {
            return Double.parseDouble(text);
        }

        float asFloat() {
            return Float.parseFloat(text);
        }

        long asLong() {
            return Long.parseLong(text);
        }

        int asInt() {
            return Integer.parseInt(text);
        }
    }

    // ---- writing ----

    static void string(StringBuilder b, String s) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        b.append('"');
    }

    /** Writes a double exactly, or {@code null} for NaN and infinities, which JSON can't hold. */
    static void number(StringBuilder b, double d) {
        b.append(Double.isFinite(d) ? Double.toString(d) : "null");
    }

    static void number(StringBuilder b, float f) {
        b.append(Float.isFinite(f) ? Float.toString(f) : "null");
    }

    // ---- reading ----

    /** Parses one JSON value: maps, lists, strings, {@link Num}, booleans or null. */
    static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipSpace();
        Object v = p.value();
        p.skipSpace();
        if (p.i != text.length()) {
            throw p.error("unexpected text after the value");
        }
        return v;
    }

    private static final class Parser {
        final String s;
        int i;

        Parser(String s) {
            this.s = s;
        }

        Object value() {
            if (i >= s.length()) {
                throw error("unexpected end");
            }
            char c = s.charAt(i);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            skipSpace();
            if (peek() == '}') {
                i++;
                return m;
            }
            while (true) {
                skipSpace();
                if (peek() != '"') {
                    throw error("expected a key");
                }
                String key = string();
                skipSpace();
                expect(':');
                skipSpace();
                m.put(key, value());
                skipSpace();
                char c = next();
                if (c == '}') {
                    return m;
                }
                if (c != ',') {
                    throw error("expected , or }");
                }
            }
        }

        List<Object> array() {
            List<Object> list = new ArrayList<>();
            i++;
            skipSpace();
            if (peek() == ']') {
                i++;
                return list;
            }
            while (true) {
                skipSpace();
                list.add(value());
                skipSpace();
                char c = next();
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw error("expected , or ]");
                }
            }
        }

        String string() {
            expect('"');
            StringBuilder b = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') {
                    return b.toString();
                }
                if (c != '\\') {
                    b.append(c);
                    continue;
                }
                char e = next();
                switch (e) {
                    case '"', '\\', '/' -> b.append(e);
                    case 'n' -> b.append('\n');
                    case 'r' -> b.append('\r');
                    case 't' -> b.append('\t');
                    case 'b' -> b.append('\b');
                    case 'f' -> b.append('\f');
                    case 'u' -> {
                        if (i + 4 > s.length()) {
                            throw error("bad \\u escape");
                        }
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> throw error("bad escape \\" + e);
                }
            }
        }

        Num number() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
                i++;
            }
            if (start == i) {
                throw error("unexpected character '" + s.charAt(i) + "'");
            }
            String text = s.substring(start, i);
            try {
                Double.parseDouble(text);
            } catch (NumberFormatException e) {
                throw error("bad number " + text);
            }
            return new Num(text);
        }

        Object literal(String word, Object v) {
            if (!s.startsWith(word, i)) {
                throw error("unexpected text");
            }
            i += word.length();
            return v;
        }

        void skipSpace() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        char peek() {
            if (i >= s.length()) {
                throw error("unexpected end");
            }
            return s.charAt(i);
        }

        char next() {
            char c = peek();
            i++;
            return c;
        }

        void expect(char c) {
            if (next() != c) {
                i--;
                throw error("expected " + c);
            }
        }

        IllegalArgumentException error(String what) {
            return new IllegalArgumentException(what + " at character " + i);
        }
    }
}
