package astar.movement.sim;

import astar.movement.trace.BlockRecord;
import astar.movement.trace.Trace;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The blocks a trace recorded, as a world to simulate in. Blocks it didn't record are air. */
public final class TraceWorld implements SimWorld {

    private final Map<Long, SimBlock> blocks = new HashMap<>();

    public TraceWorld(Trace trace) {
        for (BlockRecord r : trace.blocks()) {
            blocks.put(key(r.x(), r.y(), r.z()), block(r));
        }
    }

    @Override
    public SimBlock block(int x, int y, int z) {
        return blocks.getOrDefault(key(x, y, z), SimBlock.AIR);
    }

    /** The simulator's view of one recorded block. */
    public static SimBlock block(BlockRecord r) {
        List<Aabb> boxes = new ArrayList<>(r.boxes().size());
        for (BlockRecord.Box b : r.boxes()) {
            boxes.add(new Aabb(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()));
        }
        return new SimBlock(boxes, r.pointsY(), r.slipperiness(), r.velocityMultiplier(),
                r.jumpMultiplier(), r.kind(), r.fluid().equals("water"), r.climbable());
    }

    private static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | ((long) y & 0xFFF);
    }
}
