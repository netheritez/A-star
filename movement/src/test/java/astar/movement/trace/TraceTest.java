package astar.movement.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import astar.movement.Keys;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class TraceTest {

    private static final TraceHeader HEADER = new TraceHeader(Trace.FORMAT, "1.21.6", "walk",
            "2026-09-24T17:40:00Z", Map.of("minecraft:movement_speed", 0.10000000149011612,
                    "option:auto_jump", 0.0));

    @Test
    void everyValueReadsBackBitForBit() throws IOException {
        Random random = new Random(7);
        StringWriter text = new StringWriter();
        TraceWriter w = new TraceWriter(text);
        w.header(HEADER);
        BlockRecord slab = new BlockRecord(-3, 64, 12, "minecraft:oak_slab[type=bottom,waterlogged=false]",
                List.of(new BlockRecord.Box(0, 0, 0, 1, 0.5, 1)), 0.6F, 1.0F, 1.0F, "", 0, false,
                "", List.of(0.0, 0.5, 1.0));
        BlockRecord fence = new BlockRecord(-2, 64, 12, "minecraft:oak_fence",
                List.of(new BlockRecord.Box(0.375, 0, 0.375, 0.625, 1.5, 0.625)), 0.6F, 1, 1, "",
                0, false, "fence", List.of(0.0, 1.5));
        BlockRecord water = new BlockRecord(5, -60, 7, "minecraft:water[level=0]", List.of(),
                0.6F, 1.0F, 1.0F, "water", 0.8888888955116272, false);
        w.block(slab);
        w.block(fence);
        w.block(water);
        TickRecord[] ticks = new TickRecord[50];
        for (int i = 0; i < ticks.length; i++) {
            ticks[i] = new TickRecord(i, Keys.parse(i % 2 == 0 ? "WR" : "-"),
                    random.nextFloat(), 0.98F, random.nextFloat() * 360 - 180,
                    random.nextFloat() * 180 - 90, v(random), v(random), v(random), v(random),
                    random.nextBoolean(), random.nextBoolean(), random.nextBoolean(),
                    random.nextBoolean(), random.nextBoolean(), random.nextBoolean(),
                    random.nextBoolean(), random.nextDouble() * 5);
            w.tick(ticks[i]);
        }
        w.event(new TraceEvent(49, "script ended \"walk\"\n"));
        w.close();

        Trace t = Trace.read(new StringReader(text.toString()));
        assertEquals(HEADER, t.header());
        assertEquals(List.of(slab, fence, water), t.blocks());
        assertEquals(List.of(ticks), t.ticks());
        assertEquals(List.of(new TraceEvent(49, "script ended \"walk\"\n")), t.events());
    }

    @Test
    void aBlockRecordedAgainKeepsItsLatestState() throws IOException {
        StringWriter text = new StringWriter();
        TraceWriter w = new TraceWriter(text);
        w.header(HEADER);
        w.block(door(false));
        w.block(new BlockRecord(1, 2, 4, "minecraft:stone", List.of(new BlockRecord.Box(0, 0, 0,
                1, 1, 1)), 0.6F, 1, 1, "", 0, false));
        w.block(door(true));
        w.close();
        Trace t = Trace.read(new StringReader(text.toString()));
        assertEquals(2, t.blocks().size());
        assertEquals(door(true), t.blocks().get(1));
    }

    @Test
    void unknownLineTypesAreSkipped() throws IOException {
        StringWriter text = new StringWriter();
        TraceWriter w = new TraceWriter(text);
        w.header(HEADER);
        w.close();
        Trace t = Trace.read(new StringReader(text + "{\"type\":\"future\",\"x\":[1,{}]}\n\n"));
        assertTrue(t.ticks().isEmpty());
    }

    @Test
    void badTracesNameTheLine() {
        assertThrows(IllegalArgumentException.class, () -> Trace.read(new StringReader("")));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Trace.read(new StringReader("{\"type\":\"tick\"}\n")));
        assertTrue(e.getMessage().startsWith("trace line 1:"), e.getMessage());
        e = assertThrows(IllegalArgumentException.class, () -> Trace.read(new StringReader(
                "{\"type\":\"header\",\"format\":99,\"game\":\"\",\"scenario\":\"\","
                        + "\"started\":\"\",\"attributes\":{}}\n")));
        assertTrue(e.getMessage().contains("format 99"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> Trace.read(new StringReader(
                "{\"type\":\"header\",\"format\":1,\"game\":\"\",\"scenario\":\"\","
                        + "\"started\":\"\",\"attributes\":{}}\n{\"type\":\"tick\",\"t\":1,")));
    }

    @Test
    void nonFiniteNumbersAreWrittenAsNull() {
        StringBuilder b = new StringBuilder();
        Json.number(b, Double.NaN);
        b.append(' ');
        Json.number(b, Float.POSITIVE_INFINITY);
        assertEquals("null null", b.toString());
    }

    private static BlockRecord door(boolean open) {
        return new BlockRecord(1, 2, 3, "minecraft:oak_door[open=" + open + "]",
                open ? List.of() : List.of(new BlockRecord.Box(0, 0, 0, 1, 1, 0.1875)), 0.6F, 1,
                1, "", 0, false);
    }

    private static Vec3 v(Random r) {
        return new Vec3(r.nextGaussian() * 1000, r.nextGaussian(), -r.nextDouble() * 1e-9);
    }
}
