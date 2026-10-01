package astar.client.draw;

import java.util.Locale;

/**
 * The overlay's colours: one matte hue for everything (the route line, the key-node blocks and
 * the lead box), in shades of it. Flat, a little muted, so it reads against the world without
 * glowing.
 */
public enum OverlayColour {
    RED(0xD9534F),
    ORANGE(0xE8883A),
    YELLOW(0xE6C84A),
    GREEN(0x5DB85C),
    CYAN(0x4FB8C9),
    BLUE(0x4A7FD4),
    PURPLE(0x8E63C9),
    PINK(0xD96FA8),
    WHITE(0xE6E6E6);

    /** The hue itself, opaque: the route line. */
    public final int line;
    /** A darker shade: edges. */
    public final int edge;
    /** The hue, see-through: the key-node blocks' faces. */
    public final int fill;
    /** A lighter shade: the lead box. */
    public final int lead;

    OverlayColour(int rgb) {
        line = 0xFF000000 | rgb;
        edge = 0xFF000000 | mix(rgb, 0x000000, 0.45);
        fill = 0x38000000 | rgb;
        lead = 0xFF000000 | mix(rgb, 0xFFFFFF, 0.35);
    }

    /** Its name as typed in {@code /goto show}. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The colour with this name, or null. */
    public static OverlayColour named(String name) {
        for (OverlayColour c : values()) {
            if (c.id().equals(name.toLowerCase(Locale.ROOT))) {
                return c;
            }
        }
        return null;
    }

    private static int mix(int a, int b, double t) {
        int r = (int) Math.round(((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) Math.round(((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) Math.round((a & 255) * (1 - t) + (b & 255) * t);
        return r << 16 | g << 8 | bl;
    }
}
