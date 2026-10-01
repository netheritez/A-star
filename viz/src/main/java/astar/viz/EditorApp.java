package astar.viz;

import astar.core.BlockPoint;
import java.awt.BorderLayout;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import astar.mcworld.Island;
import astar.mcworld.WorldImporter;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import javax.swing.AbstractAction;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import javax.swing.JFileChooser;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.JOptionPane;
import javax.swing.SwingWorker;
import javax.imageio.ImageIO;
import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * The interactive editor: edit the world, move the start and goal, and replay the search.
 *
 * <p>Usage: {@code EditorApp [--world <dir> [--island <n>]] [--demo] [--script]
 * [--screenshot <file>]}.
 *
 * <ul>
 *   <li>{@code --world} opens an island from a Minecraft 1.21 world save (a folder or a
 *       {@code .zip}): the largest one, or
 *       the {@code n}-th as listed by {@code ./gradlew islands}. The world is remembered (see
 *       {@link SavedMap}), and opened again next time when no world is given. With nothing
 *       saved, the Dwarven Mines in {@code maps/} opens.
 *   <li>{@code --demo} starts on the demo worlds, without opening the saved map.
 *   <li>{@code --script} runs a short scripted session on the terrain demo.
 *   <li>{@code --screenshot} saves the window to a PNG and exits, for checking the window on a
 *       virtual display.
 * </ul>
 */
public final class EditorApp {
    private static final int CELL_PX = 32;
    /** The window's size, unless the screen is smaller. */
    private static final Dimension WINDOW = new Dimension(1400, 900);

    public static void main(String[] args) {
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("No display available. Run this on a desktop, or under xvfb-run.");
            return;
        }
        boolean script = false;
        boolean demo = false;
        String screenshot = null;
        String worldDir = null;
        int islandIndex = 1;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--script" -> script = true;
                case "--demo" -> demo = true;
                case "--screenshot" -> screenshot = i + 1 < args.length ? args[++i] : null;
                case "--world" -> worldDir = i + 1 < args.length ? args[++i] : null;
                case "--island" -> {
                    String n = i + 1 < args.length ? args[++i] : "1";
                    try {
                        islandIndex = Integer.parseInt(n);
                    } catch (NumberFormatException e) {
                        System.out.println("--island needs a number, not " + n + "; using 1");
                    }
                }
                default -> System.out.println("Ignoring unknown option " + args[i]);
            }
        }
        WorldSource initial = null;
        Path saved = null;
        if (worldDir == null && !script && !demo) {
            saved = SavedMap.fallback().orElse(null);
            if (saved != null) {
                System.out.println("(--demo starts on the demo worlds instead)");
            }
        }
        if (worldDir != null) {
            try {
                Path dir = Path.of(worldDir);
                List<Island> islands = WorldImporter.scan(dir);
                if (islandIndex < 1 || islandIndex > islands.size()) {
                    System.out.println("No island " + islandIndex + "; the world has " + islands.size());
                    return;
                }
                initial = IslandSources.load(dir, islands.get(islandIndex - 1), true);
                SavedMap.save(dir);
            } catch (IOException | RuntimeException e) {
                System.out.println("Couldn't open " + worldDir + ": " + e.getMessage());
                return;
            }
        }
        boolean runScript = script;
        String shot = screenshot;
        WorldSource first = initial;
        Path savedMap = saved;
        int island = islandIndex;
        SwingUtilities.invokeLater(() -> open(runScript, shot, first, savedMap, island));
    }

    private static void open(boolean script, String screenshot, WorldSource initial,
            Path savedMap, int islandIndex) {
        EditorModel model = new EditorModel();
        JFrame[] frameRef = new JFrame[1];
        EditorPanel panel = new EditorPanel(model, CELL_PX);
        ControlsBar controls = new ControlsBar(model, () -> openWorld(frameRef[0], model),
                panel::fitToViewport);
        InspectorPanel inspector = new InspectorPanel(model);

        JPanel content = new JPanel(new BorderLayout());
        content.add(controls, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(panel);
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        scroll.getHorizontalScrollBar().setUnitIncrement(24);
        content.add(scroll, BorderLayout.CENTER);
        content.add(inspector, BorderLayout.EAST);
        bindKeys(content, controls, model, panel);

        JFrame frame = new JFrame("A* editor");
        frameRef[0] = frame;
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setContentPane(content);
        frame.pack();
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        frame.setSize(Math.min(Math.max(frame.getWidth(), WINDOW.width), screen.width),
                Math.min(Math.max(frame.getHeight(), WINDOW.height), screen.height));
        frame.setLocationByPlatform(true);
        frame.setVisible(true);

        if (initial != null) {
            model.loadWorld(initial);
        } else if (savedMap != null) {
            openSaved(frame, model, savedMap, islandIndex);
        }
        if (script) {
            runScript(model);
        }
        if (screenshot != null) {
            // Give the window a moment to lay out, then save it and exit.
            Timer t = new Timer(1500, e -> {
                save(frame, new File(screenshot));
                System.exit(0);
            });
            t.setRepeats(false);
            t.start();
        }
    }

    // The newest background load. An older one that finishes later, or one overtaken by the
    // user picking another world meanwhile, is dropped rather than replacing what they chose.
    private record Ticket(int worldsLoaded) {}
    private static Ticket latestLoad;

    private static Object startLoad(EditorModel model) {
        latestLoad = new Ticket(model.worldsLoaded());
        return latestLoad;
    }

    private static boolean stillWanted(EditorModel model, Object ticket) {
        return ticket == latestLoad && latestLoad.worldsLoaded() == model.worldsLoaded();
    }

    /**
     * Opens an island of the saved (or bundled) map in the background; the demo shows until
     * it's ready.
     */
    private static void openSaved(JFrame frame, EditorModel model, Path dir, int islandIndex) {
        frame.setTitle("A* editor: opening " + dir.getFileName() + "...");
        frame.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        boolean diagonal = model.diagonal(); // read here, on the Swing thread
        Object ticket = startLoad(model);
        new SwingWorker<WorldSource, Void>() {
            @Override
            protected WorldSource doInBackground() throws IOException {
                List<Island> islands = WorldImporter.scan(dir);
                if (islandIndex < 1 || islandIndex > islands.size()) {
                    throw new IOException("there's no island " + islandIndex + "; the world has "
                            + islands.size());
                }
                return IslandSources.load(dir, islands.get(islandIndex - 1), diagonal);
            }

            @Override
            protected void done() {
                frame.setCursor(Cursor.getDefaultCursor());
                frame.setTitle("A* editor");
                try {
                    WorldSource loaded = get();
                    if (stillWanted(model, ticket)) {
                        model.loadWorld(loaded);
                    }
                } catch (Exception e) {
                    showError(frame, "Couldn't open the map " + dir, e);
                }
            }
        }.execute();
    }

    /**
     * Asks for a world folder, scans it for islands off the Swing thread, lets the user pick
     * one if there are several, then loads it. The world becomes the saved map.
     */
    private static void openWorld(JFrame frame, EditorModel model) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Open a Minecraft world: its folder (with level.dat) or a .zip of it");
        chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setFileFilter(new FileNameExtensionFilter("World folders and .zip files", "zip"));
        if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path dir = chooser.getSelectedFile().toPath();
        frame.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        new SwingWorker<List<Island>, Void>() {
            @Override
            protected List<Island> doInBackground() throws IOException {
                return WorldImporter.scan(dir);
            }

            @Override
            protected void done() {
                frame.setCursor(Cursor.getDefaultCursor());
                List<Island> islands;
                try {
                    islands = get();
                } catch (Exception e) {
                    showError(frame, "Couldn't read " + dir, e);
                    return;
                }
                if (islands.isEmpty()) {
                    JOptionPane.showMessageDialog(frame, "No blocks found in " + dir);
                    return;
                }
                Island pick = islands.size() == 1 ? islands.get(0)
                        : (Island) JOptionPane.showInputDialog(frame,
                                "This world has " + islands.size() + " islands. Which one?",
                                "Choose an island", JOptionPane.QUESTION_MESSAGE, null,
                                islands.toArray(), islands.get(0));
                if (pick != null) {
                    SavedMap.save(dir);
                    loadIsland(frame, model, dir, pick);
                }
            }
        }.execute();
    }

    private static void loadIsland(JFrame frame, EditorModel model, Path dir, Island island) {
        frame.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        boolean diagonal = model.diagonal(); // read here, on the Swing thread
        Object ticket = startLoad(model);
        new SwingWorker<WorldSource, Void>() {
            @Override
            protected WorldSource doInBackground() throws IOException {
                return IslandSources.load(dir, island, diagonal);
            }

            @Override
            protected void done() {
                frame.setCursor(Cursor.getDefaultCursor());
                try {
                    WorldSource loaded = get();
                    if (stillWanted(model, ticket)) {
                        model.loadWorld(loaded);
                    }
                } catch (Exception e) {
                    showError(frame, "Couldn't load " + island, e);
                }
            }
        }.execute();
    }

    private static void showError(JFrame frame, String what, Exception e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        JOptionPane.showMessageDialog(frame, what + ":\n" + cause.getMessage(), "Error",
                JOptionPane.ERROR_MESSAGE);
    }

    private static void bindKeys(JComponent root, ControlsBar controls, EditorModel model,
            EditorPanel panel) {
        bind(root, "SPACE", controls.playPause);
        bind(root, "LEFT", controls.back);
        bind(root, "RIGHT", controls.forward);
        bind(root, "HOME", controls.first);
        bind(root, "END", controls.last);
        Action in = run(model::zoomIn);
        Action out = run(model::zoomOut);
        bind(root, "ctrl EQUALS", in);
        bind(root, "ctrl ADD", in);
        bind(root, "ctrl MINUS", out);
        bind(root, "ctrl SUBTRACT", out);
        bind(root, "ctrl 0", run(panel::fitToViewport));
    }

    private static Action run(Runnable r) {
        return new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                r.run();
            }
        };
    }

    private static void bind(JComponent root, String key, Action action) {
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key), key);
        root.getActionMap().put(key, action);
    }

    /**
     * A short session through the model: block part of the plateau and move the goal, then show
     * the finished search with its smoothed route, hovering a waypoint.
     */
    private static void runScript(EditorModel model) {
        if (model.source().imported()) {
            // An imported island: just show the finished route with a waypoint hovered.
            model.last();
            if (!model.waypoints().isEmpty()) {
                model.setHovered(model.waypoints().get(model.waypoints().size() / 2).pos());
            }
            return;
        }
        model.setTool(EditorModel.Tool.SOLID);
        model.beginDrag(new BlockPoint(6, 5, 3));
        model.dragTo(new BlockPoint(7, 5, 3));
        model.endDrag(new BlockPoint(7, 5, 3));
        model.beginDrag(model.goal());
        model.endDrag(new BlockPoint(11, 1, 1));
        model.last();
        model.setHovered(model.waypoints().get(model.waypoints().size() / 2).pos());
    }

    private static void save(JFrame frame, File file) {
        try {
            JComponent root = frame.getRootPane();
            BufferedImage img = new BufferedImage(root.getWidth(), root.getHeight(),
                    BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            root.paint(g);
            g.dispose();
            File parent = file.getAbsoluteFile().getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            ImageIO.write(img, "png", file);
            System.out.println("wrote " + file.getAbsolutePath());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private EditorApp() {}
}
