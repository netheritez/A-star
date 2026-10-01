package astar.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;
import org.junit.jupiter.api.Test;

class PosTest {

    @Test
    void roundTripsIncludingNegativesAndBuildHeight() {
        int[][] cases = {
            {0, 0, 0}, {1, 2, 3}, {-1, -1, -1}, {-30_000_000, -64, 30_000_000},
            {33_554_431, 2047, -33_554_432}, {-33_554_432, -2048, 33_554_431}, {5, 319, -7},
        };
        for (int[] c : cases) {
            long p = Pos.pack(c[0], c[1], c[2]);
            assertEquals(c[0], Pos.x(p), "x of " + p);
            assertEquals(c[1], Pos.y(p), "y of " + p);
            assertEquals(c[2], Pos.z(p), "z of " + p);
        }
    }

    @Test
    void randomRoundTrips() {
        Random rng = new Random(9);
        for (int i = 0; i < 10_000; i++) {
            int x = rng.nextInt(1 << 26) - (1 << 25);
            int y = rng.nextInt(1 << 12) - (1 << 11);
            int z = rng.nextInt(1 << 26) - (1 << 25);
            long p = Pos.pack(x, y, z);
            assertEquals(new BlockPoint(x, y, z), Pos.toPoint(p));
        }
    }

    @Test
    void offsetMovesEachAxis() {
        long p = Pos.offset(Pos.pack(10, 64, -10), -11, 1, 20);
        assertEquals(new BlockPoint(-1, 65, 10), Pos.toPoint(p));
    }
}
