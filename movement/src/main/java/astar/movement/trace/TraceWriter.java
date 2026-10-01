package astar.movement.trace;

import astar.movement.Keys;
import java.io.Closeable;
import java.io.IOException;
import java.io.Writer;
import java.util.Map;

/**
 * Writes a trace as JSON Lines: a header, then blocks, ticks and events in the order they
 * happen. The format is described in {@code docs/TRACES.md}; {@link Trace#read} reads it back.
 */
public final class TraceWriter implements Closeable {

    private final Writer out;
    private final StringBuilder b = new StringBuilder(256);

    public TraceWriter(Writer out) {
        this.out = out;
    }

    public void header(TraceHeader h) throws IOException {
        b.setLength(0);
        b.append("{\"type\":\"header\",\"format\":").append(h.format());
        b.append(",\"game\":");
        Json.string(b, h.game());
        b.append(",\"scenario\":");
        Json.string(b, h.scenario());
        b.append(",\"started\":");
        Json.string(b, h.started());
        b.append(",\"attributes\":{");
        boolean first = true;
        for (Map.Entry<String, Double> e : h.attributes().entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).toList()) {
            if (!first) {
                b.append(',');
            }
            first = false;
            Json.string(b, e.getKey());
            b.append(':');
            Json.number(b, e.getValue());
        }
        b.append("}}");
        line();
    }

    public void block(BlockRecord r) throws IOException {
        b.setLength(0);
        b.append("{\"type\":\"block\",\"p\":[").append(r.x()).append(',').append(r.y())
                .append(',').append(r.z()).append("],\"state\":");
        Json.string(b, r.state());
        b.append(",\"boxes\":[");
        for (int i = 0; i < r.boxes().size(); i++) {
            BlockRecord.Box box = r.boxes().get(i);
            if (i > 0) {
                b.append(',');
            }
            b.append('[');
            Json.number(b, box.minX());
            b.append(',');
            Json.number(b, box.minY());
            b.append(',');
            Json.number(b, box.minZ());
            b.append(',');
            Json.number(b, box.maxX());
            b.append(',');
            Json.number(b, box.maxY());
            b.append(',');
            Json.number(b, box.maxZ());
            b.append(']');
        }
        b.append("],\"slip\":");
        Json.number(b, r.slipperiness());
        b.append(",\"vel\":");
        Json.number(b, r.velocityMultiplier());
        b.append(",\"jump\":");
        Json.number(b, r.jumpMultiplier());
        b.append(",\"fluid\":");
        Json.string(b, r.fluid());
        b.append(",\"fh\":");
        Json.number(b, r.fluidHeight());
        b.append(",\"climb\":").append(r.climbable());
        if (!r.kind().isEmpty()) {
            b.append(",\"kind\":");
            Json.string(b, r.kind());
        }
        if (!r.pointsY().isEmpty()) {
            b.append(",\"ys\":[");
            for (int i = 0; i < r.pointsY().size(); i++) {
                if (i > 0) {
                    b.append(',');
                }
                Json.number(b, r.pointsY().get(i));
            }
            b.append(']');
        }
        b.append('}');
        line();
    }

    public void tick(TickRecord t) throws IOException {
        b.setLength(0);
        b.append("{\"type\":\"tick\",\"t\":").append(t.tick()).append(",\"keys\":");
        Json.string(b, t.keys().code());
        b.append(",\"mv\":[");
        Json.number(b, t.moveSideways());
        b.append(',');
        Json.number(b, t.moveForward());
        b.append("],\"yaw\":");
        Json.number(b, t.yaw());
        b.append(",\"pitch\":");
        Json.number(b, t.pitch());
        vec("p0", t.posBefore());
        vec("v0", t.velBefore());
        vec("p", t.pos());
        vec("v", t.vel());
        b.append(",\"flags\":");
        Json.string(b, flags(t));
        b.append(",\"fall\":");
        Json.number(b, t.fallDistance());
        b.append('}');
        line();
    }

    public void event(TraceEvent e) throws IOException {
        b.setLength(0);
        b.append("{\"type\":\"event\",\"t\":").append(e.tick()).append(",\"text\":");
        Json.string(b, e.text());
        b.append('}');
        line();
    }

    public void flush() throws IOException {
        out.flush();
    }

    @Override
    public void close() throws IOException {
        out.close();
    }

    /** One letter per true flag, in the order of {@link Trace#FLAGS}. */
    static String flags(TickRecord t) {
        boolean[] f = {t.onGround(), t.horizontalCollision(), t.verticalCollision(),
            t.sprinting(), t.sneaking(), t.inWater(), t.climbing()};
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < f.length; i++) {
            if (f[i]) {
                s.append(Trace.FLAGS.charAt(i));
            }
        }
        return s.toString();
    }

    private void vec(String key, Vec3 v) {
        b.append(",\"").append(key).append("\":[");
        Json.number(b, v.x());
        b.append(',');
        Json.number(b, v.y());
        b.append(',');
        Json.number(b, v.z());
        b.append(']');
    }

    private void line() throws IOException {
        b.append('\n');
        out.write(b.toString());
    }
}
