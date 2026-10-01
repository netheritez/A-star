package astar.client.draw;

import java.util.List;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Draws with the game's own debug gizmos: flat lines and boxes, no shaders. Gizmos only go
 * anywhere while a collector is open, so call these inside
 * {@code LevelRenderer.collectPerFrameRenderThreadGizmos()}.
 */
public final class GizmoRouteView implements RouteView {

    private OverlayColour colour = OverlayColour.CYAN;
    /** Half the lead marker's width, in blocks. */
    private static final double LEAD_HALF = 0.12;
    /** How far above the floor the line runs, so it isn't hidden in slabs and carpets. */
    private static final double LIFT = 0.08;

    @Override
    public void colour(OverlayColour colour) {
        this.colour = colour;
    }

    @Override
    public void route(List<Vec3> points) {
        for (int i = 1; i < points.size(); i++) {
            Gizmos.line(points.get(i - 1).add(0, LIFT, 0), points.get(i).add(0, LIFT, 0),
                    colour.line, 4F);
        }
    }

    @Override
    public void keyNode(AABB floor) {
        // A hair bigger than the block, so the fill doesn't flicker against its faces.
        Gizmos.cuboid(floor.inflate(0.004),
                GizmoStyle.strokeAndFill(colour.line, 2F, colour.fill));
    }

    @Override
    public void lead(Vec3 at) {
        Vec3 c = at.add(0, LIFT, 0);
        AABB box = new AABB(c.subtract(LEAD_HALF, LEAD_HALF, LEAD_HALF),
                c.add(LEAD_HALF, LEAD_HALF, LEAD_HALF));
        Gizmos.cuboid(box, GizmoStyle.strokeAndFill(colour.edge, 1.5F, colour.lead));
    }

    @Override
    public void teleport(List<Vec3> points, boolean etherwarp) {
        // In the route's own colour, like the walking: the labels say which teleport it is.
        int c = colour.line;
        for (int i = 1; i < points.size() - 1; i++) {
            Gizmos.line(points.get(i - 1), points.get(i), c, 3F).setAlwaysOnTop();
        }
        // Seen through walls: a teleport goes where walking can't, and is planned ahead.
        if (points.size() >= 2) {
            Gizmos.arrow(points.get(points.size() - 2), points.get(points.size() - 1), c, 3F)
                    .setAlwaysOnTop();
        }
    }

    @Override
    public void castPoint(Vec3 at) {
        AABB box = new AABB(at.subtract(0.15, 0.15, 0.15), at.add(0.15, 0.15, 0.15));
        Gizmos.cuboid(box, GizmoStyle.strokeAndFill(colour.edge, 1.5F, colour.lead))
                .setAlwaysOnTop();
    }

    @Override
    public void landing(AABB floor, boolean etherwarp) {
        Gizmos.cuboid(floor.inflate(0.004),
                GizmoStyle.strokeAndFill(colour.line, 2F, colour.fill));
    }

    @Override
    public void label(Vec3 at, String text) {
        Gizmos.billboardText(text, at, TextGizmo.Style.whiteAndCentered());
    }
}
