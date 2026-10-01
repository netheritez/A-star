package astar.client;

import astar.client.draw.GizmoRouteView;
import astar.client.draw.OverlayColour;
import astar.client.draw.RouteView;
import astar.core.PathStep;
import astar.movement.exec.Journey;
import astar.pathing.PathAnalysis;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Draws the {@code /goto} route in the world while it's being walked: a line through every
 * node from start to goal, the block underfoot at each key node (a turn, or where a jump or
 * drop starts or ends) highlighted, and a small marker riding the line a few blocks ahead of
 * the player, in view in first person, so the player is seen following it.
 *
 * <p>On a trip with teleports, the rest of the trip too: the walks still to come, and each
 * teleport not yet cast as an arrow from where it's cast to where it lands (magenta for an
 * etherwarp, orange for an Instant Transmission, with a mark at each cast of an air chain),
 * the block it lands on and a numbered label.
 *
 * <p>Drawn once a frame, so the marker glides between game ticks. Everything goes through a
 * {@link RouteView}; only {@link GizmoRouteView} knows how.
 */
final class RouteOverlay {

    private final Supplier<Navigator> navigator;
    private final RouteView view = new GizmoRouteView();
    /** The path the lists below were made from, so they're only rebuilt after a reroute. */
    private List<PathStep> drawn;
    private final List<Vec3> line = new ArrayList<>();
    private final List<AABB> keys = new ArrayList<>();
    private boolean shown = true;
    private OverlayColour colour = OverlayColour.CYAN;

    RouteOverlay(Supplier<Navigator> navigator) {
        this.navigator = navigator;
        try {
            OverlayColour saved = OverlayColour.named(Files.readString(colourFile()).strip());
            if (saved != null) {
                colour = saved;
            }
        } catch (IOException | RuntimeException e) {
            // None chosen yet.
        }
        view.colour(colour);
    }

    /** Where the chosen colour is kept between games. */
    private static Path colourFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("astar-overlay-colour.txt");
    }

    OverlayColour colour() {
        return colour;
    }

    /** Draws everything in this colour from now on (and in later games), and shows it. */
    void colour(OverlayColour colour) {
        this.colour = colour;
        view.colour(colour);
        shown = true;
        try {
            Files.writeString(colourFile(), colour.id());
        } catch (IOException e) {
            // Kept for this game only.
        }
    }

    void register() {
        LevelRenderEvents.END_EXTRACTION.register(this::draw);
    }

    /** Turns the overlay on or off; returns whether it's now on. */
    boolean toggle() {
        shown = !shown;
        return shown;
    }

    private void draw(LevelExtractionContext ctx) {
        Navigator nav = navigator.get();
        Journey journey = nav == null ? null : nav.journey();
        Navigator.Trip trip = nav == null ? null : nav.trip();
        if (!shown || journey == null && trip == null) {
            drawn = null;
            tripDrawn = null;
            return;
        }
        if (journey != null && journey.path() != drawn) {
            rebuild(journey, nav.origin());
        }
        if (trip == null) {
            tripDrawn = null;
        } else if (tripDrawn == null || trip.hops() != tripDrawn.hops()
                || trip.leg() != tripDrawn.leg()) {
            rebuildTrip(trip, nav.origin());
        }
        Vec3 lead = nav.lead(ctx.deltaTracker().getGameTimeDeltaPartialTick(false));
        // Gizmos only land while a collector is open; this is the one drawn this frame.
        try (Gizmos.TemporaryCollection c = Minecraft.getInstance().levelRenderer
                .collectPerFrameRenderThreadGizmos()) {
            if (journey != null) {
                view.route(line);
                keys.forEach(view::keyNode);
            }
            if (trip != null) {
                walks.forEach(view::route);
                for (Label l : walkLabels) {
                    view.label(l.at(), l.text());
                }
                for (Hop h : teleports) {
                    view.teleport(h.points(), h.etherwarp());
                    h.casts().forEach(view::castPoint);
                    view.landing(h.landing(), h.etherwarp());
                    view.label(h.labelAt(), h.label());
                }
            }
            if (lead != null && journey != null) {
                view.lead(lead);
            }
        }
    }

    /** A teleport of the trip, ready to draw. */
    private record Hop(List<Vec3> points, List<Vec3> casts, AABB landing, boolean etherwarp,
            Vec3 labelAt, String label) {}

    /** The trip the lists below were made from. */
    private Navigator.Trip tripDrawn;
    /** The walks still to come after the one under way, cell to cell. */
    private final List<List<Vec3>> walks = new ArrayList<>();
    /** The teleports still to come. */
    private final List<Hop> teleports = new ArrayList<>();
    /** Where each of those walks starts, numbered with the teleports. */
    private final List<Label> walkLabels = new ArrayList<>();

    private record Label(Vec3 at, String text) {}

    /**
     * The rest of a trip with teleports: every walk after the one under way (that one is drawn
     * as it's walked), and every teleport not yet cast. Walks and teleports are numbered
     * together in the order they're made, from the first of the trip.
     */
    private void rebuildTrip(Navigator.Trip trip, BlockPos o) {
        tripDrawn = trip;
        walks.clear();
        teleports.clear();
        walkLabels.clear();
        // Step numbers: leg k (if it walks at all), then hop k, and so on.
        int[] hopNumber = new int[trip.hops().size()];
        int number = 0;
        for (int k = 0; k < trip.legs().size(); k++) {
            Journey.Prepared l = trip.legs().get(k);
            List<Vec3> pts = new ArrayList<>();
            if (l != null) {
                for (PathStep s : l.path()) {
                    pts.add(new Vec3(s.pos().x() + 0.5 + o.getX(), s.pos().y() + o.getY(),
                            s.pos().z() + 0.5 + o.getZ()));
                }
            }
            // A step or two onto a cast spot isn't a walk of its own.
            double walked = length(pts);
            if (walked >= 1) {
                number++;
            }
            if (!pts.isEmpty() && k > trip.leg()) {
                walks.add(pts);
                if (walked >= 1) {
                    walkLabels.add(new Label(pts.get(0).add(0, 2.2, 0),
                            number + ". walk " + Math.round(walked) + " blocks"));
                }
            }
            if (k < hopNumber.length) {
                hopNumber[k] = ++number;
            }
        }
        Level level = Minecraft.getInstance().level;
        for (int k = trip.leg(); k < trip.hops().size(); k++) {
            Warps.Hop h = trip.hops().get(k);
            // Through the middle of the body, where the player is seen to go.
            List<Vec3> pts = new ArrayList<>();
            List<Vec3> casts = new ArrayList<>();
            pts.add(middle(h.from(), o));
            for (astar.core.BlockPoint b : h.through()) {
                Vec3 m = middle(b, o);
                pts.add(m);
                casts.add(m);
            }
            Vec3 end = middle(h.to(), o);
            if (!pts.get(pts.size() - 1).equals(end)) {
                pts.add(end);
            }
            AABB floor = floorUnder(level, new BlockPos(h.to().x() + o.getX(),
                    h.to().y() + o.getY(), h.to().z() + o.getZ()));
            String what = !h.transmit() ? "etherwarp"
                    : h.chain() > 1 ? "IT x" + h.chain() + " chained" : "IT";
            teleports.add(new Hop(pts, casts, floor, !h.transmit(), pts.get(0).add(0, 1.2, 0),
                    hopNumber[k] + ". " + what));
        }
    }

    /** How far a walk goes across the ground, in blocks. */
    private static double length(List<Vec3> pts) {
        double d = 0;
        for (int i = 1; i < pts.size(); i++) {
            d += Math.hypot(pts.get(i).x - pts.get(i - 1).x, pts.get(i).z - pts.get(i - 1).z);
        }
        return d;
    }

    private static Vec3 middle(astar.core.BlockPoint b, BlockPos o) {
        return new Vec3(b.x() + 0.5 + o.getX(), b.y() + 1.0 + o.getY(), b.z() + 0.5 + o.getZ());
    }

    /**
     * The box of the block the player stands on in a path cell: the cell's own block when it's
     * a slab, stair or other block at least half high that the feet stand in, else the block
     * below (so a carpet counts as the block it lies on).
     */
    private static AABB floorUnder(Level level, BlockPos cell) {
        if (level != null) {
            VoxelShape own = level.getBlockState(cell).getCollisionShape(level, cell);
            if (!own.isEmpty() && own.max(Direction.Axis.Y) >= 0.5) {
                return own.bounds().move(cell);
            }
            BlockPos below = cell.below();
            VoxelShape under = level.getBlockState(below).getCollisionShape(level, below);
            if (!under.isEmpty()) {
                return under.bounds().move(below);
            }
        }
        return new AABB(cell.below());
    }

    private void rebuild(Journey journey, BlockPos o) {
        drawn = journey.path();
        line.clear();
        // As it's walked: around each rounded corner along its curve.
        for (double[] p : journey.plan().track()) {
            line.add(new Vec3(p[0] + o.getX(), p[1] + o.getY(), p[2] + o.getZ()));
        }
        keys.clear();
        Level level = Minecraft.getInstance().level;
        for (PathStep s : PathAnalysis.keyNodes(drawn)) {
            keys.add(floorUnder(level, new BlockPos(s.pos().x() + o.getX(),
                    s.pos().y() + o.getY(), s.pos().z() + o.getZ())));
        }
    }
}
