package astar.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class NodeTableTest {
    @Test
    void keepsEveryPositionAlongEachAxis() {
        // Positions in a straight line along x only differ in the top bits of the packed long,
        // which a weak hash drops. Every one must still get its own node, found again later.
        int n = 1 << 16;
        for (int axis = 0; axis < 3; axis++) {
            NodeTable table = new NodeTable(16);
            for (int i = 0; i < n; i++) {
                assertEquals(i, table.getOrCreate(line(axis, i)));
            }
            for (int i = 0; i < n; i++) {
                assertEquals(i, table.getOrCreate(line(axis, i)));
                assertEquals(line(axis, i), table.pos[i]);
            }
            assertEquals(n, table.size());
        }
    }

    private static long line(int axis, int i) {
        int v = i - (1 << 15);
        return switch (axis) {
            case 0 -> Pos.pack(v, 64, 7);
            case 1 -> Pos.pack(7, (i & 2047) - 1024, v >> 11);
            default -> Pos.pack(7, 64, v);
        };
    }
}
