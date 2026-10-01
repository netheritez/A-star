package astar.viz;

import astar.core.BlockPoint;
import astar.core.DefaultCostModel;
import astar.core.Heuristic;
import astar.core.Heuristics;
import astar.core.NodeView;
import astar.core.PathStep;
import astar.core.TurnPenalty;
import astar.core.SearchResult;
import astar.pathing.ArrayBlockView;
import astar.pathing.AutoLandmarks;
import astar.pathing.BlockType;
import astar.pathing.EntityProfile;
import astar.pathing.PathAnalysis;
import astar.pathing.TerrainCosts;
import astar.pathing.Waypoint;
import astar.pathing.WorldPathfinder;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * Everything the interactive editor does, with no Swing in it, so it can be tested headless.
 * The Swing classes turn mouse and button input into calls here, and repaint on change.
 *
 * <p>Every edit re-runs the search and records it, so the view can replay it step by step.
 * While dragging a marker or a paint stroke, the search re-runs at each cell only if the last
 * one was quick (see {@link #LIVE_SEARCH_MS}); on a big map it waits until the mouse is let go,
 * so the window doesn't freeze while dragging.
 */
public final class EditorModel {

    public enum HeuristicChoice {
        LANDMARKS("Landmarks (knows the map)"),
        DEFAULT("Flat (octile / Manhattan)"),
        DIJKSTRA("None (Dijkstra)");

        private final String label;

        HeuristicChoice(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** What painting a cell does: set the block at that exact level. */
    public enum Tool {
        SOLID(BlockType.SOLID, "Solid"),
        AIR(BlockType.AIR, "Air"),
        HAZARD(BlockType.HAZARD, "Lava"),
        SLAB(BlockType.PARTIAL_8, "Slab"),
        STAIRS(BlockType.STAIRS, "Stairs"),
        FENCE(BlockType.TALL, "Fence"),
        WATER(BlockType.WATER, "Water"),
        LADDER(BlockType.CLIMBABLE, "Ladder"),
        DOOR(BlockType.DOOR, "Door"),
        SOUL_SAND(BlockType.SOUL_SAND, "Soul sand"),
        COBWEB(BlockType.COBWEB, "Cobweb"),
        MAGMA(BlockType.MAGMA, "Magma");

        private final BlockType block;
        private final String label;

        Tool(BlockType block, String label) {
            this.block = block;
            this.label = label;
        }

        public BlockType block() {
            return block;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public enum DemoWorld {
        TERRAIN("Terrain demo", DemoWorlds::terrain, DemoWorlds.TERRAIN_START,
                DemoWorlds.TERRAIN_GOAL),
        FLAT("Flat demo", DemoWorlds::flat, DemoWorlds.FLAT_START, DemoWorlds.FLAT_GOAL),
        HEIGHTS("Heights demo", DemoWorlds::heights, DemoWorlds.HEIGHTS_START,
                DemoWorlds.HEIGHTS_GOAL);

        private final String label;
        private final Supplier<ArrayBlockView> factory;
        private final BlockPoint start;
        private final BlockPoint goal;

        DemoWorld(String label, Supplier<ArrayBlockView> factory, BlockPoint start,
                BlockPoint goal) {
            this.label = label;
            this.factory = factory;
            this.start = start;
            this.goal = goal;
        }

        @Override
        public String toString() {
            return label;
        }

        public WorldSource source() {
            return new WorldSource(label, factory, start, goal, WorldSource.NO_OFFSET);
        }
    }

    /**
     * What the inspector shows for one cell. {@code elevation} is the exact height the feet
     * rest at, relative to the bottom of the map (NaN if it can't stand there). {@code node} is
     * null if not reached yet;
     * {@code world} is the in-game position for imported maps, otherwise null.
     */
    public record Inspection(BlockPoint cell, BlockType here, BlockType below, boolean standable,
            double elevation, NodeView node, BlockPoint world) {}

    private enum Drag { NONE, START, GOAL, PAINT }

    private final List<Runnable> listeners = new ArrayList<>();

    private final List<WorldSource> worlds = new ArrayList<>();
    private WorldSource source;
    private int worldsLoaded; // counts loadWorld calls, so a slow background load can tell it's stale
    private ArrayBlockView world;
    private double zoom = 32;
    private HeightMap.Style above; // null: level panels
    private HeightMap heightMap; // null: not computed since the last edit
    private BlockPoint start;
    private BlockPoint goal;
    private boolean diagonal = true;
    private AutoLandmarks landmarks;
    private Object[] landmarksKey;
    private HeuristicChoice heuristic = HeuristicChoice.LANDMARKS;
    private double walkCost = DefaultCostModel.DEFAULT.walk();
    private double diagonalCost = DefaultCostModel.DEFAULT.diagonal();
    private double heuristicWeight = 1;
    private double turnCost = TurnPenalty.DEFAULT_PER_TURN;
    private double wallCost = TerrainCosts.WALL;
    private boolean showNodes = true;
    private boolean exactTurns = false;
    private Tool tool = Tool.SOLID;
    private Integer viewLevel; // null: the levels the search reached

    /**
     * The most pixels the level panels may take (about 128 MB as an image). Zooming in on many
     * levels of a big map would otherwise need gigabytes; the zoom is capped instead.
     */
    static final long MAX_LEVEL_PIXELS = 32L << 20;

    /** The zoom steps, in pixels per block: from 8 blocks per pixel up to 64 pixels a block. */
    public static final double[] ZOOMS = {0.125, 0.25, 0.5, 0.75, 1, 1.5, 2, 3, 4, 6, 8, 12,
        16, 24, 32, 40, 64};

    private Recording recording; // null: the search was refused
    private SearchResult result = SearchResult.noPath();
    private List<Waypoint> waypoints = List.of();
    private boolean showSmoothed = true;
    private String status = "";
    private int step;
    private boolean playing;
    private BlockPoint hovered;

    /** Searches slower than this don't re-run during a drag, only when it ends. */
    public static final double LIVE_SEARCH_MS = 40;

    private Drag drag = Drag.NONE;
    private BlockPoint lastPainted;
    private double lastSearchMs;
    private double liveSearchMs = LIVE_SEARCH_MS;
    private boolean searchPending; // a drag changed things; the search runs when it ends
    private FrameState cachedFrame;
    private WorldPathfinder inspector;
    private Object[] inspectorKey;
    private Recording layersFor; // the recording cachedLayers were worked out from
    private List<Integer> cachedLayers;

    public EditorModel() {
        this(DemoWorld.TERRAIN);
    }

    public EditorModel(DemoWorld demo) {
        for (DemoWorld d : DemoWorld.values()) {
            worlds.add(d.source());
        }
        load(worlds.get(demo.ordinal()));
    }

    /** How many times {@link #loadWorld} has been called: changes whenever the user picks a world. */
    public int worldsLoaded() {
        return worldsLoaded;
    }

    public void addChangeListener(Runnable listener) {
        listeners.add(listener);
    }

    // ---- World and settings -------------------------------------------------------------

    public void loadWorld(DemoWorld demo) {
        loadWorld(worlds.get(demo.ordinal()));
    }

    /**
     * Loads a world, adding it to {@link #worlds()} if it's new. Imported maps open in the view
     * from above, zoomed to fit (the window refits them to its own size).
     */
    public void loadWorld(WorldSource source) {
        worldsLoaded++;
        // Opening the same island again replaces its entry, so the list doesn't grow a
        // duplicate that keeps the old copy of the world in memory.
        int same = -1;
        for (int i = 0; i < worlds.size(); i++) {
            WorldSource w = worlds.get(i);
            if (w == source || (source.imported() && w.name().equals(source.name())
                    && w.origin().equals(source.origin()))) {
                same = i;
            }
        }
        if (same < 0) {
            worlds.add(source);
        } else {
            worlds.set(same, source);
        }
        load(source);
        if (source.imported()) {
            above = HeightMap.Style.GREY;
            viewLevel = null;
            fitZoom(1150, 750);
        }
        fire();
    }

    /** Reloads the current world, discarding edits. */
    public void reset() {
        load(source);
        fire();
    }

    private void load(WorldSource source) {
        this.source = source;
        this.world = source.factory().get().copy();
        this.start = source.start();
        this.goal = source.goal();
        this.viewLevel = null;
        this.above = null;
        this.heightMap = null;
        this.hovered = null;
        this.playing = false;
        rerun();
    }

    /** Pixels per block in the view, from 1/8 (eight blocks a pixel) to 64. */
    public void setZoom(double zoom) {
        this.zoom = Math.max(ZOOMS[0], Math.min(ZOOMS[ZOOMS.length - 1], zoom));
        capLevelZoom();
        fire();
    }

    /** Lowers the zoom, if need be, so the level panels stay within {@link #MAX_LEVEL_PIXELS}. */
    private void capLevelZoom() {
        if (above != null || world == null) {
            return;
        }
        int levels = Math.max(1, layers().size());
        int columns = Math.min(GridRenderer.MAX_COLUMNS, levels);
        int rows = (levels + columns - 1) / columns;
        for (int i = ZOOMS.length - 1; i > 0; i--) {
            long px = Math.max(1, (long) Math.ceil(zoom)); // the panels render whole pixels
            if (ZOOMS[i] > zoom + 1e-9 || (long) columns * world.sizeX() * px * rows
                    * world.sizeZ() * px <= MAX_LEVEL_PIXELS) {
                continue;
            }
            zoom = ZOOMS[i - 1]; // too big at this step: one step down, and check again
        }
    }

    /** The next zoom step in (towards 64 pixels a block). */
    public void zoomIn() {
        for (double z : ZOOMS) {
            if (z > zoom + 1e-9) {
                setZoom(z);
                return;
            }
        }
    }

    /** The next zoom step out (towards 1/8 of a pixel a block). */
    public void zoomOut() {
        for (int i = ZOOMS.length - 1; i >= 0; i--) {
            if (ZOOMS[i] < zoom - 1e-9) {
                setZoom(ZOOMS[i]);
                return;
            }
        }
    }

    /**
     * Picks the largest zoom step (up to 32) at which the view fits in {@code width} by
     * {@code height} pixels: the whole map when seen from above, otherwise the width of the
     * level panels.
     */
    public void fitZoom(int width, int height) {
        double fit;
        if (above != null) {
            fit = Math.min((width - 2.0 * GridRenderer.MARGIN) / world.sizeX(),
                    (height - 2.0 * GridRenderer.MARGIN - RouteImages.LEGEND_H) / world.sizeZ());
        } else {
            this.zoom = fitLevelZoom(world.sizeX(), layers().size(), width);
            return;
        }
        this.zoom = step(fit);
    }

    /** The largest zoom step (up to 32) at which this many level panels fit {@code width}. */
    static double fitLevelZoom(int sizeX, int levels, int width) {
        int columns = Math.min(GridRenderer.MAX_COLUMNS, Math.max(1, levels));
        return step((width - 2.0 * GridRenderer.MARGIN - (columns - 1) * GridRenderer.PANEL_GAP)
                / Math.max(1, columns * sizeX));
    }

    private static double step(double fit) {
        double pick = ZOOMS[0];
        for (double z : ZOOMS) {
            if (z <= Math.min(32, fit)) {
                pick = z;
            }
        }
        return pick;
    }

    /** Fits the view in {@code width} by {@code height} pixels (see above) and redraws. */
    public void fit(int width, int height) {
        fitZoom(width, height);
        fire();
    }

    public void setDiagonal(boolean diagonal) {
        if (this.diagonal != diagonal) {
            this.diagonal = diagonal;
            rerunAndFire();
        }
    }

    public void setHeuristic(HeuristicChoice heuristic) {
        if (this.heuristic != heuristic) {
            this.heuristic = heuristic;
            rerunAndFire();
        }
    }

    /**
     * The cost of one block straight and one block diagonally. Any positive values work: the
     * heuristic is scaled to them (see {@link DefaultCostModel#heuristic(int)}), so the path
     * is still the cheapest for these costs.
     */
    public void setStepCosts(double walk, double diagonal) {
        if (!(walk > 0) || !(diagonal > 0)) {
            throw new IllegalArgumentException("Step costs must be above 0");
        }
        if (walkCost != walk || diagonalCost != diagonal) {
            walkCost = walk;
            diagonalCost = diagonal;
            rerunAndFire();
        }
    }

    /**
     * Multiplies the heuristic (weighted A*): 1 finds the cheapest path, more expands fewer
     * nodes for a path that may cost more.
     */
    public void setHeuristicWeight(double weight) {
        if (!(weight >= 1)) {
            throw new IllegalArgumentException("The heuristic weight must be at least 1");
        }
        if (heuristicWeight != weight) {
            heuristicWeight = weight;
            rerunAndFire();
        }
    }

    /**
     * The cost added for every 45 degrees the heading turns (see {@link TurnPenalty}), so
     * straight runs, along the axes or the diagonals, beat zigzags. 0 turns it off; the
     * default is {@link TurnPenalty#DEFAULT_PER_TURN}.
     */
    public void setTurnCost(double cost) {
        if (!(cost >= 0)) {
            throw new IllegalArgumentException("The turn cost can't be negative");
        }
        if (turnCost != cost) {
            turnCost = cost;
            rerunAndFire();
        }
    }

    public double turnCost() {
        return turnCost;
    }

    /**
     * How much more a step against a wall or at a ledge costs ({@link TerrainCosts#wall}), so
     * paths keep to the middle of the way. 0 turns it off; the default is {@link
     * TerrainCosts#WALL}.
     */
    public void setWallCost(double cost) {
        if (!(cost >= 0)) {
            throw new IllegalArgumentException("The wall cost can't be negative");
        }
        if (wallCost != cost) {
            wallCost = cost;
            rerunAndFire();
        }
    }

    public double wallCost() {
        return wallCost;
    }

    /** Shows or hides every path node and the key nodes on the map from above. */
    /**
     * Whether a turn cost is charged exactly: a node per heading, so the path is the cheapest
     * counting turns (see {@code TurningCostModel.exact}).
     */
    public void setExactTurns(boolean exact) {
        if (exactTurns != exact) {
            exactTurns = exact;
            rerunAndFire();
        }
    }

    public boolean exactTurns() {
        return exactTurns;
    }

    public void setShowNodes(boolean show) {
        this.showNodes = show;
        fire();
    }

    /** Shows or hides the smoothed route on the finished path. */
    public void setShowSmoothed(boolean show) {
        this.showSmoothed = show;
        fire();
    }

    public void setTool(Tool tool) {
        this.tool = tool;
        fire();
    }

    /** Shows one Y level, or {@code null} for every level the search reached. */
    public void setView(Integer level) {
        this.viewLevel = level;
        this.above = null;
        capLevelZoom();
        fire();
    }

    /**
     * Shows the whole map from above, with the terrain in this style, or {@code null} to go back
     * to the level panels.
     */
    public void setAbove(HeightMap.Style style) {
        this.above = style;
        fire();
    }

    // ---- Editing ------------------------------------------------------------------------

    /** Sets the block at this exact level to the current tool, then re-runs the search. */
    public void paint(BlockPoint cell) {
        if (cell == null || !world.inBounds(cell.x(), cell.y(), cell.z())) {
            return;
        }
        lastPainted = cell;
        if (world.blockAt(cell.x(), cell.y(), cell.z()) != tool.block()) {
            world.set(cell.x(), cell.y(), cell.z(), tool.block());
            heightMap = null;
            rerunOrDefer();
        }
    }

    /**
     * Starts a drag: on the start or goal marker it moves that marker, otherwise it paints. The
     * view from above only moves markers; there's no telling which level a paint stroke is for.
     */
    public void beginDrag(BlockPoint cell) {
        if (cell == null) {
            return;
        }
        if (cell.equals(start)) {
            drag = Drag.START;
        } else if (cell.equals(goal)) {
            drag = Drag.GOAL;
        } else if (above == null) {
            drag = Drag.PAINT;
            paint(cell);
        }
    }

    public void dragTo(BlockPoint cell) {
        if (cell == null || !world.inBounds(cell.x(), cell.y(), cell.z())) {
            return;
        }
        switch (drag) {
            case START -> {
                if (!cell.equals(start)) {
                    start = cell;
                    rerunOrDefer();
                }
            }
            case GOAL -> {
                if (!cell.equals(goal)) {
                    goal = cell;
                    rerunOrDefer();
                }
            }
            case PAINT -> {
                if (!cell.equals(lastPainted)) {
                    paint(cell);
                }
            }
            case NONE -> { }
        }
    }

    public void endDrag(BlockPoint cell) {
        dragTo(cell);
        drag = Drag.NONE;
        lastPainted = null;
        if (searchPending) {
            rerunAndFire();
        }
    }

    public void setHovered(BlockPoint cell) {
        if (cell == null ? hovered != null : !cell.equals(hovered)) {
            hovered = cell;
            fire();
        }
    }

    // ---- Playback -----------------------------------------------------------------------

    public void first() {
        goTo(0);
    }

    public void back() {
        goTo(step - 1);
    }

    public void forward() {
        goTo(step + 1);
    }

    public void last() {
        goTo(lastStep());
    }

    /** Jumps to a step (clamped) and pauses playback. */
    public void goTo(int target) {
        playing = false;
        step = Math.max(0, Math.min(lastStep(), target));
        fire();
    }

    /** Starts or stops playback. Starting from the last step restarts from the beginning. */
    public void setPlaying(boolean play) {
        if (play && recording == null) {
            return;
        }
        if (play && step >= lastStep()) {
            step = 0;
        }
        playing = play;
        fire();
    }

    /** Advances playback by one step. Returns whether playback is still running. */
    public boolean tick() {
        return tick(1);
    }

    /** Advances playback by {@code steps} steps at once, for fast playback. */
    public boolean tick(int steps) {
        if (!playing || recording == null) {
            return false;
        }
        step = Math.min(lastStep(), step + Math.max(0, steps));
        if (step >= lastStep()) {
            playing = false;
        }
        fire();
        return playing;
    }

    // ---- Queries ------------------------------------------------------------------------

    public FrameState frame() {
        if (recording == null || searchPending) {
            return FrameState.empty(start, goal);
        }
        if (cachedFrame == null || cachedFrame.step() != step) {
            cachedFrame = recording.frame(step);
        }
        return cachedFrame;
    }

    /** The Y levels to draw. */
    public List<Integer> layers() {
        if (viewLevel != null) {
            return List.of(viewLevel);
        }
        if (recording != null) {
            if (layersFor != recording) { // scanning every event is slow: once per search
                layersFor = recording;
                cachedLayers = GridRenderer.layers(recording);
            }
            return cachedLayers;
        }
        return List.copyOf(new TreeSet<>(List.of(start.y(), goal.y())));
    }

    public Inspection inspect(BlockPoint cell) {
        if (cell == null) {
            return null;
        }
        return new Inspection(cell,
                world.blockAt(cell.x(), cell.y(), cell.z()),
                world.blockAt(cell.x(), cell.y() - 1, cell.z()),
                inspector().canStand(cell),
                inspector().validator().elevation(cell),
                frame().inspect(cell),
                source.imported() ? source.toWorld(cell) : null);
    }

    /** The world currently loaded. */
    public WorldSource source() {
        return source;
    }

    /** Every world loaded so far: the demos, then any imported islands. */
    public List<WorldSource> worlds() {
        return List.copyOf(worlds);
    }

    /** Pixels per block; below 1 when zoomed far out. */
    public double zoom() {
        return zoom;
    }

    /** The terrain style of the view from above, or {@code null} when showing levels. */
    public HeightMap.Style above() {
        return above;
    }

    /** The current world seen from above; rescanned after each edit. */
    public HeightMap heightMap() {
        if (heightMap == null) {
            heightMap = HeightMap.of(world);
        }
        return heightMap;
    }

    /**
     * The cell a click on column (x, z) in the view from above means: the start or goal if
     * either is in that column, otherwise the highest floor there ({@code null} if none).
     */
    public BlockPoint columnCell(int x, int z) {
        if (start.x() == x && start.z() == z) {
            return start;
        }
        if (goal.x() == x && goal.z() == z) {
            return goal;
        }
        return heightMap().top(x, z);
    }

    public ArrayBlockView world() {
        return world;
    }

    public BlockPoint start() {
        return start;
    }

    public BlockPoint goal() {
        return goal;
    }

    public boolean diagonal() {
        return diagonal;
    }

    public HeuristicChoice heuristic() {
        return heuristic;
    }

    public double walkCost() {
        return walkCost;
    }

    public double diagonalCost() {
        return diagonalCost;
    }

    public double heuristicWeight() {
        return heuristicWeight;
    }

    public boolean showNodes() {
        return showNodes;
    }

    /** The key nodes of the current path (see {@link PathAnalysis#keyNodes}); empty if none. */
    public List<PathStep> keyNodes() {
        return result != null && result.found() ? PathAnalysis.keyNodes(result.path()) : List.of();
    }

    public Tool tool() {
        return tool;
    }

    public Integer viewLevel() {
        return viewLevel;
    }

    /** The recorded search, or {@code null} if it was refused. */
    public Recording recording() {
        return recording;
    }

    public SearchResult result() {
        return result;
    }

    /** The smoothed route for the current path; empty if there is no path. */
    public List<Waypoint> waypoints() {
        return waypoints;
    }

    public boolean showSmoothed() {
        return showSmoothed;
    }

    /** What to draw: the waypoints if smoothing is shown, otherwise nothing. */
    public List<Waypoint> shownWaypoints() {
        return showSmoothed ? waypoints : List.of();
    }

    public String status() {
        return status;
    }

    public int step() {
        return step;
    }

    public int lastStep() {
        return recording == null ? 0 : recording.lastStep();
    }

    public boolean playing() {
        return playing;
    }

    public BlockPoint hovered() {
        return hovered;
    }

    // ---- Internals ----------------------------------------------------------------------

    /**
     * The pathfinder for searches and the inspector, kept between searches (it keeps the move
     * graph it builds, see {@link WorldPathfinder}) and mouse moves (hovering asks on every
     * one); made again when the world or the settings change.
     */
    private WorldPathfinder inspector() {
        Object[] key = {world, diagonal, heuristic, walkCost, diagonalCost, heuristicWeight,
            turnCost, exactTurns, wallCost};
        if (inspector == null || !java.util.Arrays.equals(key, inspectorKey)) {
            inspector = finder();
            inspectorKey = key;
        }
        return inspector;
    }

    private WorldPathfinder finder() {
        DefaultCostModel costs = DefaultCostModel.DEFAULT.withSteps(walkCost, diagonalCost);
        int directions = diagonal ? 8 : 4;
        Heuristic flat = costs.heuristic(directions);
        Heuristic h = switch (heuristic) {
            case DIJKSTRA -> Heuristics.ZERO;
            case DEFAULT -> Heuristics.scaled(flat, heuristicWeight);
            case LANDMARKS -> Heuristics.scaled(landmarks(costs, directions, flat),
                    heuristicWeight);
        };
        return new WorldPathfinder(world, EntityProfile.DEFAULT, directions, costs,
                TerrainCosts.DEFAULT.withWall(wallCost), h, turnCost, exactTurns);
    }

    /**
     * The landmarks for this world, these step costs and directions, kept between searches.
     * They rebuild themselves after edits: at once on small worlds, in the background on big
     * ones (searching again when they're ready).
     */
    private AutoLandmarks landmarks(DefaultCostModel costs, int directions, Heuristic flat) {
        // Exact turn costs also get landmarks that count turns, built for this turn cost.
        double turns = exactTurns ? turnCost : 0;
        Object[] key = {world, costs, directions, turns, wallCost};
        if (landmarks == null || !java.util.Arrays.equals(key, landmarksKey)) {
            ArrayBlockView w = world;
            TerrainCosts terrain = TerrainCosts.DEFAULT.withWall(wallCost);
            landmarks = new AutoLandmarks(() -> new WorldPathfinder(w, EntityProfile.DEFAULT,
                    directions, costs, terrain, flat, turns, true), flat,
                    AutoLandmarks.DEFAULT_COUNT, AutoLandmarks.SYNC_LIMIT_MS,
                    () -> javax.swing.SwingUtilities.invokeLater(this::rerunAndFire));
            landmarksKey = key;
        }
        return landmarks;
    }

    private void rerunAndFire() {
        rerun();
        capLevelZoom();
        fire();
    }

    /** For tests: the {@link #LIVE_SEARCH_MS} limit, e.g. -1 to always wait for the drag's end. */
    void setLiveSearchMs(double ms) {
        this.liveSearchMs = ms;
    }

    /** Mid-drag, a slow search waits for the drag to end: the markers move, the rest clears. */
    private void rerunOrDefer() {
        if (drag != Drag.NONE && lastSearchMs > liveSearchMs) {
            searchPending = true;
            status = "Let go to search";
            fire();
        } else {
            rerunAndFire();
        }
    }

    /** Re-runs and records the search. During playback, restarts from step 0. */
    private void rerun() {
        long t = System.nanoTime();
        searchPending = false;
        WorldPathfinder finder = inspector();
        SearchRecorder recorder = new SearchRecorder();
        result = finder.find(start, goal, recorder);
        cachedFrame = null;

        waypoints = result.found() ? finder.smoother().smooth(result.path()) : List.of();
        lastSearchMs = (System.nanoTime() - t) / 1e6;

        if (recorder.isEmpty()) {
            recording = null;
            step = 0;
            playing = false;
            status = !finder.canStand(start)
                    ? "Can't stand at the start " + start
                    : "Can't stand at the goal " + goal;
            return;
        }
        recording = recorder.recording();
        step = playing ? 0 : recording.lastStep();
        status = result.found()
                ? String.format("Path found: %d steps, %d key nodes, cost %.3f, %d expanded,"
                        + " %d waypoints", result.path().size() - 1,
                        PathAnalysis.keyNodes(result.path()).size(), result.cost(),
                        result.expanded(), waypoints.size())
                : String.format("No path: %d nodes expanded", result.expanded());
    }

    private void fire() {
        for (Runnable l : List.copyOf(listeners)) {
            l.run();
        }
    }
}
