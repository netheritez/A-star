package astar.viz;

import astar.core.MoveType;
import astar.core.NodeView;
import astar.pathing.PathAnalysis;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JTextArea;

/** Shows the hovered cell's block and search values at the current step, plus the status. */
public final class InspectorPanel extends JPanel {
    private static final String HINTS = """
            Editing
              Click or drag to paint the
              selected tool at that level.
              A 2-high wall needs the feet
              level and the one above.
              Drag S or G to move them
              (from above too: they land
              on the highest floor).

            Moving around
              Ctrl+wheel  zoom
              Right-drag  pan
              Ctrl+0      fit the window

            Keys
              Space  play / pause
              ← →    step back / forward
              Home   first step
              End    last step""";

    private final EditorModel model;
    private final JTextArea cell = area();
    private final JTextArea status = area();

    public InspectorPanel(EditorModel model) {
        super(new BorderLayout(0, 12));
        this.model = model;
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        setPreferredSize(new Dimension(260, 400));

        JTextArea hints = area();
        hints.setText(HINTS);
        add(cell, BorderLayout.NORTH);
        add(status, BorderLayout.CENTER);
        add(hints, BorderLayout.SOUTH);

        model.addChangeListener(this::refresh);
        refresh();
    }

    private static JTextArea area() {
        JTextArea a = new JTextArea();
        a.setEditable(false);
        a.setFocusable(false);
        a.setOpaque(false);
        a.setLineWrap(true);
        a.setWrapStyleWord(true);
        a.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        return a;
    }

    void refresh() {
        cell.setText(describe(model.inspect(model.hovered()), model.step()));
        status.setText("Status\n  " + model.status());
    }

    /** A height within the cell, like "+0.5" or "+1/16"; "+0" on a full block. */
    static String elevation(double offset) {
        int sixteenths = (int) Math.round(offset * 16);
        if (sixteenths % 8 == 0) {
            return "+" + (sixteenths / 16.0 == Math.rint(sixteenths / 16.0)
                    ? String.valueOf(sixteenths / 16) : String.valueOf(sixteenths / 16.0));
        }
        return "+" + sixteenths + "/16";
    }

    static String describe(EditorModel.Inspection in, int step) {
        if (in == null) {
            return "Cell\n  Hover a cell to inspect it.";
        }
        StringBuilder sb = new StringBuilder("Cell " + in.cell() + "\n");
        if (in.world() != null) {
            sb.append("  world:  ").append(in.world()).append('\n');
        }
        sb.append("  here:   ").append(in.here()).append('\n');
        sb.append("  below:  ").append(in.below()).append('\n');
        sb.append("  stand:  ").append(in.standable()
                ? String.format("yes, feet at %s", elevation(in.elevation() - in.cell().y()))
                : "no").append('\n');
        sb.append("\nAt step ").append(step).append('\n');
        NodeView n = in.node();
        if (n == null) {
            sb.append("  not reached yet");
        } else {
            sb.append(String.format("  g:      %.3f%n", n.g()));
            sb.append(String.format("  h:      %.3f%n", n.h()));
            sb.append(String.format("  f:      %.3f%n", n.f()));
            sb.append("  parent: ").append(n.parent() == null ? "none (start)" : n.parent())
                    .append('\n');
            sb.append("  via:    ").append(n.via() == null ? "start"
                    : n.parent() != null && PathAnalysis.isDescent(n.parent(), n.pos(), n.via())
                            ? "DESCENT (1 down)"
                            : n.via() == MoveType.DROP ? "DROP (" + (n.parent().y() - n.pos().y())
                                    + " down)" : n.via());
        }
        return sb.toString();
    }
}
