package astar.viz;

import astar.core.TurnPenalty;
import astar.pathing.TerrainCosts;
import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import javax.swing.Timer;

/**
 * Two rows of controls: playback, search settings and editing tools on top; the view, zoom and
 * world below.
 */
public final class ControlsBar extends JPanel {
    private static final String ALL_LEVELS = "Levels searched";
    private static final String ABOVE = "Whole map from above";
    private static final String ABOVE_COLOURED = "From above, coloured by height";

    private final EditorModel model;
    private final JToolBar top = new JToolBar();
    private final JToolBar bottom = new JToolBar();
    private final Timer timer;
    private final JButton play = new JButton();
    private final JLabel stepLabel = new JLabel();
    private final JCheckBox diagonal = new JCheckBox("8-way");
    private final JCheckBox smoothed = new JCheckBox("Smoothed");
    private final JCheckBox nodes = new JCheckBox("Nodes");
    private final JCheckBox exactTurns = new JCheckBox("Exact");
    private final JSpinner walkCost = spinner(new SpinnerNumberModel(1.0, 0.05, 20.0, 0.05));
    private final JSpinner diagonalCost = spinner(new SpinnerNumberModel(1.414, 0.05, 20.0, 0.05));
    private final JSpinner weight = spinner(new SpinnerNumberModel(1.0, 1.0, 20.0, 0.25));
    private final JSpinner turn = spinner(new SpinnerNumberModel(
            TurnPenalty.DEFAULT_PER_TURN, 0.0, 20.0, 0.05));
    private final JSpinner wall = spinner(new SpinnerNumberModel(
            TerrainCosts.WALL, 0.0, 5.0, 0.05));
    private final JComboBox<EditorModel.HeuristicChoice> heuristic =
            new JComboBox<>(EditorModel.HeuristicChoice.values());
    private final JComboBox<String> view = new JComboBox<>();
    /** The speed slider is logarithmic: position p gives 10^(p / 100) steps per second. */
    private static final int SPEED_MAX = 400; // 10,000 steps per second
    private static final int FRAME_MS = 16;

    private final JComboBox<WorldSource> worlds = new JComboBox<>();
    private final JComboBox<String> zoom = new JComboBox<>();
    private final JLabel speedLabel = new JLabel();
    private List<WorldSource> knownWorlds = List.of();
    private double stepsPerSecond;
    private double owedSteps;
    private final List<JToggleButton> tools = new ArrayList<>();
    private int viewLevels = -1;
    private boolean updating;

    final Action playPause;
    final Action first;
    final Action back;
    final Action forward;
    final Action last;

    public ControlsBar(EditorModel model) {
        this(model, null, null);
    }

    /**
     * @param openWorld runs when "Open world..." is pressed; null hides the button
     * @param fit runs when "Fit" is pressed; null hides the button
     */
    public ControlsBar(EditorModel model, Runnable openWorld, Runnable fit) {
        super(new GridLayout(2, 1));
        this.model = model;
        top.setFloatable(false);
        bottom.setFloatable(false);
        add(top);
        add(bottom);
        playPause = action("Play", () -> model.setPlaying(!model.playing()));
        first = action("|◀", model::first);
        back = action("◀", model::back);
        forward = action("▶", model::forward);
        last = action("▶|", model::last);
        first.putValue(Action.SHORT_DESCRIPTION, "First step (Home)");
        back.putValue(Action.SHORT_DESCRIPTION, "Step back (←)");
        playPause.putValue(Action.SHORT_DESCRIPTION, "Play / pause (Space)");
        forward.putValue(Action.SHORT_DESCRIPTION, "Step forward (→)");
        last.putValue(Action.SHORT_DESCRIPTION, "Last step (End)");

        JSlider speed = new JSlider(0, SPEED_MAX, 108); // about 12 steps per second
        speed.setToolTipText("Playback speed: 1 to 10,000 steps per second");
        setSpeed(speed.getValue());
        speed.addChangeListener(e -> setSpeed(speed.getValue()));
        // A fixed frame rate; at high speeds each frame advances several steps.
        timer = new Timer(FRAME_MS, e -> {
            owedSteps += stepsPerSecond * FRAME_MS / 1000.0;
            int steps = (int) owedSteps;
            if (steps > 0) {
                owedSteps -= steps;
                model.tick(steps);
            }
        });

        play.setAction(playPause);
        top.add(new JButton(first));
        top.add(new JButton(back));
        top.add(play);
        top.add(new JButton(forward));
        top.add(new JButton(last));
        top.add(stepLabel);
        top.addSeparator();
        top.add(new JLabel("Speed "));
        top.add(speed);
        top.add(speedLabel);
        top.addSeparator();

        diagonal.addActionListener(e -> {
            if (!updating) {
                model.setDiagonal(diagonal.isSelected());
            }
        });
        heuristic.addActionListener(e -> {
            if (!updating) {
                model.setHeuristic((EditorModel.HeuristicChoice) heuristic.getSelectedItem());
            }
        });
        smoothed.setToolTipText("Show the smoothed route (straight segments between waypoints)");
        smoothed.addActionListener(e -> {
            if (!updating) {
                model.setShowSmoothed(smoothed.isSelected());
            }
        });
        nodes.setToolTipText("Show every path node (dots) and the key nodes (rings: where the"
                + " direction or the kind of move changes) on the map from above");
        nodes.addActionListener(e -> {
            if (!updating) {
                model.setShowNodes(nodes.isSelected());
            }
        });
        walkCost.setToolTipText("Cost of one block straight along x or z");
        diagonalCost.setToolTipText("Cost of one block diagonally (1.414 is its true length)");
        weight.setToolTipText("Heuristic weight: 1 finds the cheapest path; more searches fewer"
                + " nodes for a path that may cost more");
        javax.swing.event.ChangeListener costs = e -> {
            if (!updating) {
                model.setStepCosts(((Number) walkCost.getValue()).doubleValue(),
                        ((Number) diagonalCost.getValue()).doubleValue());
                model.setHeuristicWeight(((Number) weight.getValue()).doubleValue());
                model.setTurnCost(((Number) turn.getValue()).doubleValue());
                model.setWallCost(((Number) wall.getValue()).doubleValue());
            }
        };
        turn.setToolTipText("Cost added for every 45 degrees the path turns, so straight runs"
                + " (along the axes or the diagonals) beat zigzags. 0: no turn cost (default "
                + TurnPenalty.DEFAULT_PER_TURN + ")");
        turn.addChangeListener(costs);
        wall.setToolTipText("How much more a step against a wall or at a ledge costs (half that"
                + " one block in), so paths keep to the middle of the way. 0: as tight as can be"
                + " (default " + TerrainCosts.WALL + ")");
        wall.addChangeListener(costs);
        exactTurns.setToolTipText("Keep a node per heading, so the path is the cheapest counting"
                + " turns; landmarks then count turns too. Slower on most routes, so off by default:"
                + " turns are then charged from each cell's cheapest parent");
        exactTurns.addActionListener(e -> {
            if (!updating) {
                model.setExactTurns(exactTurns.isSelected());
            }
        });
        walkCost.addChangeListener(costs);
        diagonalCost.addChangeListener(costs);
        weight.addChangeListener(costs);
        top.add(diagonal);
        top.add(new JLabel(" Heuristic "));
        top.add(heuristic);
        top.add(smoothed);
        top.add(nodes);
        top.addSeparator();

        ButtonGroup group = new ButtonGroup();
        for (EditorModel.Tool t : EditorModel.Tool.values()) {
            JToggleButton b = new JToggleButton(t.toString());
            b.addActionListener(e -> model.setTool(t));
            group.add(b);
            tools.add(b);
            top.add(b);
        }

        view.setToolTipText("See the whole map from above, or one panel per level");
        view.addActionListener(e -> {
            if (!updating && view.getSelectedItem() != null) {
                String item = (String) view.getSelectedItem();
                switch (item) {
                    case ABOVE -> model.setAbove(HeightMap.Style.GREY);
                    case ABOVE_COLOURED -> model.setAbove(HeightMap.Style.HEIGHT);
                    case ALL_LEVELS -> model.setView(null);
                    default -> model.setView(Integer.parseInt(item.substring("y = ".length())));
                }
            }
        });
        bottom.add(new JLabel("View "));
        bottom.add(view);
        bottom.addSeparator();

        for (double z : EditorModel.ZOOMS) {
            zoom.addItem(zoomLabel(z));
        }
        zoom.setToolTipText("Zoom: pixels per block. Ctrl + mouse wheel zooms too.");
        zoom.addActionListener(e -> {
            if (!updating && zoom.getSelectedIndex() >= 0) {
                model.setZoom(EditorModel.ZOOMS[zoom.getSelectedIndex()]);
            }
        });
        bottom.add(new JLabel(" Zoom "));
        bottom.add(zoom);
        if (fit != null) {
            JButton fitButton = new JButton(action("Fit", fit));
            fitButton.setToolTipText("Zoom so the whole view fits the window."
                    + " Drag with the right mouse button to move around.");
            bottom.add(fitButton);
        }
        bottom.addSeparator();

        worlds.addActionListener(e -> {
            if (!updating && worlds.getSelectedItem() != null) {
                model.loadWorld((WorldSource) worlds.getSelectedItem());
            }
        });
        bottom.add(worlds);
        if (openWorld != null) {
            JButton open = new JButton(action("Open world...", openWorld));
            open.setToolTipText("Import an island from a Minecraft 1.21 world save");
            bottom.add(open);
        }
        bottom.add(new JButton(action("Reset", model::reset)));
        bottom.addSeparator();
        bottom.add(new JLabel("Straight "));
        bottom.add(walkCost);
        bottom.add(new JLabel(" Diagonal "));
        bottom.add(diagonalCost);
        bottom.add(new JLabel(" Weight "));
        bottom.add(weight);
        bottom.add(new JLabel(" Turn "));
        bottom.add(turn);
        bottom.add(exactTurns);
        bottom.add(new JLabel(" Wall "));
        bottom.add(wall);
        fitWidth(zoom);

        model.addChangeListener(this::refresh);
        refresh();
    }

    private static JSpinner spinner(SpinnerNumberModel numbers) {
        JSpinner s = new JSpinner(numbers);
        s.setEditor(new JSpinner.NumberEditor(s, "0.0##"));
        s.setMaximumSize(s.getPreferredSize());
        return s;
    }

    /** Keeps a combo box as wide as its longest item, rather than stretching across the row. */
    private static void fitWidth(JComboBox<?> box) {
        box.setMaximumSize(null);
        box.setMaximumSize(box.getPreferredSize());
    }

    /** "1/8 px", "1/2 px", "3/4 px", "1 px", "1.5 px", "32 px"... */
    static String zoomLabel(double z) {
        if (z < 1) {
            int eighths = (int) Math.round(z * 8);
            int den = 8;
            while (eighths % 2 == 0 && den > 1) {
                eighths /= 2;
                den /= 2;
            }
            return eighths + "/" + den + " px";
        }
        return (z == Math.rint(z) ? String.valueOf((int) z) : String.valueOf(z)) + " px";
    }

    private void setSpeed(int position) {
        stepsPerSecond = Math.pow(10, position / 100.0);
        speedLabel.setText(String.format(" %,d/s ", Math.round(stepsPerSecond)));
    }

    private static Action action(String name, Runnable r) {
        return new AbstractAction(name) {
            @Override
            public void actionPerformed(ActionEvent e) {
                r.run();
            }
        };
    }

    /** Brings every control in line with the model, without firing their listeners. */
    void refresh() {
        updating = true;
        try {
            playPause.putValue(Action.NAME, model.playing() ? "Pause" : "Play");
            stepLabel.setText(String.format("  step %d / %d  ", model.step(), model.lastStep()));
            diagonal.setSelected(model.diagonal());
            smoothed.setSelected(model.showSmoothed());
            nodes.setSelected(model.showNodes());
            walkCost.setValue(model.walkCost());
            diagonalCost.setValue(model.diagonalCost());
            weight.setValue(model.heuristicWeight());
            turn.setValue(model.turnCost());
            wall.setValue(model.wallCost());
            exactTurns.setSelected(model.exactTurns());
            heuristic.setSelectedItem(model.heuristic());
            if (!model.worlds().equals(knownWorlds)) { // added, or an island reopened
                knownWorlds = List.copyOf(model.worlds());
                worlds.removeAllItems();
                model.worlds().forEach(worlds::addItem);
                fitWidth(worlds);
            }
            worlds.setSelectedItem(model.source());
            zoom.setSelectedItem(zoomLabel(model.zoom()));
            tools.get(model.tool().ordinal()).setSelected(true);

            int levels = model.world().sizeY();
            if (levels != viewLevels) {
                viewLevels = levels;
                view.removeAllItems();
                view.addItem(ABOVE);
                view.addItem(ABOVE_COLOURED);
                view.addItem(ALL_LEVELS);
                for (int y = 0; y < levels; y++) {
                    view.addItem("y = " + y);
                }
                fitWidth(view);
            }
            view.setSelectedItem(model.above() == HeightMap.Style.GREY ? ABOVE
                    : model.above() == HeightMap.Style.HEIGHT ? ABOVE_COLOURED
                    : model.viewLevel() == null ? ALL_LEVELS : "y = " + model.viewLevel());
        } finally {
            updating = false;
        }

        if (model.playing() && !timer.isRunning()) {
            owedSteps = 0;
            timer.start();
        } else if (!model.playing() && timer.isRunning()) {
            timer.stop();
        }
    }
}
