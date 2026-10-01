package astar.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ScenarioTest {

    @Test
    void stepsExpandToOneFramePerTick() {
        Scenario s = Scenario.parse("t", """
                # Sprint and jump.
                15 W sprint
                1  W sprint jump   # the jump
                4  -
                """);
        assertEquals("Sprint and jump.", s.description());
        assertEquals(20, s.ticks());
        assertEquals(Keys.parse("WR"), s.frames().get(0).keys());
        assertEquals(Keys.parse("WJR"), s.frames().get(15).keys());
        assertEquals(Keys.NONE, s.frames().get(16).keys());
        assertTrue(Float.isNaN(s.frames().get(0).pitch()), "pitch is left alone by default");
    }

    @Test
    void cameraSettingsCarryOverAndTurnAddsEachTick() {
        Scenario s = Scenario.parse("t", """
                2 W yaw=90 pitch=10
                3 W turn=5
                1 -
                """);
        List<Scenario.Frame> f = s.frames();
        assertEquals(90, f.get(0).yaw());
        assertEquals(90, f.get(1).yaw());
        assertEquals(95, f.get(2).yaw());
        assertEquals(105, f.get(4).yaw());
        assertEquals(105, f.get(5).yaw(), "the yaw stays where the turn left it");
        assertEquals(10, f.get(5).pitch());
    }

    @Test
    void keyWordsAreCaseInsensitive() {
        Scenario s = Scenario.parse("t", "1 w a s d JUMP Sneak SPRINT");
        assertEquals(new Keys(true, true, true, true, true, true, true), s.frames().get(0).keys());
    }

    @Test
    void mistakesNameTheLine() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Scenario.parse("mine", "10 W\n5 W hop\n"));
        assertTrue(e.getMessage().startsWith("mine line 2:"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> Scenario.parse("t", "W 10"));
        assertThrows(IllegalArgumentException.class, () -> Scenario.parse("t", "0 W"));
        assertThrows(IllegalArgumentException.class, () -> Scenario.parse("t", "1 - W"));
        assertThrows(IllegalArgumentException.class, () -> Scenario.parse("t", "1 W pitch=95"));
        assertThrows(IllegalArgumentException.class, () -> Scenario.parse("t", "1 W yaw=abc"));
        assertThrows(IllegalArgumentException.class, () -> Scenario.parse("t", "# only a comment"));
        assertThrows(IllegalArgumentException.class,
                () -> Scenario.parse("t", (Scenario.MAX_TICKS + 1) + " W"));
    }

    @Test
    void everyBuiltInScriptLoads() {
        for (String name : Scenario.BUILT_IN) {
            Scenario s = Scenario.builtIn(name);
            assertEquals(name, s.name());
            assertFalse(s.description().isEmpty(), name + " has a description");
            assertEquals(Keys.NONE, s.frames().get(s.ticks() - 1).keys(),
                    name + " ends with the keys released, so the stop is recorded");
        }
        assertThrows(IllegalArgumentException.class, () -> Scenario.builtIn("nope"));
    }

    @Test
    void keyCodesRoundTrip() {
        for (int bits = 0; bits < 128; bits++) {
            Keys k = new Keys((bits & 1) != 0, (bits & 2) != 0, (bits & 4) != 0, (bits & 8) != 0,
                    (bits & 16) != 0, (bits & 32) != 0, (bits & 64) != 0);
            assertEquals(k, Keys.parse(k.code()));
        }
        assertEquals("-", Keys.NONE.code());
        assertEquals("WJR", new Keys(true, false, false, false, true, false, true).code());
        assertThrows(IllegalArgumentException.class, () -> Keys.parse("WX"));
    }

    @Test
    void anAtLineGivesTheCourseStart() {
        Scenario s = Scenario.parse("t", """
                at 2.5 3 -12.5 -90
                5 W
                """);
        assertEquals(new Scenario.Start(2.5, 3, -12.5, -90), s.start());
        assertEquals(null, Scenario.parse("t", "5 W").start());
        assertThrows(IllegalArgumentException.class,
                () -> Scenario.parse("t", "5 W\nat 0 0 0 0"));
        assertThrows(IllegalArgumentException.class, () -> Scenario.parse("t", "at 0 0 0\n5 W"));
        for (String name : Scenario.BUILT_IN) {
            if (name.startsWith("course-")) {
                assertTrue(Scenario.builtIn(name).start() != null, name);
            }
        }
    }

    @Test
    void aFromLineGivesAPlaceInTheWorld() {
        Scenario s = Scenario.parse("t", """
                from 168.5 202.25 -283.5 135
                5 W
                """);
        assertEquals(new Scenario.Start(168.5, 202.25, -283.5, 135, true), s.start());
        assertThrows(IllegalArgumentException.class,
                () -> Scenario.parse("t", "at 0 0 0 0\nfrom 0 0 0 0\n5 W"));
    }

    @Test
    void writtenScriptsReadBackToTheSameFrames() {
        Keys w = Keys.parse("W");
        Keys sprint = Keys.parse("WR");
        Keys coast = Keys.parse("N");
        List<Scenario.Frame> frames = List.of(
                new Scenario.Frame(sprint, 0, 10), new Scenario.Frame(sprint, 0, 10),
                new Scenario.Frame(sprint, 11.700012F, 10), new Scenario.Frame(w, -0.1F, 10),
                new Scenario.Frame(coast, 1e-6F, 10), new Scenario.Frame(Keys.NONE, 0, 10),
                new Scenario.Frame(Keys.parse("ADSJ"), 3, -20));
        Scenario s = new Scenario("t", "a test", new Scenario.Start(-1.5, 64, 7.25, -45, true),
                frames);
        Scenario back = Scenario.parse("t", s.toText());
        assertEquals(s.frames(), back.frames());
        assertEquals(s.start(), back.start());
        assertEquals("a test", back.description());
    }
}
