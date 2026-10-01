package astar.viz;

import astar.core.BlockPoint;
import astar.core.MoveType;
import astar.core.PathStep;
import astar.core.SearchResult;
import astar.pathing.ArrayBlockView;
import astar.pathing.BlockType;
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
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Draws a 3D world and a search as one top-down panel per Y level (x across, z down). Pure
 * Java2D, so it works headless.
 *
 * <p>Within each panel, bottom to top: blocks at that level, closed set, open set, the node being
 * expanded, path cells, path line, key nodes, jump and drop markers, start and goal, grid lines.
 *
 * <p>It draws either a finished {@link SearchResult} or one {@link FrameState} of a replay. While
 * a replay is still running, the path shown is the best path so far to the node being expanded,
 * drawn as a dashed line.
 */
public final class GridRenderer {
    static final int MARGIN = 8;
    static final int LABEL_H = 20;
    static final int PANEL_GAP = 16;
    static final int MAX_COLUMNS = 3;
    private static final int LEGEND_ROW = 22;
    private static final int MIN_WIDTH = 600;

    private GridRenderer() {}

    /** Where each Y level's panel sits in the image. */
    record Layout(List<Integer> layers, int columns, int panelW, int panelH, int cellPx) {

        int index(int y) {
            return layers.indexOf(y);
        }

        int originX(int index) {
            return MARGIN + (index % columns) * (panelW + PANEL_GAP);
        }

        int originY(int index) {
            return MARGIN + (index / columns) * (LABEL_H + panelH + PANEL_GAP) + LABEL_H;
        }

        int rows() {
            return (layers.size() + columns - 1) / columns;
        }

        int width() {
            return Math.max(MIN_WIDTH, 2 * MARGIN + columns * panelW + (columns - 1) * PANEL_GAP);
        }

        int gridBottom() {
            return MARGIN + rows() * (LABEL_H + panelH) + (rows() - 1) * PANEL_GAP;
        }

        /** The cell under a pixel, as (x, panel level, z), or {@code null} outside every panel. */
        BlockPoint cellAt(int px, int py) {
            for (int i = 0; i < layers.size(); i++) {
                int ox = originX(i);
                int oy = originY(i);
                if (px >= ox && px < ox + panelW && py >= oy && py < oy + panelH) {
                    return new BlockPoint((px - ox) / cellPx, layers.get(i), (py - oy) / cellPx);
                }
            }
            return null;
        }
    }

    /** What one image shows, whether it came from a finished result or a replay frame. */
    private record Scene(BlockPoint start, BlockPoint goal, Set<BlockPoint> closed,
            Set<BlockPoint> open, BlockPoint current, List<PathStep> path, boolean finished,
            List<Waypoint> smoothed, String stats) {}

    /** The Y levels worth drawing: every level the search or its endpoints touched. */
    static List<Integer> layers(SearchResult result, BlockPoint start, BlockPoint goal) {
        TreeSet<Integer> ys = new TreeSet<>();
        ys.add(start.y());
        ys.add(goal.y());
        result.closed().forEach(p -> ys.add(p.y()));
        result.open().forEach(p -> ys.add(p.y()));
        result.path().forEach(s -> ys.add(s.pos().y()));
        return List.copyOf(ys);
    }

    /**
     * Every level a recorded search ever reached. Using the same levels for every frame keeps the
     * panels still during playback.
     */
    static List<Integer> layers(Recording recording) {
        TreeSet<Integer> ys = new TreeSet<>();
        ys.add(recording.start().y());
        ys.add(recording.goal().y());
        for (SearchEvent e : recording.events()) {
            if (e instanceof SearchEvent.Opened o) {
                ys.add(o.node().pos().y());
            }
        }
        return List.copyOf(ys);
    }

    static Layout layout(ArrayBlockView world, List<Integer> layers, int cellPx) {
        return new Layout(layers, Math.min(MAX_COLUMNS, layers.size()),
                world.sizeX() * cellPx, world.sizeZ() * cellPx, cellPx);
    }

    public static BufferedImage render(ArrayBlockView world, SearchResult result, BlockPoint start,
            BlockPoint goal, int cellPx, String title) {
        return render(world, result, List.of(), start, goal, cellPx, title);
    }

    /** Also draws a smoothed route over the path (see {@link astar.pathing.PathSmoother}). */
    public static BufferedImage render(ArrayBlockView world, SearchResult result,
            List<Waypoint> smoothed, BlockPoint start, BlockPoint goal, int cellPx, String title) {
        Scene scene = new Scene(start, goal, result.closed(), result.open(), null, result.path(),
                true, smoothed, resultStats(result) + waypointStats(smoothed));
        return render(world, scene, layout(world, layers(result, start, goal), cellPx), title, 0);
    }

    /** Renders one replay frame on the given levels (see {@link #layers(Recording)}). */
    public static BufferedImage render(ArrayBlockView world, FrameState frame, List<Integer> layers,
            int cellPx, String title) {
        return render(world, frame, List.of(), layers, cellPx, title);
    }

    /** Renders a replay frame; the smoothed route is only drawn once the search has finished. */
    public static BufferedImage render(ArrayBlockView world, FrameState frame,
            List<Waypoint> smoothed, List<Integer> layers, int cellPx, String title) {
        return render(world, frame, smoothed, layers, cellPx, title, 0);
    }

    /** As above, adding {@code labelYOffset} to each panel's "y =" label (for in-game y). */
    public static BufferedImage render(ArrayBlockView world, FrameState frame,
            List<Waypoint> smoothed, List<Integer> layers, int cellPx, String title,
            int labelYOffset) {
        List<Waypoint> shown = frame.finished() ? smoothed : List.of();
        String stats;
        if (frame.finished()) {
            stats = "step " + frame.step() + " (finished) · " + resultStats(frame.result());
        } else {
            stats = String.format("step %d · expanded %d · frontier %d",
                    frame.step(), frame.closed().size(), frame.open().size());
        }
        Scene scene = new Scene(frame.start(), frame.goal(), frame.closed(), frame.open(),
                frame.finished() ? null : frame.current(), frame.path(), frame.finished(), shown,
                stats + waypointStats(shown));
        return render(world, scene, layout(world, layers, cellPx), title, labelYOffset);
    }

    private static String waypointStats(List<Waypoint> smoothed) {
        return smoothed.isEmpty() ? "" : " · " + smoothed.size() + " waypoints";
    }

    private static String resultStats(SearchResult result) {
        return result.found()
                ? String.format("steps %d · cost %.3f · expanded %d · frontier %d",
                        result.path().size() - 1, result.cost(), result.expanded(),
                        result.open().size())
                : String.format("no path · expanded %d", result.expanded());
    }

    private static BufferedImage render(ArrayBlockView world, Scene scene, Layout layout,
            String title, int labelYOffset) {
        int legendTop = layout.gridBottom() + 6;
        int height = legendTop + (title == null ? 0 : LEGEND_ROW) + 6 * LEGEND_ROW + 4;

        BufferedImage img = new BufferedImage(layout.width(), height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(Palette.BACKGROUND);
            g.fillRect(0, 0, img.getWidth(), img.getHeight());
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));

            for (int i = 0; i < layout.layers().size(); i++) {
                drawPanel(g, world, scene, layout, i, labelYOffset);
            }
            drawLegend(g, scene, title, legendTop);
        } finally {
            g.dispose();
        }
        return img;
    }

    private static void drawPanel(Graphics2D g, ArrayBlockView world, Scene scene, Layout layout,
            int index, int labelYOffset) {
        BlockPoint start = scene.start();
        BlockPoint goal = scene.goal();
        int y = layout.layers().get(index);
        int ox = layout.originX(index);
        int oy = layout.originY(index);
        int c = layout.cellPx();

        g.setColor(Palette.TEXT);
        g.drawString("y = " + (y + labelYOffset), ox, oy - 6);

        // 1. Blocks at this level.
        for (int z = 0; z < world.sizeZ(); z++) {
            for (int x = 0; x < world.sizeX(); x++) {
                fillCell(g, ox, oy, c, x, z, blockColour(world, x, y, z));
            }
        }

        // 2-3. Lookahead: closed set, then open set.
        for (BlockPoint p : scene.closed()) {
            if (p.y() == y) {
                fillCell(g, ox, oy, c, p.x(), p.z(), Palette.CLOSED);
            }
        }
        for (BlockPoint p : scene.open()) {
            if (p.y() == y) {
                fillCell(g, ox, oy, c, p.x(), p.z(), Palette.OPEN);
            }
        }
        BlockPoint current = scene.current();
        if (current != null && current.y() == y) {
            fillCell(g, ox, oy, c, current.x(), current.z(), Palette.CURRENT);
        }

        // 4. Skeleton: path cells, then the line. A segment between two levels is drawn in both.
        //    While a replay is running, only a dashed line to the node being expanded is shown.
        List<PathStep> path = scene.path();
        if (scene.finished()) {
            for (PathStep s : path) {
                if (s.pos().y() == y) {
                    fillCell(g, ox, oy, c, s.pos().x(), s.pos().z(), Palette.PATH);
                }
            }
        }
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Palette.PATH_LINE);
        float width = Math.max(1.5f, c / 10f);
        g.setStroke(scene.finished()
                ? new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                : new BasicStroke(width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f,
                        new float[] {width * 2, width * 1.5f}, 0f));
        for (int i = 0; i + 1 < path.size(); i++) {
            BlockPoint a = path.get(i).pos();
            BlockPoint b = path.get(i + 1).pos();
            if (a.y() == y || b.y() == y) {
                g.draw(new Line2D.Double(cx(ox, c, a.x()), cy(oy, c, a.z()),
                        cx(ox, c, b.x()), cy(oy, c, b.z())));
            }
        }

        // 5. Key nodes.
        int keyR = Math.max(2, c / 5);
        for (PathStep s : PathAnalysis.keyNodes(path)) {
            BlockPoint p = s.pos();
            if (p.y() == y && !p.equals(start) && !p.equals(goal)) {
                dot(g, cx(ox, c, p.x()), cy(oy, c, p.z()), keyR, Palette.KEY_NODE);
            }
        }

        // 5b. The smoothed route: straight segments between waypoints, drawn over the path.
        List<Waypoint> smoothed = scene.smoothed();
        if (!smoothed.isEmpty()) {
            g.setColor(Palette.SMOOTH);
            g.setStroke(new BasicStroke(Math.max(1.5f, c / 12f), BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND));
            for (int i = 0; i + 1 < smoothed.size(); i++) {
                BlockPoint a = smoothed.get(i).pos();
                BlockPoint b = smoothed.get(i + 1).pos();
                if (a.y() == y || b.y() == y) {
                    g.draw(new Line2D.Double(cx(ox, c, a.x()), cy(oy, c, a.z()),
                            cx(ox, c, b.x()), cy(oy, c, b.z())));
                }
            }
            double half = Math.max(1.5, c / 7.0);
            for (Waypoint w : smoothed) {
                BlockPoint p = w.pos();
                if (p.y() == y && !p.equals(start) && !p.equals(goal)) {
                    square(g, cx(ox, c, p.x()), cy(oy, c, p.z()), half, Palette.SMOOTH);
                }
            }
        }

        // 6. Where the path arrives by jumping up or dropping down.
        for (int i = 0; i < path.size(); i++) {
            PathStep s = path.get(i);
            BlockPoint p = s.pos();
            if (p.y() != y || p.equals(goal)) {
                continue;
            }
            if (s.via() == MoveType.JUMP_UP) {
                triangle(g, cx(ox, c, p.x()), cy(oy, c, p.z()), c * 0.32, true, Palette.JUMP);
            } else if (s.via() == MoveType.DROP) {
                triangle(g, cx(ox, c, p.x()), cy(oy, c, p.z()), c * 0.32, false,
                        PathAnalysis.isDescent(path, i) ? Palette.DESCENT : Palette.DROP);
            }
        }

        // 7. Start and goal.
        int endR = Math.max(3, c / 3);
        if (start.y() == y) {
            dot(g, cx(ox, c, start.x()), cy(oy, c, start.z()), endR, Palette.START);
        }
        if (goal.y() == y) {
            dot(g, cx(ox, c, goal.x()), cy(oy, c, goal.z()), endR, Palette.GOAL);
        }

        // 8. Grid lines, when the cells are big enough for them to help.
        if (c < 6) {
            return;
        }
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setStroke(new BasicStroke(1));
        g.setColor(Palette.GRID_LINE);
        for (int x = 0; x <= world.sizeX(); x++) {
            g.drawLine(ox + x * c, oy, ox + x * c, oy + layout.panelH());
        }
        for (int z = 0; z <= world.sizeZ(); z++) {
            g.drawLine(ox, oy + z * c, ox + layout.panelW(), oy + z * c);
        }
    }

    /** How a cell looks at feet level {@code y}, from the block there and the block below. */
    static Color blockColour(ArrayBlockView world, int x, int y, int z) {
        BlockType here = world.blockAt(x, y, z);
        switch (here) {
            case SOLID, VOID, POWDER_SNOW, THIN:
                return Palette.WALL;
            case HAZARD, CACTUS:
                return Palette.HAZARD;
            case DOOR, GATE:
                return Palette.DOOR;
            case SOUL_SAND, HONEY, COBWEB:
                return Palette.SLOW;
            case MAGMA, BERRY_BUSH:
                return Palette.HURTS;
            case TALL:
                return Palette.TALL;
            case STAIRS:
                return Palette.STAIRS;
            case WATER, FLOWING_WATER, BUBBLE_UP, BUBBLE_DOWN:
                return Palette.WATER;
            case CLIMBABLE, SCAFFOLDING:
                return Palette.LADDER;
            default:
                if (here.isPartialFloor()) {
                    return Palette.PARTIAL;
                }
        }
        return switch (world.blockAt(x, y - 1, z)) {
            case SOLID, STAIRS -> Palette.CELL;
            case TALL -> Palette.TALL; // the top half of a fence
            case GATE -> Palette.DOOR; // or of a gate
            case HAZARD, CACTUS -> Palette.HAZARD;
            case MAGMA -> Palette.HURTS;
            default -> Palette.GAP;
        };
    }

    private static void drawLegend(Graphics2D g, Scene scene, String title, int top) {
        FontMetrics fm = g.getFontMetrics();
        int y = top;
        if (title != null) {
            g.setColor(Palette.TEXT);
            Font plain = g.getFont();
            g.setFont(plain.deriveFont(Font.BOLD));
            g.drawString(title, MARGIN, y + fm.getAscent());
            g.setFont(plain);
            y += LEGEND_ROW;
        }

        int x = swatch(g, fm, MARGIN, y, Palette.CELL, "Cell");
        x = swatch(g, fm, x, y, Palette.WALL, "Wall");
        x = swatch(g, fm, x, y, Palette.GAP, "Gap (no floor)");
        swatch(g, fm, x, y, Palette.HAZARD, "Hazard");
        y += LEGEND_ROW;

        x = swatch(g, fm, MARGIN, y, Palette.PARTIAL, "Slab / carpet / low block");
        x = swatch(g, fm, x, y, Palette.STAIRS, "Stairs");
        x = swatch(g, fm, x, y, Palette.TALL, "Fence / wall (1.5)");
        x = swatch(g, fm, x, y, Palette.WATER, "Water");
        swatch(g, fm, x, y, Palette.LADDER, "Ladder / vine");
        y += LEGEND_ROW;

        x = swatch(g, fm, MARGIN, y, Palette.DOOR, "Door / gate");
        x = swatch(g, fm, x, y, Palette.SLOW, "Slow (soul sand, honey, cobweb)");
        swatch(g, fm, x, y, Palette.HURTS, "Hurts (magma, berry bush)");
        y += LEGEND_ROW;

        x = swatch(g, fm, MARGIN, y, Palette.CLOSED, "Closed (expanded)");
        x = swatch(g, fm, x, y, Palette.OPEN, "Open (frontier)");
        x = swatch(g, fm, x, y, Palette.CURRENT, "Expanding now");
        swatch(g, fm, x, y, Palette.PATH, "Skeleton (path)");
        y += LEGEND_ROW;

        x = marker(g, fm, MARGIN, y, "Key node", (cx, cy) -> dot(g, cx, cy, 5, Palette.KEY_NODE));
        x = marker(g, fm, x, y, "Jump up", (cx, cy) -> triangle(g, cx, cy, 7, true, Palette.JUMP));
        x = marker(g, fm, x, y, "Descent", (cx, cy) -> triangle(g, cx, cy, 7, false,
                Palette.DESCENT));
        x = marker(g, fm, x, y, "Drop", (cx, cy) -> triangle(g, cx, cy, 7, false, Palette.DROP));
        x = marker(g, fm, x, y, "Start", (cx, cy) -> dot(g, cx, cy, 6, Palette.START));
        x = marker(g, fm, x, y, "Goal", (cx, cy) -> dot(g, cx, cy, 6, Palette.GOAL));
        marker(g, fm, x, y, "Smoothed route", (cx, cy) -> {
            g.setColor(Palette.SMOOTH);
            g.setStroke(new BasicStroke(2.5f));
            g.draw(new Line2D.Double(cx - 7, cy + 4, cx + 7, cy - 4));
            square(g, cx, cy, 3.5, Palette.SMOOTH);
        });
        y += LEGEND_ROW;

        g.setColor(Palette.TEXT);
        g.drawString(scene.stats(), MARGIN, y + fm.getAscent());
    }

    private static int swatch(Graphics2D g, FontMetrics fm, int x, int y, Color c, String label) {
        int s = 12;
        g.setColor(c);
        g.fillRect(x, y + 1, s, s);
        g.setColor(Palette.GRID_LINE);
        g.drawRect(x, y + 1, s, s);
        return label(g, fm, x + s + 5, y, label);
    }

    private interface Glyph {
        void draw(double cx, double cy);
    }

    private static int marker(Graphics2D g, FontMetrics fm, int x, int y, String label, Glyph glyph) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        glyph.draw(x + 6, y + 7);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        return label(g, fm, x + 17, y, label);
    }

    private static int label(Graphics2D g, FontMetrics fm, int x, int y, String text) {
        g.setColor(Palette.TEXT);
        g.drawString(text, x, y + fm.getAscent());
        return x + fm.stringWidth(text) + 14;
    }

    private static void fillCell(Graphics2D g, int ox, int oy, int c, int x, int z, Color colour) {
        g.setColor(colour);
        g.fillRect(ox + x * c, oy + z * c, c, c);
    }

    private static void dot(Graphics2D g, double cx, double cy, double r, Color colour) {
        g.setColor(colour);
        g.fill(new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r));
    }

    private static void square(Graphics2D g, double cx, double cy, double half, Color colour) {
        java.awt.geom.Rectangle2D.Double r =
                new java.awt.geom.Rectangle2D.Double(cx - half, cy - half, 2 * half, 2 * half);
        g.setColor(colour);
        g.fill(r);
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(1.2f));
        g.draw(r);
    }

    private static void triangle(Graphics2D g, double cx, double cy, double r, boolean up,
            Color colour) {
        double tip = up ? -r : r;
        Path2D t = new Path2D.Double();
        t.moveTo(cx, cy + tip);
        t.lineTo(cx - r, cy - tip * 0.8);
        t.lineTo(cx + r, cy - tip * 0.8);
        t.closePath();
        g.setColor(colour);
        g.fill(t);
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(1.5f));
        g.draw(t);
    }

    private static double cx(int ox, int c, int x) {
        return ox + x * c + c / 2.0;
    }

    private static double cy(int oy, int c, int z) {
        return oy + z * c + c / 2.0;
    }
}
