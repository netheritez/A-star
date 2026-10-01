package astar.viz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import astar.core.BlockPoint;
import astar.core.TurnPenalty;
import astar.pathing.BlockType;
import java.util.List;
import org.junit.jupiter.api.Test;

class EditorModelTest {
    private static final double EPS = 1e-9;

    private static BlockPoint p(int x, int y, int z) {
        return new BlockPoint(x, y, z);
    }

    @Test
    void startsWithTheTerrainSearchAtItsLastStep() {
        EditorModel m = new EditorModel();
        assertEquals(EditorModel.DemoWorld.TERRAIN.toString(), m.source().name());
        assertFalse(m.source().imported());
        assertTrue(m.result().found());
        assertEquals(m.lastStep(), m.step());
        assertTrue(m.frame().finished());
        assertTrue(m.status().startsWith("Path found"), m.status());
        assertEquals(List.of(1, 2, 3, 4, 5), m.layers());
    }

    @Test
    void stepCostsAndTheWeightChangeTheSearch() {
        EditorModel m = new EditorModel();
        double defaultCost = m.result().cost();
        assertFalse(m.keyNodes().isEmpty());
        // Diagonals as cheap as straight steps: never dearer than before, still found.
        m.setStepCosts(1, 1);
        assertTrue(m.result().found());
        assertTrue(m.result().cost() <= defaultCost + 1e-9);
        m.setStepCosts(1, Math.sqrt(2));
        assertEquals(defaultCost, m.result().cost(), 1e-9);
        int expanded = m.result().expanded();
        m.setHeuristicWeight(3);
        assertTrue(m.result().found());
        assertTrue(m.result().expanded() <= expanded);
        m.setHeuristicWeight(1);
        m.setTurnCost(0.25);
        assertTrue(m.result().found());
        assertEquals(0.25, m.turnCost());
        m.setShowNodes(false);
        assertFalse(m.showNodes());
    }

    @Test
    void paintingAWallReroutesTheSearch() {
        EditorModel m = new EditorModel();
        // A step in the middle of the path, on flat ground (so a wall there is a detour).
        var path = m.result().path();
        BlockPoint onPath = path.get(path.size() / 2).pos();
        assertTrue(m.result().positions().contains(onPath));

        int[] changes = {0};
        m.addChangeListener(() -> changes[0]++);
        m.setTool(EditorModel.Tool.SOLID);
        m.paint(onPath);

        assertEquals(BlockType.SOLID, m.world().blockAt(onPath.x(), onPath.y(), onPath.z()));
        assertTrue(m.result().found());
        assertFalse(m.result().positions().contains(onPath));
        assertTrue(changes[0] >= 2, "tool change and repaint both notify");
    }

    @Test
    void blockingTheGoalRefusesTheSearch() {
        EditorModel m = new EditorModel();
        m.paint(m.goal()); // default tool is SOLID
        assertNull(m.recording());
        assertEquals(0, m.lastStep());
        assertTrue(m.status().contains("goal"), m.status());
        assertTrue(m.frame().closed().isEmpty());
        assertEquals(List.of(1), m.layers());

        m.setTool(EditorModel.Tool.AIR);
        m.paint(m.goal());
        assertNotNull(m.recording(), "clearing it again restores the search");
    }

    @Test
    void draggingAMarkerMovesItAndOtherDragsPaint() {
        EditorModel m = new EditorModel();
        m.beginDrag(m.start());
        m.dragTo(p(1, 1, 2));
        m.endDrag(p(1, 1, 1));
        assertEquals(p(1, 1, 1), m.start());
        assertEquals(BlockType.AIR, m.world().blockAt(1, 1, 1), "moving a marker doesn't paint");
        assertTrue(m.result().found());

        m.setTool(EditorModel.Tool.HAZARD);
        m.beginDrag(p(9, 0, 1));
        m.dragTo(p(9, 0, 2));
        m.endDrag(p(10, 0, 2));
        assertEquals(BlockType.HAZARD, m.world().blockAt(9, 0, 1));
        assertEquals(BlockType.HAZARD, m.world().blockAt(9, 0, 2));
        assertEquals(BlockType.HAZARD, m.world().blockAt(10, 0, 2));
    }

    @Test
    void aSlowSearchWaitsForTheDragToEnd() {
        EditorModel m = new EditorModel();
        m.setLiveSearchMs(-1); // every search counts as slow
        BlockPoint oldStart = m.start();
        m.beginDrag(m.start());
        m.dragTo(p(1, 1, 2));
        assertEquals(p(1, 1, 2), m.start(), "the marker follows the mouse");
        assertTrue(m.frame().closed().isEmpty(), "the old search is cleared");
        assertEquals(p(1, 1, 2), m.frame().start());
        assertTrue(m.status().contains("Let go"), m.status());
        assertEquals(oldStart, m.recording().start(), "no search yet");

        m.endDrag(p(1, 1, 1));
        assertEquals(p(1, 1, 1), m.recording().start(), "searched once the drag ended");
        assertTrue(m.result().found());
        assertFalse(m.frame().closed().isEmpty());

        m.setTool(EditorModel.Tool.HAZARD);
        m.beginDrag(p(9, 0, 1));
        m.dragTo(p(9, 0, 2));
        assertEquals(BlockType.HAZARD, m.world().blockAt(9, 0, 2), "painting doesn't wait");
        assertTrue(m.frame().closed().isEmpty());
        m.endDrag(p(9, 0, 2));
        assertFalse(m.frame().closed().isEmpty());
    }

    @Test
    void reopeningAnIslandReplacesItsEntry() {
        EditorModel m = new EditorModel();
        int before = m.worlds().size();
        java.util.function.Supplier<astar.pathing.ArrayBlockView> make =
                () -> astar.pathing.ArrayBlockView.flat("....", "....");
        BlockPoint origin = new BlockPoint(100, 60, -30);
        m.loadWorld(new WorldSource("Map / island 1", make, p(0, 1, 0), p(3, 1, 1), origin));
        m.loadWorld(new WorldSource("Map / island 1", make, p(0, 1, 0), p(3, 1, 1), origin));
        assertEquals(before + 1, m.worlds().size(), "no duplicate entry");
        m.loadWorld(new WorldSource("Map / island 2", make, p(0, 1, 0), p(3, 1, 1), origin));
        assertEquals(before + 2, m.worlds().size());
        assertEquals(3, m.worldsLoaded());
    }

    @Test
    void theLevelPanelsZoomStaysWithinTheirPixelBudget() {
        EditorModel m = new EditorModel();
        m.loadWorld(new WorldSource("Big", () -> new astar.pathing.ArrayBlockView(1500, 8, 1500),
                p(1, 1, 1), p(2, 1, 2), WorldSource.NO_OFFSET));
        m.setView(1);
        m.setZoom(64);
        long px = (long) Math.ceil(m.zoom()) * 1500;
        assertTrue(px * px <= EditorModel.MAX_LEVEL_PIXELS, "zoom " + m.zoom());
        assertTrue(m.zoom() >= 2, "only lowered as far as needed: " + m.zoom());
    }

    @Test
    void stepControlsClampAndPause() {
        EditorModel m = new EditorModel();
        m.goTo(-5);
        assertEquals(0, m.step());
        m.goTo(1_000_000);
        assertEquals(m.lastStep(), m.step());
        m.forward();
        assertEquals(m.lastStep(), m.step());
        m.first();
        m.forward();
        m.forward();
        m.back();
        assertEquals(1, m.step());
        m.last();
        assertEquals(m.lastStep(), m.step());
    }

    @Test
    void playbackAdvancesAndStopsAtTheEnd() {
        EditorModel m = new EditorModel();
        m.setPlaying(true); // at the last step, so it restarts
        assertEquals(0, m.step());
        assertTrue(m.playing());
        assertTrue(m.tick());
        assertEquals(1, m.step());

        int guard = 0;
        while (m.tick()) {
            assertTrue(++guard < 10_000);
        }
        assertEquals(m.lastStep(), m.step());
        assertFalse(m.playing());
        assertFalse(m.tick());
    }

    @Test
    void editingDuringPlaybackRestartsIt() {
        EditorModel m = new EditorModel();
        m.setPlaying(true);
        m.tick();
        m.tick();
        m.paint(p(0, 3, 6)); // somewhere harmless
        assertTrue(m.playing());
        assertEquals(0, m.step());

        m.forward(); // stepping by hand pauses
        assertFalse(m.playing());
    }

    @Test
    void settingsChangeTheSearch() {
        EditorModel m = new EditorModel();
        double eightWay = m.result().cost();
        int eightWayExpanded = m.result().expanded();

        m.setHeuristic(EditorModel.HeuristicChoice.DIJKSTRA);
        assertEquals(eightWay, m.result().cost(), EPS, "same shortest path");
        assertTrue(m.result().expanded() >= eightWayExpanded, "without a heuristic it looks wider");

        m.setHeuristic(EditorModel.HeuristicChoice.DEFAULT);
        m.setDiagonal(false);
        assertNotEquals(eightWay, m.result().cost());
    }

    @Test
    void inspectShowsValuesAtTheCurrentStep() {
        EditorModel m = new EditorModel();
        BlockPoint goal = m.goal();

        m.first();
        EditorModel.Inspection early = m.inspect(goal);
        assertNull(early.node(), "not reached at step 0");
        assertTrue(early.standable());
        assertEquals(BlockType.AIR, early.here());
        assertEquals(BlockType.SOLID, early.below());

        m.last();
        EditorModel.Inspection late = m.inspect(goal);
        assertEquals(m.result().cost(), late.node().g(), EPS);
        assertEquals(late.node(), m.frame().inspect(goal));
        assertNull(m.inspect(null));
    }

    @Test
    void viewCanShowOneLevel() {
        EditorModel m = new EditorModel();
        m.setView(3);
        assertEquals(List.of(3), m.layers());
        m.setView(null);
        assertEquals(List.of(1, 2, 3, 4, 5), m.layers());
    }

    @Test
    void worldsAreCopiesSoResetDiscardsEdits() {
        EditorModel m = new EditorModel();
        m.paint(p(0, 3, 6));
        assertEquals(BlockType.SOLID, m.world().blockAt(0, 3, 6));
        assertEquals(BlockType.AIR, DemoWorlds.terrain().blockAt(0, 3, 6));

        m.reset();
        assertEquals(BlockType.AIR, m.world().blockAt(0, 3, 6));

        m.setWallCost(0); // as tight as can be: the flat world's edges would cost more
        m.loadWorld(EditorModel.DemoWorld.FLAT);
        assertEquals(DemoWorlds.FLAT_GOAL, m.goal());
        // One diagonal step: a 45-degree turn onto it and another off it.
        assertEquals(13 + Math.sqrt(2) + 2 * TurnPenalty.DEFAULT_PER_TURN, m.result().cost(), EPS);
    }

    @Test
    void waypointsFollowTheSearch() {
        EditorModel m = new EditorModel();
        assertFalse(m.waypoints().isEmpty());
        assertTrue(m.waypoints().size() < m.result().path().size());
        assertEquals(m.start(), m.waypoints().get(0).pos());
        assertEquals(m.goal(), m.waypoints().get(m.waypoints().size() - 1).pos());
        assertTrue(m.status().contains("waypoints"), m.status());

        m.paint(m.goal()); // refuse the search
        assertTrue(m.waypoints().isEmpty());

        m.reset();
        assertEquals(m.waypoints(), m.shownWaypoints());
        m.setShowSmoothed(false);
        assertTrue(m.shownWaypoints().isEmpty());
        assertFalse(m.waypoints().isEmpty(), "hiding doesn't discard them");
    }

    @Test
    void fastPlaybackAdvancesManyStepsPerTick() {
        EditorModel m = new EditorModel();
        m.setPlaying(true);
        assertTrue(m.tick(10));
        assertEquals(10, m.step());
        assertFalse(m.tick(1_000_000), "stops at the end");
        assertEquals(m.lastStep(), m.step());
    }

    @Test
    void zoomIsClamped() {
        EditorModel m = new EditorModel();
        m.setZoom(0.01);
        assertEquals(0.125, m.zoom(), EPS);
        m.setZoom(500);
        assertEquals(64, m.zoom(), EPS);
    }

    @Test
    void zoomStepsGoFarOut() {
        EditorModel m = new EditorModel();
        m.setZoom(1);
        m.zoomOut();
        assertEquals(0.75, m.zoom(), EPS);
        for (int i = 0; i < 10; i++) {
            m.zoomOut();
        }
        assertEquals(0.125, m.zoom(), EPS, "eight blocks a pixel at the most");
        m.zoomIn();
        assertEquals(0.25, m.zoom(), EPS);
    }

    @Test
    void fittingPicksTheLargestStepThatFits() {
        EditorModel m = new EditorModel();
        m.setAbove(HeightMap.Style.GREY);
        int sx = m.world().sizeX();
        int sz = m.world().sizeZ();
        // Room for exactly 10 pixels a block plus the margins and legend: 8 is the step below.
        m.fit(sx * 10 + 2 * GridRenderer.MARGIN, sz * 10 + 2 * GridRenderer.MARGIN
                + RouteImages.LEGEND_H);
        assertEquals(8, m.zoom(), EPS);
        // A window far smaller than the map zooms out below one pixel a block.
        m.fit(sx / 3 + 2 * GridRenderer.MARGIN, 2000);
        assertTrue(m.zoom() < 1 && m.zoom() * sx <= sx / 3.0, "zoom " + m.zoom());
    }

    @Test
    void theViewFromAboveMovesMarkersButDoesNotPaint() {
        EditorModel m = new EditorModel();
        m.setAbove(HeightMap.Style.HEIGHT);
        assertEquals(HeightMap.Style.HEIGHT, m.above());

        // A click on the goal's column picks up the goal; dropping it lands on the top floor.
        BlockPoint goal = m.goal();
        assertEquals(goal, m.columnCell(goal.x(), goal.z()));
        BlockPoint target = m.heightMap().top(11, 1);
        assertNotNull(target);
        m.beginDrag(m.columnCell(goal.x(), goal.z()));
        m.endDrag(m.columnCell(11, 1));
        assertEquals(target, m.goal());
        assertTrue(m.result().found(), m.status());

        // Anywhere else, nothing is painted.
        BlockPoint floor = m.heightMap().top(3, 4);
        BlockType before = m.world().blockAt(floor.x(), floor.y(), floor.z());
        m.beginDrag(floor);
        m.endDrag(floor);
        assertEquals(before, m.world().blockAt(floor.x(), floor.y(), floor.z()));

        // Choosing a level leaves the view from above.
        m.setView(null);
        assertNull(m.above());
    }

    @Test
    void theHeightMapFollowsEdits() {
        EditorModel m = new EditorModel(EditorModel.DemoWorld.FLAT);
        HeightMap before = m.heightMap();
        assertEquals(before, m.heightMap(), "cached until something changes");
        BlockPoint floor = before.top(2, 2);
        m.setTool(EditorModel.Tool.SOLID);
        m.paint(floor);
        assertNotEquals(before, m.heightMap());
        assertTrue(m.heightMap().height(2, 2) != floor.y(), "the column changed");
    }

    private static final java.nio.file.Path FIXTURE =
            java.nio.file.Path.of("../mcworld/src/test/resources/skyblock-test");

    @Test
    void loadsAnIslandFromAWorldSave() throws Exception {
        var islands = astar.mcworld.WorldImporter.scan(FIXTURE);
        WorldSource island = IslandSources.load(FIXTURE, islands.get(1), true);
        assertTrue(island.imported());

        EditorModel m = new EditorModel();
        int demos = m.worlds().size();
        m.loadWorld(island);
        assertEquals(island, m.source());
        assertEquals(demos + 1, m.worlds().size());
        assertTrue(m.result().found(), m.status());
        assertTrue(m.result().cost() > 15, "a long route: " + m.status());
        assertEquals(HeightMap.Style.GREY, m.above(), "big maps open seen from above");
        assertTrue(m.zoom() >= 0.125 && m.zoom() <= 32);

        EditorModel.Inspection in = m.inspect(m.start());
        assertEquals(island.toWorld(m.start()), in.world());
        assertEquals(null, new EditorModel().inspect(new BlockPoint(0, 1, 0)).world(), "demos have none");

        m.paint(m.goal());
        m.reset();
        assertTrue(m.result().found(), "reset restores the imported island");
        m.loadWorld(island);
        assertEquals(demos + 1, m.worlds().size(), "loading it again doesn't duplicate it");
    }

    @Test
    void theIslandToolRendersEveryIsland() throws Exception {
        java.nio.file.Path out = java.nio.file.Files.createTempDirectory("islands");
        System.setProperty("java.awt.headless", "true");
        IslandTool.main(new String[] {FIXTURE.toString(), out.toString()});
        // Island 1 is a solid box: its roof is sealed and there's nowhere to stand, so no image.
        assertFalse(java.nio.file.Files.exists(out.resolve("island-1.png")));
        for (int i = 2; i <= 3; i++) {
            assertTrue(java.nio.file.Files.size(out.resolve("island-" + i + ".png")) > 1000,
                    "island " + i);
        }
    }

    @Test
    void loadsAnIslandFromAZip() throws Exception {
        java.nio.file.Path zip = java.nio.file.Files.createTempDirectory("zip").resolve("Island.zip");
        try (var out = new java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(zip));
                var walk = java.nio.file.Files.walk(FIXTURE)) {
            for (var p : walk.filter(java.nio.file.Files::isRegularFile).toList()) {
                out.putNextEntry(new java.util.zip.ZipEntry(
                        "Island/" + FIXTURE.relativize(p).toString().replace('\\', '/')));
                out.write(java.nio.file.Files.readAllBytes(p));
                out.closeEntry();
            }
        }
        var islands = astar.mcworld.WorldImporter.scan(zip);
        EditorModel m = new EditorModel();
        m.loadWorld(IslandSources.load(zip, islands.get(1), true));
        assertTrue(m.result().found(), m.status());
        assertTrue(m.source().name().startsWith("Island / Island"), m.source().name());
    }

    @Test
    void theRouteToolDrawsAnOverviewAProfileAndLevels() throws Exception {
        System.setProperty("java.awt.headless", "true");
        java.nio.file.Path out = java.nio.file.Files.createTempDirectory("route");
        RouteTool.main(new String[] {FIXTURE.toString(), "--island", "2", "--out", out.toString()});
        for (String f : List.of("overview.png", "profile.png", "levels.png")) {
            assertTrue(java.nio.file.Files.size(out.resolve(f)) > 2000, f);
        }
        // Explicit endpoints, in game coordinates: west of the fence to the brick platform.
        java.nio.file.Path out2 = java.nio.file.Files.createTempDirectory("route2");
        RouteTool.main(new String[] {FIXTURE.toString(), "--island", "2", "--from", "-8,61,-5",
            "--to", "8,63,-5", "--out", out2.toString()});
        assertTrue(java.nio.file.Files.exists(out2.resolve("overview.png")));
    }

    /** Runs RouteTool and captures what it prints. */
    private static String routeTool(String... args) throws Exception {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        java.io.PrintStream old = System.out;
        System.setOut(new java.io.PrintStream(buf, true));
        try {
            RouteTool.main(args);
        } finally {
            System.setOut(old);
        }
        return buf.toString();
    }

    @Test
    void theRouteToolBenchmarksAndCompares() throws Exception {
        System.setProperty("java.awt.headless", "true");
        String out = java.nio.file.Files.createTempDirectory("bench").toString();
        String bench = routeTool(FIXTURE.toString(), "--island", "2", "--bench", "3", "--out", out);
        assertTrue(bench.contains("benchmark: warming up, then 3 timed runs"), bench);
        assertTrue(bench.contains("nodes expanded per millisecond"), bench);

        String cmp = routeTool(FIXTURE.toString(), "--island", "2", "--compare", "--jump-cost", "5",
                "--heuristic-weight", "1.5", "--out", out);
        assertTrue(cmp.contains("Comparison (same start and goal)"), cmp);
        assertTrue(cmp.contains("jump 5"), cmp);
        for (String f : List.of("overview-default.png", "overview-custom.png", "profile-custom.png")) {
            assertTrue(java.nio.file.Files.exists(java.nio.file.Path.of(out, f)), f);
        }
    }

    @Test
    void theRouteToolComparesHierarchicalSearch() throws Exception {
        System.setProperty("java.awt.headless", "true");
        String out = java.nio.file.Files.createTempDirectory("hpa").toString();
        String hpa = routeTool(FIXTURE.toString(), "--island", "2", "--hpa", "--cluster", "4",
                "--compare", "--bench", "3", "--pairs", "20", "--out", out);
        assertTrue(hpa.contains("hierarchical (4-block clusters)"), hpa);
        assertTrue(hpa.contains("portals"), hpa);
        assertTrue(hpa.contains("cost gap vs default %"), hpa);
        assertTrue(hpa.contains("graph build (yours"), hpa);
        assertTrue(hpa.contains("stages:    insert"), hpa);
        assertTrue(hpa.contains("20 random routes, default against yours"), hpa);
        assertTrue(hpa.contains("total search time"), hpa);
        assertTrue(routeTool(FIXTURE.toString(), "--hpa", "--cluster", "1").contains("at least 2"));
    }

    @Test
    void theRouteToolSweepsStepCostsAndWeights() throws Exception {
        forgetSavedMap();
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("sweep");
        String out = routeTool(FIXTURE.toString(), "--island", "2", "--sweep",
                "1/1.414/1,1/1/1,0.5/2/1,1/1.414/3,1/1.414/1/0.25", "--out", dir.toString());
        assertTrue(out.contains("Sweep over step costs"), out);
        assertTrue(out.contains("def. cost"), out);
        assertTrue(java.nio.file.Files.size(dir.resolve("sweep-overview.png")) > 0);
        assertTrue(java.nio.file.Files.size(dir.resolve("sweep-closeup.png")) > 0);
    }

    @Test
    void theRouteToolExplainsBadInput() throws Exception {
        forgetSavedMap();
        assertTrue(routeTool(FIXTURE.toString(), "--jump-cost", "0").contains("aren't allowed"));
        assertTrue(routeTool(FIXTURE.toString(), "--sweep", "1/1").contains("walk/diagonal/weight"));
        assertTrue(routeTool(FIXTURE.toString(), "--sweep", "1/1/0.5").contains("at least 1"));
        assertTrue(routeTool(FIXTURE.toString(), "--turn-cost", "-1").contains("negative"));
        assertTrue(routeTool(FIXTURE.toString(), "--hpa", "--turn-cost", "1")
                .contains("plain A* only"));
        assertTrue(routeTool(FIXTURE.toString(), "--heuristic-weight", "0.5").contains("at least 1"));
        assertTrue(routeTool(FIXTURE.toString(), "--island", "9").contains("There's no island 9"));
        forgetSavedMap();
        assertTrue(routeTool("").contains("the path was empty"));
        assertTrue(routeTool().contains("Usage"));
        assertTrue(routeTool("no/such/map.zip").contains("Not found"));
        assertTrue(routeTool(FIXTURE.toString(), "--from", "1,2").contains("x,y,z"));
    }

    /**
     * Points the saved map at a fresh, empty settings file for this test, with no bundled map.
     */
    private static void forgetSavedMap() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("settings");
        System.setProperty("astar.settings", dir.resolve("settings.properties").toString());
        System.setProperty("astar.bundledMap", dir.resolve("no-bundled-map.zip").toString());
    }

    @Test
    void theBundledMapIsTheFallback() throws Exception {
        forgetSavedMap();
        assertTrue(SavedMap.fallback().isEmpty(), "nothing saved and nothing bundled");

        java.nio.file.Path bundled = java.nio.file.Files.createTempFile("mines", ".zip");
        System.setProperty("astar.bundledMap", bundled.toString());
        assertEquals(bundled, SavedMap.fallback().orElseThrow());

        // A saved map wins while it exists; once it's gone, the bundled one is used again.
        java.nio.file.Path mine = java.nio.file.Files.createTempFile("my-map", ".zip");
        SavedMap.save(mine);
        assertEquals(mine.toAbsolutePath().normalize(), SavedMap.fallback().orElseThrow());
        java.nio.file.Files.delete(mine);
        assertEquals(bundled, SavedMap.fallback().orElseThrow());

        // The repo's own copy is where the tools look for it.
        System.clearProperty("astar.bundledMap");
        assertEquals(java.nio.file.Path.of("maps", "dwarven-mines.zip"), SavedMap.bundled());
        assertTrue(java.nio.file.Files.isRegularFile(java.nio.file.Path.of("..").resolve(SavedMap.bundled())),
                "maps/dwarven-mines.zip is in the repo");
        forgetSavedMap();
    }

    @Test
    void theRouteToolRemembersTheMap() throws Exception {
        System.setProperty("java.awt.headless", "true");
        forgetSavedMap();
        String out = java.nio.file.Files.createTempDirectory("route").toString();
        String first = routeTool(FIXTURE.toString(), "--island", "2", "--out", out);
        assertTrue(first.contains("as your default map"), first);
        assertEquals(FIXTURE.toAbsolutePath().normalize(), SavedMap.load().orElseThrow());
        assertTrue(java.nio.file.Files.exists(java.nio.file.Path.of(out, "heights.png")));

        // No world given: the saved one is used, options and all.
        String again = routeTool("--island", "2", "--out", out);
        assertTrue(again.contains("Using your saved map"), again);
        assertTrue(again.contains("result:"), again);
        assertFalse(again.contains("as your default map"), "already saved: " + again);

        // A PowerShell variable that was never set gives an empty path: the saved map again.
        assertTrue(routeTool("", "--island", "2", "--out", out).contains("result:"));

        SavedMap.save(java.nio.file.Path.of("no/such/map.zip"));
        assertTrue(routeTool().contains("isn't there any more"));
    }

    @Test
    void pricingUnderDefaultsMatchesTheSearchCost() {
        var world = DemoWorlds.terrain();
        var r = new astar.pathing.WorldPathfinder(world, astar.pathing.EntityProfile.DEFAULT, 8,
                astar.core.DefaultCostModel.DEFAULT, RouteTool.Settings.TERRAIN,
                astar.pathing.WorldPathfinder.heuristicFor(8))
                .find(DemoWorlds.TERRAIN_START, DemoWorlds.TERRAIN_GOAL);
        assertEquals(r.cost(), RouteTool.priceUnderDefaults(world, r.path()), 1e-9);
    }

    @Test
    void theHeightsDemoNeedsNoJumps() {
        EditorModel m = new EditorModel(EditorModel.DemoWorld.HEIGHTS);
        var r = new astar.pathing.WorldPathfinder(DemoWorlds.heights(),
                astar.pathing.EntityProfile.DEFAULT, true)
                .find(DemoWorlds.HEIGHTS_START, DemoWorlds.HEIGHTS_GOAL);
        assertTrue(r.found());
        assertTrue(r.path().stream().noneMatch(s -> s.via() == astar.core.MoveType.JUMP_UP),
                "slabs and stairs are walked up: " + r.path());
        assertTrue(r.positions().stream().noneMatch(q -> q.x() == 5 && q.z() < 6),
                "the fence can't be crossed except at its gap");

        EditorModel.Inspection slab = m.inspect(new BlockPoint(6, 1, 0));
        assertTrue(slab.standable());
        assertEquals(1.5, slab.elevation());
        assertTrue(InspectorPanel.describe(slab, 0).contains("feet at +0.5"));
        assertTrue(Double.isNaN(m.inspect(new BlockPoint(6, 2, 0)).elevation()),
                "the cell above a slab is not a floor");
        assertEquals("+1/16", InspectorPanel.elevation(1 / 16.0));
        assertEquals("+0", InspectorPanel.elevation(0));
        assertEquals("+1", InspectorPanel.elevation(1));
    }
}
