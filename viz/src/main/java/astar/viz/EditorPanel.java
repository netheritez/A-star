package astar.viz;

import astar.core.BlockPoint;
import astar.core.PathStep;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Objects;
import javax.swing.JComponent;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;

/**
 * Draws the current frame and turns mouse input into {@link EditorModel} calls.
 *
 * <p>Two views: one panel per level (see {@link GridRenderer}), or the whole map from above
 * (see {@link HeightMap}). Ctrl + mouse wheel zooms around the pointer, and dragging with the
 * right or middle button pans.
 */
public final class EditorPanel extends JComponent {
    private static final Color EXPLORED = new Color(0x3F, 0x6F, 0xB5, 70);

    private final EditorModel model;

    // Level panels: rendered at a whole number of pixels per block, then scaled.
    private BufferedImage image;
    private GridRenderer.Layout layout;
    private int renderPx;
    private double scale = 1;
    private Object[] imageKey;

    // From above: the terrain at one pixel per block, and a smaller copy when zoomed out.
    private BufferedImage terrain;
    private Object[] terrainKey;
    private Image shrunk;
    private double shrunkZoom;

    private WorldSource shownSource;
    private Point panFrom;

    public EditorPanel(EditorModel model, double zoom) {
        this.model = model;
        model.setZoom(zoom);
        model.addChangeListener(this::refresh);
        refresh();

        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (isPan(e)) {
                    panFrom = e.getLocationOnScreen();
                    return;
                }
                model.beginDrag(cellAt(e));
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (panFrom != null) {
                    pan(e.getLocationOnScreen());
                    return;
                }
                model.dragTo(cellAt(e));
                model.setHovered(cellAt(e));
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (panFrom != null) {
                    panFrom = null;
                    return;
                }
                model.endDrag(cellAt(e));
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                model.setHovered(cellAt(e));
            }

            @Override
            public void mouseExited(MouseEvent e) {
                model.setHovered(null);
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                if (e.isControlDown() || e.isMetaDown()) {
                    zoomAround(e.getPoint(), e.getWheelRotation() < 0);
                } else if (getParent() != null) {
                    // Let the scroll pane scroll as usual.
                    getParent().dispatchEvent(SwingUtilities.convertMouseEvent(
                            EditorPanel.this, e, getParent()));
                }
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);
    }

    private static boolean isPan(MouseEvent e) {
        return SwingUtilities.isRightMouseButton(e) || SwingUtilities.isMiddleMouseButton(e);
    }

    private BlockPoint cellAt(MouseEvent e) {
        return cellAt(e.getX(), e.getY());
    }

    /** The cell under a point of this component, or {@code null}. */
    BlockPoint cellAt(int px, int py) {
        double zoom = model.zoom();
        if (model.above() != null) {
            int x = (int) Math.floor((px - GridRenderer.MARGIN) / zoom);
            int z = (int) Math.floor((py - GridRenderer.MARGIN) / zoom);
            if (x < 0 || z < 0 || x >= model.world().sizeX() || z >= model.world().sizeZ()) {
                return null;
            }
            return model.columnCell(x, z);
        }
        return layout.cellAt((int) (px / scale), (int) (py / scale));
    }

    /** Re-renders what changed; called whenever the model changes. */
    void refresh() {
        Dimension size;
        if (model.above() != null) {
            refreshTerrain();
            double zoom = model.zoom();
            size = new Dimension(
                    Math.max(RouteImages.LEGEND_H * 12,
                            (int) Math.ceil(2 * GridRenderer.MARGIN + model.world().sizeX() * zoom)),
                    (int) Math.ceil(2 * GridRenderer.MARGIN + model.world().sizeZ() * zoom)
                            + RouteImages.LEGEND_H + 8);
        } else {
            refreshLevels();
            size = new Dimension((int) Math.ceil(image.getWidth() * scale),
                    (int) Math.ceil(image.getHeight() * scale));
        }
        if (!size.equals(getPreferredSize())) {
            setPreferredSize(size);
            revalidate();
        }
        repaint();

        if (model.source() != shownSource) {
            shownSource = model.source();
            if (shownSource.imported()) {
                SwingUtilities.invokeLater(this::fitToViewport);
            }
        }
    }

    /** Re-renders the level panels, but only if something they show has changed. */
    private void refreshLevels() {
        double zoom = model.zoom();
        renderPx = Math.max(1, (int) Math.ceil(zoom));
        scale = zoom / renderPx;
        List<Integer> layers = model.layers();
        // The world and frame are compared by identity (each edit re-runs the search, making a
        // new frame); the lists by value.
        Object[] key = {model.world(), model.frame(), layers, model.shownWaypoints(), renderPx};
        if (image != null && sameKey(key, imageKey)) {
            return;
        }
        imageKey = key;
        layout = GridRenderer.layout(model.world(), layers, renderPx);
        image = GridRenderer.render(model.world(), model.frame(), model.shownWaypoints(),
                layers, renderPx, null);
    }

    private void refreshTerrain() {
        Object[] key = {model.heightMap(), model.above()};
        if (terrain == null || !sameKey(key, terrainKey)) {
            terrainKey = key;
            terrain = model.heightMap().image(model.above());
            shrunk = null;
        }
        double zoom = model.zoom();
        if (zoom < 1 && (shrunk == null || shrunkZoom != zoom)) {
            // Average the blocks under each pixel, rather than picking one of them.
            shrunkZoom = zoom;
            shrunk = terrain.getScaledInstance(Math.max(1, (int) Math.round(terrain.getWidth() * zoom)),
                    Math.max(1, (int) Math.round(terrain.getHeight() * zoom)), Image.SCALE_AREA_AVERAGING);
        }
    }

    /** Whether two cache keys match: lists and numbers by value, everything else by identity. */
    private static boolean sameKey(Object[] a, Object[] b) {
        if (b == null || a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            boolean same = a[i] instanceof List<?> || a[i] instanceof Integer
                    ? Objects.equals(a[i], b[i]) : a[i] == b[i];
            if (!same) {
                return false;
            }
        }
        return true;
    }

    /** Zooms so the whole view fits the scroll pane this panel is in. */
    void fitToViewport() {
        JViewport viewport = viewport();
        if (viewport == null || viewport.getExtentSize().width <= 0) {
            return;
        }
        Dimension extent = viewport.getExtentSize();
        model.fit(extent.width, extent.height);
    }

    private JViewport viewport() {
        return getParent() instanceof JViewport v ? v : null;
    }

    private void pan(Point screen) {
        JViewport viewport = viewport();
        if (viewport == null) {
            return;
        }
        Point view = viewport.getViewPosition();
        view.translate(panFrom.x - screen.x, panFrom.y - screen.y);
        panFrom = screen;
        viewport.setViewPosition(clamp(viewport, view));
    }

    /** One zoom step in or out, keeping the point under the mouse where it is. */
    private void zoomAround(Point p, boolean in) {
        double before = model.zoom();
        if (in) {
            model.zoomIn();
        } else {
            model.zoomOut();
        }
        double ratio = model.zoom() / before;
        JViewport viewport = viewport();
        if (viewport == null || ratio == 1) {
            return;
        }
        Point view = viewport.getViewPosition();
        int offX = p.x - view.x;
        int offY = p.y - view.y;
        setSize(getPreferredSize());
        Point next = new Point((int) Math.round(p.x * ratio) - offX,
                (int) Math.round(p.y * ratio) - offY);
        viewport.setViewPosition(clamp(viewport, next));
    }

    private Point clamp(JViewport viewport, Point p) {
        Dimension extent = viewport.getExtentSize();
        Dimension size = getSize();
        return new Point(Math.max(0, Math.min(p.x, size.width - extent.width)),
                Math.max(0, Math.min(p.y, size.height - extent.height)));
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            if (model.above() != null) {
                paintAbove(g);
            } else {
                paintLevels(g);
            }
        } finally {
            g.dispose();
        }
    }

    private void paintLevels(Graphics2D g) {
        if (scale != 1) {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(image, 0, 0, (int) Math.ceil(image.getWidth() * scale),
                    (int) Math.ceil(image.getHeight() * scale), null);
        } else {
            g.drawImage(image, 0, 0, null);
        }
        BlockPoint h = model.hovered();
        int i = h == null ? -1 : layout.index(h.y());
        if (i >= 0) {
            double c = renderPx * scale;
            g.setColor(Palette.HOVER);
            g.setStroke(new BasicStroke(2f));
            g.drawRect((int) Math.round((layout.originX(i) + h.x() * renderPx) * scale) + 1,
                    (int) Math.round((layout.originY(i) + h.z() * renderPx) * scale) + 1,
                    (int) Math.max(2, c - 2), (int) Math.max(2, c - 2));
        }
    }

    private void paintAbove(Graphics2D g) {
        double c = model.zoom();
        int m = GridRenderer.MARGIN;
        int sx = model.world().sizeX();
        int sz = model.world().sizeZ();
        int w = (int) Math.round(sx * c);
        int h = (int) Math.round(sz * c);
        g.setColor(Palette.BACKGROUND);
        g.fillRect(0, 0, getWidth(), getHeight());
        if (c < 1 && shrunk != null) {
            g.drawImage(shrunk, m, m, w, h, null);
        } else {
            // Nearest neighbour: each block stays a crisp square. Only the visible part is drawn.
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(terrain, m, m, w, h, null);
        }

        // While replaying: what the search has explored so far, by column, within the visible
        // area. Once it has finished, the terrain is left clear.
        FrameState frame = model.frame();
        Rectangle clip = g.getClipBounds() != null ? g.getClipBounds()
                : new Rectangle(0, 0, getWidth(), getHeight());
        double x0 = (clip.x - m) / c - 1;
        double x1 = (clip.x + clip.width - m) / c;
        double z0 = (clip.y - m) / c - 1;
        double z1 = (clip.y + clip.height - m) / c;
        g.setColor(EXPLORED);
        double cell = Math.max(1, c);
        for (BlockPoint p : frame.finished() ? List.<BlockPoint>of() : frame.closed()) {
            if (p.x() >= x0 && p.x() <= x1 && p.z() >= z0 && p.z() <= z1) {
                g.fillRect((int) (m + p.x() * c), (int) (m + p.z() * c), (int) Math.ceil(cell),
                        (int) Math.ceil(cell));
            }
        }

        List<PathStep> path = frame.path();
        int[] yr = heightRange();
        RouteImages.drawRoute(g, path, frame.finished() ? model.shownWaypoints() : List.of(),
                frame.start(), frame.goal(), m, m, c, yr, model.above(),
                frame.finished() && model.showNodes());

        BlockPoint hv = model.hovered();
        if (hv != null) {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            g.setColor(Palette.HOVER);
            g.setStroke(new BasicStroke(2f));
            int size = (int) Math.max(4, Math.round(c));
            g.drawRect((int) Math.round(m + (hv.x() + 0.5) * c) - size / 2,
                    (int) Math.round(m + (hv.z() + 0.5) * c) - size / 2, size, size);
        }

        RouteImages.drawLegend(g, m, m + h + 10, yr, model.source().origin(), model.above(),
                model.showNodes());
    }

    /** The heights the colour ramp spans: the route's in the grey style, the map's otherwise. */
    private int[] heightRange() {
        if (model.above() == HeightMap.Style.HEIGHT || !model.result().found()) {
            return new int[] {model.heightMap().minHeight(), model.heightMap().maxHeight()};
        }
        int lo = Integer.MAX_VALUE;
        int hi = Integer.MIN_VALUE;
        for (PathStep s : model.result().path()) {
            lo = Math.min(lo, s.pos().y());
            hi = Math.max(hi, s.pos().y());
        }
        return new int[] {lo, Math.max(hi, lo + 1)};
    }
}
