package astar.viz;

import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/** A plain Swing window that shows a rendered image. */
public final class VisualizerWindow {
    private VisualizerWindow() {}

    /** True when a display is available to open windows on. */
    public static boolean canShow() {
        return !GraphicsEnvironment.isHeadless();
    }

    public static void show(String title, BufferedImage image, int offset) {
        SwingUtilities.invokeLater(() -> {
            JPanel panel = new JPanel() {
                @Override
                protected void paintComponent(Graphics g) {
                    super.paintComponent(g);
                    g.drawImage(image, 0, 0, null);
                }
            };
            panel.setPreferredSize(new Dimension(image.getWidth(), image.getHeight()));

            JFrame frame = new JFrame(title);
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.setContentPane(panel);
            frame.setResizable(false);
            frame.pack();
            frame.setLocation(80 + offset, 80 + offset);
            frame.setVisible(true);
        });
    }
}
