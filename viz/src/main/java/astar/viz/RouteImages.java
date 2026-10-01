package astar.viz;

import astar.core.BlockPoint;
import astar.core.MoveType;
import astar.core.PathStep;
import astar.core.SearchResult;
import astar.pathing.ArrayBlockView;
import astar.pathing.PathAnalysis;
import astar.pathing.Waypoint;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pictures of one route on a large map, where one panel per level would be far too big:
 *
 * <ul>
 *   <li>{@link #overview}: the whole map from above, shaded by the height of each column's
 *       highest walkable floor, with the route coloured by height;
 *   <li>{@link #profile}: the route's height against distance travelled, with jumps, descents and drops;
 *   <li>{@link #levels}: close-ups of the levels where the route spends the most steps.
 * </ul>
 */
public final class RouteImages {
    private static final Color BG = new Color(0xF7F7F5);
    private static final Font FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
    /** How tall {@link #drawLegend} is. */
    static final int LEGEND_H = 52;

    private RouteImages() {}

    /** Heights, low to high: dark blue, teal, green, yellow (a viridis-like ramp). */
    static Color heightColour(double t) {
        return HeightMap.heightColour(t);
    }

    /** The whole map from above, with the route drawn over it, coloured by height. */
    public static BufferedImage overview(ArrayBlockView world, List<PathStep> path,
            List<Waypoint> waypoints, BlockPoint origin, String title, int maxPixels) {
        return overview(world, path, waypoints, origin, title, maxPixels, HeightMap.Style.GREY);
    }

    /**
     * As above, with the terrain in either style: grey, with the route coloured by height, or
     * coloured by height itself, with the route in white.
     */
    public static BufferedImage overview(ArrayBlockView world, List<PathStep> path,
            List<Waypoint> waypoints, BlockPoint origin, String title, int maxPixels,
            HeightMap.Style style) {
        int sx = world.sizeX();
        int sz = world.sizeZ();
        int c = Math.max(1, Math.min(6, maxPixels / Math.max(sx, sz)));
        int top = 28;
        int legendH = 64;
        int w = Math.max(sx * c + 16, 520);
        BufferedImage img = new BufferedImage(w, top + sz * c + legendH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(BG);
            g.fillRect(0, 0, img.getWidth(), img.getHeight());
            text(g);
            g.setColor(Palette.TEXT);
            g.drawString(title, 8, 18);

            HeightMap heights = heights(world);
            int ox = 8;
            g.drawImage(heights.image(style), ox, top, sx * c, sz * c, null);

            int[] yr = style == HeightMap.Style.GREY ? yRange(path)
                    : new int[] {heights.minHeight(), heights.maxHeight()};
            drawRoute(g, path, waypoints, null, null, ox, top, c, yr, style, true);

            int ly = top + sz * c + 14;
            drawLegend(g, 8, ly, yr, origin, style, true);
        } finally {
            g.dispose();
        }
        return img;
    }

    // The last world's height map: one route writes several images of the same world, and
    // --compare writes them twice, so it is worked out once rather than for each.
    private static ArrayBlockView heightsWorld;
    private static int heightsVersion;
    private static HeightMap lastHeights;

    static synchronized HeightMap heights(ArrayBlockView world) {
        if (world != heightsWorld || world.version() != heightsVersion) {
            lastHeights = HeightMap.of(world);
            heightsWorld = world;
            heightsVersion = world.version();
        }
        return lastHeights;
    }

    /**
     * Draws a route seen from above: the line (coloured by height against {@code yRange} in the
     * grey style, white in the height style), jump and drop markers, then start and goal
     * ({@code null}: the ends of the path). A block is {@code c} pixels wide and block (0, 0)
     * starts at ({@code ox}, {@code oy}).
     *
     * <p>With {@code nodes}, every path node is also drawn as a small dark dot, and the key
     * nodes ({@link PathAnalysis#keyNodes}: where the direction or the kind of move changes) as
     * larger red rings.
     */
    static void drawRoute(Graphics2D g, List<PathStep> path, List<Waypoint> waypoints,
            BlockPoint start, BlockPoint goal, double ox, double oy, double c, int[] yRange,
            HeightMap.Style style, boolean nodes) {
        Object aa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        float width = (float) Math.max(2, Math.min(6, c * 0.9));
        boolean grey = style == HeightMap.Style.GREY;
        // An outline so the line reads over any terrain.
        g.setStroke(new BasicStroke(width + 2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(grey ? new Color(255, 255, 255, 200) : new Color(20, 20, 24, 220));
        for (int i = 0; i + 1 < path.size(); i++) {
            g.draw(segment(path.get(i).pos(), path.get(i + 1).pos(), ox, oy, c));
        }
        g.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(Color.WHITE);
        for (int i = 0; i + 1 < path.size(); i++) {
            BlockPoint a = path.get(i).pos();
            if (grey) {
                g.setColor(heightColour((a.y() - yRange[0])
                        / (double) Math.max(1, yRange[1] - yRange[0])));
            }
            g.draw(segment(a, path.get(i + 1).pos(), ox, oy, c));
        }
        if (nodes) {
            drawNodes(g, path, ox, oy, c);
        }
        for (int i = 0; i < waypoints.size(); i++) {
            Waypoint wp = waypoints.get(i);
            if (wp.kind() == Waypoint.Kind.JUMP_UP || wp.kind() == Waypoint.Kind.DROP) {
                boolean descent = wp.kind() == Waypoint.Kind.DROP && i > 0
                        && waypoints.get(i - 1).pos().y() - wp.pos().y() == 1;
                marker(g, wp.pos(), ox, oy, c, wp.kind() == Waypoint.Kind.JUMP_UP ? Palette.JUMP
                        : descent ? Palette.DESCENT : Palette.DROP,
                        Math.max(2.5, Math.min(6, c * 0.8)));
            }
        }
        if (start == null && !path.isEmpty()) {
            start = path.get(0).pos();
        }
        if (goal == null && !path.isEmpty()) {
            goal = path.get(path.size() - 1).pos();
        }
        double r = Math.max(5, Math.min(13.2, c * 2.2));
        if (start != null) {
            marker(g, start, ox, oy, c, Palette.START, r);
        }
        if (goal != null) {
            marker(g, goal, ox, oy, c, Palette.GOAL, r);
        }
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa);
    }

    /** Radius of a path node's dot at {@code c} pixels per block. */
    static double nodeRadius(double c) {
        return Math.max(1.1, Math.min(4, c * 0.2));
    }

    /** Radius of a key node's ring at {@code c} pixels per block. */
    static double keyRadius(double c) {
        return Math.max(2.5, Math.min(12, c * 0.55));
    }

    /**
     * Every path node as a small dark dot, then the key nodes as larger red rings. Below 4
     * pixels per block the dots would hide the line, so only the key nodes are drawn.
     */
    static void drawNodes(Graphics2D g, List<PathStep> path, double ox, double oy, double c) {
        double r = nodeRadius(c);
        g.setColor(Palette.PATH_NODE);
        for (PathStep s : c < 4 ? List.<PathStep>of() : path) {
            BlockPoint p = s.pos();
            double cx = ox + (p.x() + 0.5) * c;
            double cy = oy + (p.z() + 0.5) * c;
            g.fill(new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r));
        }
        double kr = keyRadius(c);
        float ring = (float) Math.max(1.5, Math.min(3, c * 0.12));
        for (PathStep s : PathAnalysis.keyNodes(path)) {
            BlockPoint p = s.pos();
            double cx = ox + (p.x() + 0.5) * c;
            double cy = oy + (p.z() + 0.5) * c;
            Ellipse2D e = new Ellipse2D.Double(cx - kr, cy - kr, 2 * kr, 2 * kr);
            g.setStroke(new BasicStroke(ring + 2));
            g.setColor(Color.WHITE);
            g.draw(e);
            g.setStroke(new BasicStroke(ring));
            g.setColor(Palette.KEY_NODE);
            g.draw(e);
        }
    }

    /**
     * The legend under a map from above: the height ramp in in-game y, what the markers mean,
     * and a line on the terrain colours. It is {@link #LEGEND_H} pixels tall.
     */
    static void drawLegend(Graphics2D g, int x, int ly, int[] yr, BlockPoint origin,
            HeightMap.Style style, boolean nodes) {
        text(g);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setColor(Palette.TEXT);
        boolean grey = style == HeightMap.Style.GREY;
        g.drawString(grey ? "Route height (in-game y)" : "Floor height (in-game y)", x, ly + 11);
        int bx = x + 162;
        for (int i = 0; i < 200; i++) {
            g.setColor(heightColour(i / 199.0));
            g.fillRect(bx + i, ly, 1, 14);
        }
        g.setColor(Palette.TEXT);
        g.drawString(String.valueOf(yr[0] + origin.y()), bx - 4, ly + 28);
        FontMetrics fm = g.getFontMetrics();
        String hi = String.valueOf(yr[1] + origin.y());
        g.drawString(hi, bx + 200 - fm.stringWidth(hi) + 4, ly + 28);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int mx = bx + 230;
        mx = legendDot(g, fm, mx, ly + 7, Palette.START, "Start");
        mx = legendDot(g, fm, mx, ly + 7, Palette.GOAL, "Goal");
        mx = legendDot(g, fm, mx, ly + 7, Palette.JUMP, "Jump up");
        mx = legendDot(g, fm, mx, ly + 7, Palette.DESCENT, "Descent (1 down)");
        mx = legendDot(g, fm, mx, ly + 7, Palette.DROP, "Drop (2+ down)");
        if (nodes) {
            g.setColor(Palette.PATH_NODE);
            g.fill(new Ellipse2D.Double(mx + 2, ly + 4, 6, 6));
            g.setColor(Palette.TEXT);
            g.drawString("Path node", mx + 14, ly + 11);
            mx += 14 + fm.stringWidth("Path node") + 16;
            g.setStroke(new BasicStroke(2));
            g.setColor(Palette.KEY_NODE);
            g.draw(new Ellipse2D.Double(mx, ly + 2, 10, 10));
            g.setColor(Palette.TEXT);
            g.drawString("Key node", mx + 14, ly + 11);
        }
        g.setColor(Palette.TEXT);
        g.drawString(grey
                ? "Grey: the highest walkable floor in each column (lighter is higher)."
                        + " Dark: solid rock. Red: lava. Blue: water. White: empty."
                : "Colour: the highest walkable floor in each column. The route is white."
                        + " Dark: solid rock. Red: lava. Blue: water. White: empty.", x, ly + 46);
    }

    /** Etherwarps on the teleport map. */
    static final Color ETHERWARP = new Color(0xD63AF9);
    /** Instant Transmissions on the teleport map. */
    static final Color TRANSMIT = new Color(0xFF8A1E);

    /**
     * A route with teleports on the height map, from above, cropped to the route: walking in
     * white, each etherwarp as a magenta arrow from where it's cast to where it lands, and each
     * Instant Transmission as an orange arrow through the block every cast puts the player in
     * (a dot for each; an air chain has several) and on down to where they fall to, numbered in
     * order. {@code hopCells.get(i)} is where step {@code i} goes if it is a teleport: the
     * blocks each cast ends in, then the landing.
     */
    public static BufferedImage teleports(ArrayBlockView world, List<PathStep> path,
            Map<Integer, List<BlockPoint>> hopCells, BlockPoint origin, String title,
            int maxPixels) {
        int x0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE;
        int x1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        for (PathStep st : path) {
            x0 = Math.min(x0, st.pos().x());
            x1 = Math.max(x1, st.pos().x());
            z0 = Math.min(z0, st.pos().z());
            z1 = Math.max(z1, st.pos().z());
        }
        for (List<BlockPoint> cells : hopCells.values()) {
            for (BlockPoint b : cells) {
                x0 = Math.min(x0, b.x());
                x1 = Math.max(x1, b.x());
                z0 = Math.min(z0, b.z());
                z1 = Math.max(z1, b.z());
            }
        }
        int margin = 12;
        x0 = Math.max(0, x0 - margin);
        z0 = Math.max(0, z0 - margin);
        x1 = Math.min(world.sizeX() - 1, x1 + margin);
        z1 = Math.min(world.sizeZ() - 1, z1 + margin);
        int sx = x1 - x0 + 1, sz = z1 - z0 + 1;
        double c = Math.max(1, Math.min(8, maxPixels / (double) Math.max(sx, sz)));
        int top = 28;
        int w = Math.max((int) Math.ceil(sx * c) + 16, 900);
        BufferedImage img = new BufferedImage(w, top + (int) Math.ceil(sz * c) + 80,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(BG);
            g.fillRect(0, 0, img.getWidth(), img.getHeight());
            text(g);
            g.setColor(Palette.TEXT);
            g.drawString(title, 8, 18);
            HeightMap heights = heights(world);
            BufferedImage terrain = heights.image(HeightMap.Style.HEIGHT);
            int ox0 = 8;
            g.drawImage(terrain.getSubimage(x0, z0, sx, sz), ox0, top, (int) Math.round(sx * c),
                    (int) Math.round(sz * c), null);
            // Block (0, 0) of the map is here, so the helpers can draw in map coordinates.
            double ox = ox0 - x0 * c, oy = top - z0 * c;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            float width = (float) Math.max(2, Math.min(5, c * 0.6));
            BasicStroke outline = new BasicStroke(width + 2.5f, BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND);
            BasicStroke line = new BasicStroke(width, BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND);
            // Walking first, under the teleports.
            for (int i = 1; i < path.size(); i++) {
                if (hopCells.containsKey(i)) {
                    continue;
                }
                Line2D seg = segment(path.get(i - 1).pos(), path.get(i).pos(), ox, oy, c);
                g.setStroke(outline);
                g.setColor(new Color(20, 20, 24, 220));
                g.draw(seg);
                g.setStroke(line);
                g.setColor(Color.WHITE);
                g.draw(seg);
            }
            int number = 0;
            for (int i = 1; i < path.size(); i++) {
                List<BlockPoint> cells = hopCells.get(i);
                if (cells == null) {
                    continue;
                }
                number++;
                boolean ether = path.get(i).via() == MoveType.WARP;
                Color colour = ether ? ETHERWARP : TRANSMIT;
                List<BlockPoint> pts = new ArrayList<>();
                pts.add(path.get(i - 1).pos());
                pts.addAll(cells);
                if (!pts.get(pts.size() - 1).equals(path.get(i).pos())) {
                    pts.add(path.get(i).pos());
                }
                for (int k = 1; k < pts.size(); k++) {
                    Line2D seg = segment(pts.get(k - 1), pts.get(k), ox, oy, c);
                    g.setStroke(outline);
                    g.setColor(new Color(20, 20, 24, 220));
                    g.draw(seg);
                    g.setStroke(line);
                    g.setColor(colour);
                    g.draw(seg);
                }
                arrowHead(g, pts.get(pts.size() - 2), pts.get(pts.size() - 1), ox, oy, c, colour,
                        width);
                // A dot where each cast of an Instant Transmission puts the player.
                if (!ether) {
                    for (BlockPoint b : cells) {
                        marker(g, b, ox, oy, c, colour, Math.max(2.5, Math.min(5, c * 0.5)));
                    }
                }
                BlockPoint a = pts.get(0);
                String label = String.valueOf(number);
                FontMetrics fm = g.getFontMetrics();
                double lx = ox + (a.x() + 0.5) * c + 6, ly = oy + (a.z() + 0.5) * c - 6;
                g.setColor(new Color(20, 20, 24, 200));
                g.fillRoundRect((int) lx - 2, (int) ly - fm.getAscent(), fm.stringWidth(label) + 4,
                        fm.getAscent() + 2, 4, 4);
                g.setColor(Color.WHITE);
                g.drawString(label, (float) lx, (float) ly);
            }
            double r = Math.max(5, Math.min(10, c * 1.6));
            marker(g, path.get(0).pos(), ox, oy, c, Palette.START, r);
            marker(g, path.get(path.size() - 1).pos(), ox, oy, c, Palette.GOAL, r);

            // Legend.
            int ly = top + (int) Math.ceil(sz * c) + 16;
            g.setColor(Palette.TEXT);
            g.drawString("Floor height (in-game y)", 8, ly + 11);
            int bx = 170;
            for (int i = 0; i < 160; i++) {
                g.setColor(heightColour(i / 159.0));
                g.fillRect(bx + i, ly, 1, 14);
            }
            FontMetrics fm = g.getFontMetrics();
            g.setColor(Palette.TEXT);
            g.drawString(String.valueOf(heights.minHeight() + origin.y()), bx - 4, ly + 28);
            String hi = String.valueOf(heights.maxHeight() + origin.y());
            g.drawString(hi, bx + 160 - fm.stringWidth(hi) + 4, ly + 28);
            int mx = bx + 190;
            mx = legendDot(g, fm, mx, ly + 7, Palette.START, "Start");
            mx = legendDot(g, fm, mx, ly + 7, Palette.GOAL, "Goal");
            mx = legendDot(g, fm, mx, ly + 7, Color.WHITE, "Walking");
            mx = legendDot(g, fm, mx, ly + 7, ETHERWARP, "Etherwarp");
            legendDot(g, fm, mx, ly + 7, TRANSMIT, "Instant Transmission (dot = each cast)");
            g.setColor(Palette.TEXT);
            g.drawString("Numbers: the order the teleports are cast in. Colour under the route:"
                    + " the highest floor in each column.", 8, ly + 50);
        } finally {
            g.dispose();
        }
        return img;
    }

    private static void arrowHead(Graphics2D g, BlockPoint from, BlockPoint to, double ox,
            double oy, double c, Color colour, float width) {
        double ax = ox + (from.x() + 0.5) * c, ay = oy + (from.z() + 0.5) * c;
        double bx = ox + (to.x() + 0.5) * c, by = oy + (to.z() + 0.5) * c;
        double len = Math.hypot(bx - ax, by - ay);
        if (len < 1) {
            return;
        }
        double ux = (bx - ax) / len, uy = (by - ay) / len;
        double size = Math.max(7, width * 3);
        Path2D head = new Path2D.Double();
        head.moveTo(bx, by);
        head.lineTo(bx - ux * size - uy * size * 0.6, by - uy * size + ux * size * 0.6);
        head.lineTo(bx - ux * size + uy * size * 0.6, by - uy * size - ux * size * 0.6);
        head.closePath();
        g.setStroke(new BasicStroke(1.5f));
        g.setColor(new Color(20, 20, 24, 220));
        g.draw(head);
        g.setColor(colour);
        g.fill(head);
    }

    /** Height (in-game y) against horizontal distance along the route. */
    public static BufferedImage profile(List<PathStep> path, BlockPoint origin, String title) {
        int w = 1200;
        int h = 360;
        int left = 56;
        int right = 20;
        int top = 34;
        int bottom = 52;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(BG);
            g.fillRect(0, 0, w, h);
            text(g);
            g.setColor(Palette.TEXT);
            g.drawString(title, 8, 18);
            if (path.size() < 2) {
                return img;
            }
            double[] dist = new double[path.size()];
            for (int i = 1; i < path.size(); i++) {
                BlockPoint a = path.get(i - 1).pos();
                BlockPoint b = path.get(i).pos();
                dist[i] = dist[i - 1] + Math.hypot(b.x() - a.x(), b.z() - a.z());
            }
            int[] yr = yRange(path);
            int ylo = yr[0] - 2;
            int yhi = yr[1] + 2;
            double total = Math.max(1, dist[dist.length - 1]);
            int pw = w - left - right;
            int ph = h - top - bottom;

            // Axes and gridlines.
            g.setColor(Palette.GRID_LINE);
            int yStep = niceStep(yhi - ylo, 6);
            for (int y = (int) Math.ceil(ylo / (double) yStep) * yStep; y <= yhi; y += yStep) {
                int py = top + (int) (ph * (1 - (y - ylo) / (double) (yhi - ylo)));
                g.setColor(Palette.GRID_LINE);
                g.drawLine(left, py, left + pw, py);
                g.setColor(Palette.TEXT);
                g.drawString(String.valueOf(y + origin.y()), 10, py + 4);
            }
            int dStep = niceStep((int) total, 10);
            for (int d = 0; d <= total; d += dStep) {
                int px = left + (int) (pw * d / total);
                g.setColor(Palette.GRID_LINE);
                g.drawLine(px, top, px, top + ph);
                g.setColor(Palette.TEXT);
                g.drawString(String.valueOf(d), px - 8, top + ph + 16);
            }
            g.drawString("blocks travelled (horizontal)", left + pw / 2 - 80, h - 10);
            g.drawString("y", 10, top - 8);

            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            Path2D line = new Path2D.Double();
            for (int i = 0; i < path.size(); i++) {
                double px = left + pw * dist[i] / total;
                double py = top + ph * (1 - (path.get(i).pos().y() - ylo) / (double) (yhi - ylo));
                if (i == 0) {
                    line.moveTo(px, py);
                } else {
                    line.lineTo(px, py);
                }
            }
            g.setColor(Palette.PATH_LINE);
            g.draw(line);
            for (int i = 1; i < path.size(); i++) {
                MoveType via = path.get(i).via();
                if (via == MoveType.JUMP_UP || via == MoveType.DROP) {
                    double px = left + pw * dist[i] / total;
                    double py = top + ph * (1 - (path.get(i).pos().y() - ylo) / (double) (yhi - ylo));
                    g.setColor(via == MoveType.JUMP_UP ? Palette.JUMP
                            : PathAnalysis.isDescent(path, i) ? Palette.DESCENT : Palette.DROP);
                    g.fill(new Ellipse2D.Double(px - 3, py - 3, 6, 6));
                }
            }
            FontMetrics fm = g.getFontMetrics();
            int mx = left + pw - 380;
            mx = legendDot(g, fm, mx, top - 12, Palette.JUMP, "Jump up");
            mx = legendDot(g, fm, mx, top - 12, Palette.DESCENT, "Descent (1 down)");
            legendDot(g, fm, mx, top - 12, Palette.DROP, "Drop (2+ down)");
        } finally {
            g.dispose();
        }
        return img;
    }

    /**
     * Close-ups of the {@code maxLevels} levels where the route takes the most steps, cropped
     * to the route there plus a margin, with the search's lookahead shown.
     */
    public static BufferedImage levels(ArrayBlockView world, SearchResult result,
            List<Waypoint> waypoints, int maxLevels, int maxWidth, String title, BlockPoint origin) {
        List<PathStep> path = result.path();
        Map<Integer, Integer> perLevel = new HashMap<>();
        for (PathStep s : path) {
            perLevel.merge(s.pos().y(), 1, Integer::sum);
        }
        List<Integer> chosen = perLevel.entrySet().stream()
                .sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed())
                .limit(maxLevels).map(Map.Entry::getKey).sorted().toList();

        int margin = 6;
        int x0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        for (PathStep s : path) {
            if (chosen.contains(s.pos().y())) {
                x0 = Math.min(x0, s.pos().x());
                z0 = Math.min(z0, s.pos().z());
                x1 = Math.max(x1, s.pos().x());
                z1 = Math.max(z1, s.pos().z());
            }
        }
        x0 = Math.max(0, x0 - margin);
        z0 = Math.max(0, z0 - margin);
        x1 = Math.min(world.sizeX() - 1, x1 + margin);
        z1 = Math.min(world.sizeZ() - 1, z1 + margin);
        int minY = chosen.get(0) - 1;
        int maxY = chosen.get(chosen.size() - 1) + 2;
        ArrayBlockView crop = world.crop(x0, minY, z0, x1, maxY, z1);
        int dx = x0, dy = Math.max(0, minY), dz = z0;

        List<PathStep> shifted = new ArrayList<>();
        for (PathStep s : path) {
            shifted.add(new PathStep(s.pos().offset(-dx, -dy, -dz), s.via()));
        }
        List<Waypoint> shiftedW = new ArrayList<>();
        for (Waypoint wp : waypoints) {
            shiftedW.add(new Waypoint(wp.pos().offset(-dx, -dy, -dz), wp.kind()));
        }
        Set<Integer> levelSet = Set.copyOf(chosen);
        Set<BlockPoint> closed = within(result.closed(), levelSet, crop, dx, dy, dz);
        Set<BlockPoint> open = within(result.open(), levelSet, crop, dx, dy, dz);
        SearchResult cropped = new SearchResult(result.status(), shifted, result.cost(), closed,
                open, result.expanded());
        List<Integer> layers = chosen.stream().map(y -> y - dy).toList();
        FrameState frame = new FrameState(result.expanded(), shifted.get(0).pos(),
                shifted.get(shifted.size() - 1).pos(), Map.of(), open, closed, null, cropped);
        int columns = Math.min(GridRenderer.MAX_COLUMNS, layers.size());
        int cell = Math.max(2, Math.min(16, maxWidth / Math.max(1, columns * crop.sizeX() + columns)));
        return GridRenderer.render(crop, frame, shiftedW, layers, cell, title, dy + origin.y());
    }

    private static Set<BlockPoint> within(Set<BlockPoint> points, Set<Integer> levels,
            ArrayBlockView crop, int dx, int dy, int dz) {
        Set<BlockPoint> out = new LinkedHashSet<>();
        for (BlockPoint p : points) {
            BlockPoint q = p.offset(-dx, -dy, -dz);
            if (levels.contains(p.y()) && crop.inBounds(q.x(), q.y(), q.z())) {
                out.add(q);
            }
        }
        return out;
    }

    private static int[] yRange(List<PathStep> path) {
        int lo = Integer.MAX_VALUE;
        int hi = Integer.MIN_VALUE;
        for (PathStep s : path) {
            lo = Math.min(lo, s.pos().y());
            hi = Math.max(hi, s.pos().y());
        }
        return path.isEmpty() ? new int[] {0, 1} : new int[] {lo, Math.max(hi, lo + 1)};
    }

    private static int niceStep(int range, int ticks) {
        double raw = Math.max(1, range) / (double) ticks;
        double mag = Math.pow(10, Math.floor(Math.log10(raw)));
        for (double m : new double[] {1, 2, 5, 10}) {
            if (m * mag >= raw) {
                return (int) Math.max(1, m * mag);
            }
        }
        return (int) (10 * mag);
    }

    private static Line2D segment(BlockPoint a, BlockPoint b, double ox, double oy, double c) {
        return new Line2D.Double(ox + (a.x() + 0.5) * c, oy + (a.z() + 0.5) * c,
                ox + (b.x() + 0.5) * c, oy + (b.z() + 0.5) * c);
    }

    private static void marker(Graphics2D g, BlockPoint p, double ox, double oy, double c,
            Color colour, double r) {
        double cx = ox + (p.x() + 0.5) * c;
        double cy = oy + (p.z() + 0.5) * c;
        g.setColor(Color.WHITE);
        g.fill(new Ellipse2D.Double(cx - r - 1.5, cy - r - 1.5, 2 * r + 3, 2 * r + 3));
        g.setColor(colour);
        g.fill(new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r));
    }

    private static int legendDot(Graphics2D g, FontMetrics fm, int x, int y, Color colour, String label) {
        g.setColor(colour);
        g.fill(new Ellipse2D.Double(x, y - 5, 10, 10));
        g.setColor(Palette.TEXT);
        g.drawString(label, x + 14, y + 4);
        return x + 14 + fm.stringWidth(label) + 16;
    }

    private static void text(Graphics2D g) {
        g.setFont(FONT);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }
}
