package astar.viz;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RouteToolTest {
    @Test
    void p95IsTheNearestRank() {
        double[] hundred = new double[100];
        for (int i = 0; i < 100; i++) {
            hundred[i] = i + 1;
        }
        assertEquals(95, RouteTool.p95(hundred));
        assertEquals(3, RouteTool.p95(new double[] {1, 2, 3}), "few values: the largest");
        double[] forty = new double[40];
        for (int i = 0; i < 40; i++) {
            forty[i] = i + 1;
        }
        assertEquals(38, RouteTool.p95(forty));
    }
}
