package astar.movement.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import astar.movement.Scenario;
import astar.movement.trace.Trace;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Replays traces recorded in Minecraft 26.3 (one per built-in scenario, on the flat test
 * course) and checks the simulator lands on exactly the same positions and velocities.
 */
class TraceReplayTest {

    static Iterable<String> scenarios() {
        return Scenario.BUILT_IN;
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    void freeRunMatchesTheGameExactly(String name) throws IOException {
        TraceReplay.Result r = TraceReplay.replay(read(name), false, 0.0);
        assertTrue(r.ticks() > 0);
        assertEquals("", r.unsupported());
        assertTrue(r.matches(), name + " diverged on tick " + r.firstDivergentTick() + ": "
                + r.firstDivergence());
        assertEquals(0.0, r.maxPositionError());
        assertEquals(0.0, r.maxVelocityError());
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    void everyTickMatchesFromTheRecordedStart(String name) throws IOException {
        TraceReplay.Result r = TraceReplay.replay(read(name), true, 0.0);
        assertTrue(r.matches(), name + " diverged on tick " + r.firstDivergentTick() + ": "
                + r.firstDivergence());
    }

    static Trace read(String name) throws IOException {
        InputStream in = TraceReplayTest.class.getResourceAsStream(
                "/astar/movement/traces/" + name + ".jsonl.gz");
        assertNotNull(in, "no recorded trace for " + name);
        try (var reader = new InputStreamReader(new GZIPInputStream(in),
                StandardCharsets.UTF_8)) {
            return Trace.read(reader);
        }
    }
}
