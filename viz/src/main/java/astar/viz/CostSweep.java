package astar.viz;

import astar.core.BlockPoint;
import astar.core.DefaultCostModel;
import astar.core.PathStep;
import astar.core.Pos;
import astar.pathing.ArrayBlockView;
import astar.pathing.PathAnalysis;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Line2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The same route searched with several straight-step costs, diagonal-step costs and heuristic
 * weights, compared: a table of what each path is like, and pictures of them side by side.
 *
 * <p>Each setting is written {@code walk/diagonal/weight} or {@code walk/diagonal/weight/turn},
 * e.g. {@code 1/1.414/1} (the defaults), {@code 1/1/2} (diagonals as cheap as straight steps,
 * heuristic doubled) or {@code 1/1.414/1/0.1} (0.1 per 45 degrees of turn).
 */
final class CostSweep {
    private CostSweep() {}

    /**
     * One setting: the straight and diagonal step costs, the heuristic weight, and the cost
     * per 45 degrees of turn (NaN: whatever the base settings say).
     */
    record Setting(double walk, double diagonal, double weight, double turn) {
        Setting(double walk, double diagonal, double weight) {
            this(walk, diagonal, weight, Double.NaN);
        }

        static Setting parse(String s) {
            String[] p = s.trim().split("/");
            if (p.length != 3 && p.length != 4) {
                throw new IllegalArgumentException("Each --sweep setting is walk/diagonal/weight"
                        + " or walk/diagonal/weight/turn, e.g. 1/1.414/1 or 1/1.414/1/0.1; got "
                        + s);
            }
            double diagonal = Double.parseDouble(p[1]);
            // 1.414 and the like mean sqrt(2), the default, not a slightly cheaper diagonal.
            if (Math.abs(diagonal - Math.sqrt(2)) < 5e-4) {
                diagonal = Math.sqrt(2);
            }
            Setting r = new Setting(Double.parseDouble(p[0]), diagonal, Double.parseDouble(p[2]),
                    p.length == 4 ? Double.parseDouble(p[3]) : Double.NaN);
            if (!Double.isNaN(r.turn) && !(r.turn >= 0)) {
                throw new IllegalArgumentException("The turn cost can't be negative; got " + s);
            }
            if (!(r.walk > 0) || !(r.diagonal > 0)) {
                throw new IllegalArgumentException("Step costs must be above 0; got " + s);
            }
            if (!(r.weight >= 1)) {
                throw new IllegalArgumentException("The heuristic weight must be at least 1; got "
                        + s);
            }
            return r;
        }

        String label() {
            double t = turnOr(0);
            return "straight " + num(walk) + ", diagonal " + num(diagonal)
                    + (weight == 1 ? "" : ", weight " + num(weight))
                    + (t == 0 ? "" : ", turn " + num(t));
        }

        double turnOr(double baseTurn) {
            return Double.isNaN(turn) ? baseTurn : turn;
        }
    }

    /**
     * The settings compared when none are given: the defaults; diagonals as cheap as straight
     * steps; a bit dearer than their length; as dear as two straight steps; and the default
     * costs with the heuristic weighted 1.5 and 3 times.
     */
    static final List<Setting> DEFAULTS = List.of(
            new Setting(1, Math.sqrt(2), 1),
            new Setting(1, 1, 1),
            new Setting(1, 1.6, 1),
            new Setting(1, 2, 1),
            new Setting(1, Math.sqrt(2), 1.5),
            new Setting(1, Math.sqrt(2), 3));

    static List<Setting> parse(String list) {
        List<Setting> out = new ArrayList<>();
        for (String s : list.split(",")) {
            if (!s.isBlank()) {
                out.add(Setting.parse(s));
            }
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("--sweep needs at least one walk/diagonal/weight");
        }
        return out;
    }

    /** What one setting's search found, and how its path measures up. */
    record Row(Setting setting, RouteTool.Outcome outcome, double defaultCost, int keyNodes,
            int headingChanges, double turningDegrees) {
        List<PathStep> path() {
            return outcome.result().path();
        }
    }

    /** Runs every setting from {@code start} to {@code goal} on top of {@code base}. */
    static List<Row> run(ArrayBlockView blocks, BlockPoint start, BlockPoint goal,
            RouteTool.Settings base, List<Setting> settings) {
        List<Row> rows = new ArrayList<>();
        for (Setting given : settings) {
            Setting s = new Setting(given.walk(), given.diagonal(), given.weight(),
                    given.turnOr(base.turnCost()));
            DefaultCostModel costs = base.costs().withSteps(s.walk(), s.diagonal());
            RouteTool.Settings rs = new RouteTool.Settings(costs, base.terrain(), s.weight(),
                    base.directions(), false, base.clusterSize(), -1, -1, s.turn(),
                    base.diagonalLeaps(), base.heightAware(), base.landmarks(),
                    base.exactTurns());
            RouteTool.Outcome o = RouteTool.run(blocks, start, goal, rs);
            List<PathStep> path = o.result().path();
            double[] turns = turning(path);
            rows.add(new Row(s, o, o.result().found()
                    ? RouteTool.priceUnderDefaults(blocks, path) : Double.NaN,
                    o.result().found() ? PathAnalysis.keyNodes(path).size() : 0,
                    (int) turns[0], turns[1]));
        }
        return rows;
    }

    /**
     * How much a path turns, seen from above: {count of heading changes, total degrees turned}.
     * Steps straight up or down have no heading and are skipped over.
     */
    static double[] turning(List<PathStep> path) {
        int changes = 0;
        double degrees = 0;
        double last = Double.NaN;
        for (int i = 1; i < path.size(); i++) {
            BlockPoint a = path.get(i - 1).pos();
            BlockPoint b = path.get(i).pos();
            int dx = b.x() - a.x();
            int dz = b.z() - a.z();
            if (dx == 0 && dz == 0) {
                continue;
            }
            double heading = Math.toDegrees(Math.atan2(dz, dx));
            if (!Double.isNaN(last)) {
                double turn = Math.abs(heading - last) % 360;
                turn = Math.min(turn, 360 - turn);
                if (turn > 1e-6) {
                    changes++;
                    degrees += turn;
                }
            }
            last = heading;
        }
        return new double[] {changes, degrees};
    }

    static void print(List<Row> rows) {
        System.out.println();
        System.out.println("  Sweep over step costs and heuristic weights (same start and goal):");
        System.out.printf(Locale.ROOT, "    %-3s %-8s %-8s %-6s %-5s %6s %8s %9s %9s %6s %8s %9s %9s%n",
                "", "straight", "diagonal", "weight", "turn", "steps", "blocks", "own cost",
                "def. cost", "key", "turns", "degrees", "expanded");
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            Setting s = r.setting();
            if (!r.outcome().result().found()) {
                System.out.printf(Locale.ROOT, "    %-3s %-8s %-8s %-6s %-5s  no path%n",
                        letter(i), num(s.walk()), num(s.diagonal()), num(s.weight()),
                        num(s.turn()));
                continue;
            }
            System.out.printf(Locale.ROOT,
                    "    %-3s %-8s %-8s %-6s %-5s %,6d %8.1f %9.1f %9.1f %,6d %,8d %,9.0f %,9d%n",
                    letter(i), num(s.walk()), num(s.diagonal()), num(s.weight()), num(s.turn()),
                    r.path().size() - 1, r.outcome().length(), r.outcome().result().cost(),
                    r.defaultCost(), r.keyNodes(), r.headingChanges(), r.turningDegrees(),
                    r.outcome().result().expanded());
        }
        System.out.println("    blocks: distance walked along the grid path. own cost: priced with"
                + " that row's costs. def. cost: priced with the default costs, so rows compare.");
        System.out.println("    key: key nodes (turns and changes of move). turns: heading changes"
                + " seen from above; degrees: all of them added up.");
    }

    /**
     * Every setting on the same random routes: per 100 blocks walked, how many key nodes and
     * turns; how much dearer at default prices than setting A; and nodes expanded and search
     * time, against A. Routes some setting can't find are left out.
     */
    static void printRandom(ArrayBlockView blocks, BlockPoint[][] routes, RouteTool.Settings base,
            List<Setting> settings) {
        int n = settings.size();
        double[] blocksWalked = new double[n];
        double[] keys = new double[n];
        double[] turns = new double[n];
        double[] cost = new double[n];
        double[] expanded = new double[n];
        double[] ms = new double[n];
        int used = 0;
        for (BlockPoint[] r : routes) {
            List<Row> rows = run(blocks, r[0], r[1], base, settings);
            if (rows.stream().anyMatch(x -> !x.outcome().result().found())) {
                continue;
            }
            used++;
            for (int i = 0; i < n; i++) {
                Row x = rows.get(i);
                blocksWalked[i] += x.outcome().length();
                keys[i] += x.keyNodes();
                turns[i] += x.headingChanges();
                cost[i] += x.defaultCost();
                expanded[i] += x.outcome().result().expanded();
                ms[i] += x.outcome().searchMs();
            }
        }
        System.out.printf(Locale.ROOT, "%n  The same settings on %d random routes (%d found by"
                + " all), totals:%n", routes.length, used);
        System.out.printf(Locale.ROOT, "    %-3s %-8s %-8s %-6s %-5s %10s %10s %10s %10s %10s %10s%n",
                "", "straight", "diagonal", "weight", "turn", "blocks", "key/100", "turns/100",
                "def. cost", "expanded", "search ms");
        for (int i = 0; i < n; i++) {
            Setting s = settings.get(i);
            double t = s.turnOr(base.turnCost());
            double per = 100 / Math.max(1e-9, blocksWalked[i]);
            System.out.printf(Locale.ROOT,
                    "    %-3s %-8s %-8s %-6s %-5s %,10.0f %10.1f %10.1f %9s %9s %9s%n",
                    letter(i), num(s.walk()), num(s.diagonal()), num(s.weight()), num(t),
                    blocksWalked[i], keys[i] * per, turns[i] * per,
                    relative(cost[i], cost[0]), relative(expanded[i], expanded[0]),
                    relative(ms[i], ms[0]));
        }
        System.out.println("    key/100, turns/100: per 100 blocks walked. def. cost, expanded,"
                + " search ms: against A (+3% is 3% more).");
    }

    private static String relative(double v, double a) {
        if (a == 0) {
            return "-";
        }
        return String.format(Locale.ROOT, "%+.1f%%", (v / a - 1) * 100);
    }

    static String letter(int i) {
        return String.valueOf((char) ('A' + i));
    }

    // ---- Pictures ---------------------------------------------------------------------------

    private static final Font TITLE = new Font(Font.SANS_SERIF, Font.BOLD, 14);
    private static final Font SMALL = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
    private static final int COLUMNS = 3;
    private static final int CAPTION = 58;
    private static final int GAP = 12;

    /**
     * Every setting's path over the whole map, side by side, with the close-up's window
     * outlined.
     */
    static BufferedImage overview(ArrayBlockView world, List<Row> rows, Rectangle window,
            String title) {
        int c = Math.max(1, Math.min(4, 560 / Math.max(world.sizeX(), world.sizeZ())));
        return panels(world, rows, new Rectangle(0, 0, world.sizeX(), world.sizeZ()), c, window,
                false, title);
    }

    /** The same window of every setting's path, zoomed in, with every node and key node. */
    static BufferedImage closeUp(ArrayBlockView world, List<Row> rows, Rectangle window,
            String title) {
        int c = Math.max(4, Math.min(16, 520 / Math.max(window.width, window.height)));
        return panels(world, rows, window, c, null, true, title);
    }

    private static BufferedImage panels(ArrayBlockView world, List<Row> rows, Rectangle area,
            int c, Rectangle outline, boolean nodes, String title) {
        int cols = Math.min(COLUMNS, rows.size());
        int rowsN = (rows.size() + cols - 1) / cols;
        int pw = area.width * c;
        int ph = area.height * c;
        int top = 34;
        int legend = 30;
        int w = GAP + cols * (pw + GAP);
        int h = top + rowsN * (ph + CAPTION + GAP) + legend;
        BufferedImage img = new BufferedImage(Math.max(w, 640), h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(Palette.BACKGROUND);
            g.fillRect(0, 0, img.getWidth(), img.getHeight());
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(TITLE);
            g.setColor(Palette.TEXT);
            g.drawString(title, GAP, 22);

            BufferedImage terrain = RouteImages.heights(world).image(HeightMap.Style.GREY);
            int[] yr = yRange(rows);
            List<PathStep> baseline = rows.get(0).path();
            for (int i = 0; i < rows.size(); i++) {
                Row r = rows.get(i);
                int px = GAP + (i % cols) * (pw + GAP);
                int py = top + (i / cols) * (ph + CAPTION + GAP);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                g.drawImage(terrain, px, py, px + pw, py + ph, area.x, area.y,
                        area.x + area.width, area.y + area.height, null);
                Shape clip = g.getClip();
                g.clipRect(px, py, pw, ph);
                double ox = px - area.x * (double) c;
                double oy = py - area.y * (double) c;
                if (i > 0) {
                    drawBaseline(g, baseline, ox, oy, c);
                }
                RouteImages.drawRoute(g, r.path(), List.of(), null, null, ox, oy, c, yr,
                        HeightMap.Style.GREY, nodes);
                if (outline != null) {
                    g.setStroke(new BasicStroke(2));
                    g.setColor(Palette.KEY_NODE);
                    g.drawRect((int) (ox + outline.x * c), (int) (oy + outline.y * c),
                            outline.width * c, outline.height * c);
                }
                g.setClip(clip);
                g.setColor(Palette.GRID_LINE);
                g.setStroke(new BasicStroke(1));
                g.drawRect(px, py, pw, ph);
                caption(g, r, i, px, py + ph + 16);
            }
            int ly = h - legend + 12;
            g.setFont(SMALL);
            g.setColor(Palette.TEXT);
            g.drawString((nodes ? "Dots: every path node. Red rings: key nodes (the direction or"
                    + " the kind of move changes). " : "")
                    + "Dashed: path A, for comparison. The line is coloured by height; green is"
                    + " the start, purple the goal." + (outline != null
                            ? " The red box is the close-up." : ""), GAP, ly);
        } finally {
            g.dispose();
        }
        return img;
    }

    /** Path A as a thin dashed line under the others, so the difference shows. */
    private static void drawBaseline(Graphics2D g, List<PathStep> path, double ox, double oy,
            double c) {
        Object aa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        float w = (float) Math.max(1.2, Math.min(3, c * 0.25));
        g.setStroke(new BasicStroke(w, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f,
                new float[] {w * 3, w * 2}, 0f));
        g.setColor(new Color(20, 20, 24, 170));
        for (int i = 0; i + 1 < path.size(); i++) {
            BlockPoint a = path.get(i).pos();
            BlockPoint b = path.get(i + 1).pos();
            g.draw(new Line2D.Double(ox + (a.x() + 0.5) * c, oy + (a.z() + 0.5) * c,
                    ox + (b.x() + 0.5) * c, oy + (b.z() + 0.5) * c));
        }
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa);
    }

    private static void caption(Graphics2D g, Row r, int i, int x, int y) {
        g.setFont(TITLE);
        g.setColor(Palette.TEXT);
        g.drawString(letter(i) + ". " + r.setting().label(), x, y);
        g.setFont(SMALL);
        if (!r.outcome().result().found()) {
            g.drawString("no path", x, y + 17);
            return;
        }
        g.drawString(String.format(Locale.ROOT, "%.0f blocks, %d key nodes, %d turns",
                r.outcome().length(), r.keyNodes(), r.headingChanges()), x, y + 17);
        g.drawString(String.format(Locale.ROOT, "cost %.1f at default prices, %,d nodes expanded",
                r.defaultCost(), r.outcome().result().expanded()), x, y + 32);
    }

    private static int[] yRange(List<Row> rows) {
        int lo = Integer.MAX_VALUE;
        int hi = Integer.MIN_VALUE;
        for (Row r : rows) {
            for (PathStep s : r.path()) {
                lo = Math.min(lo, s.pos().y());
                hi = Math.max(hi, s.pos().y());
            }
        }
        return lo > hi ? new int[] {0, 1} : new int[] {lo, Math.max(hi, lo + 1)};
    }

    /**
     * A {@code size} by {@code size} window (in blocks, from above) where the paths disagree
     * most: the most columns that some paths cross and others don't. Candidate windows are
     * centred on every eighth step of each path.
     */
    static Rectangle busiestDifference(ArrayBlockView world, List<Row> rows, int size) {
        List<Set<Long>> columns = new ArrayList<>();
        for (Row r : rows) {
            Set<Long> cols = new HashSet<>();
            for (PathStep s : r.path()) {
                cols.add(Pos.pack(s.pos().x(), 0, s.pos().z()));
            }
            columns.add(cols);
        }
        Set<Long> some = new HashSet<>();
        columns.forEach(some::addAll);
        List<long[]> differing = new ArrayList<>();
        for (long col : some) {
            boolean all = columns.stream().allMatch(s -> s.contains(col));
            if (!all) {
                differing.add(new long[] {Pos.x(col), Pos.z(col)});
            }
        }
        int sx = Math.min(size, world.sizeX());
        int sz = Math.min(size, world.sizeZ());
        Rectangle best = null;
        int bestScore = -1;
        for (Row r : rows) {
            List<PathStep> path = r.path();
            for (int i = 0; i < path.size(); i += 8) {
                BlockPoint p = path.get(i).pos();
                int x0 = Math.max(0, Math.min(world.sizeX() - sx, p.x() - sx / 2));
                int z0 = Math.max(0, Math.min(world.sizeZ() - sz, p.z() - sz / 2));
                int score = 0;
                for (long[] d : differing) {
                    if (d[0] >= x0 && d[0] < x0 + sx && d[1] >= z0 && d[1] < z0 + sz) {
                        score++;
                    }
                }
                if (score > bestScore) {
                    bestScore = score;
                    best = new Rectangle(x0, z0, sx, sz);
                }
            }
        }
        return best != null ? best : new Rectangle(0, 0, sx, sz);
    }

    private static String num(double v) {
        if (Math.abs(v - Math.sqrt(2)) < 1e-9) {
            return "1.414";
        }
        return v == Math.rint(v) ? String.valueOf((long) v)
                : String.format(Locale.ROOT, "%.3f", v).replaceAll("0+$", "");
    }
}
