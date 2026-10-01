package astar.movement.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AimControllerTest {

    private static final double FRAME = 1 / 60.0;

    @Test
    void turnsARightAngleQuicklyWithoutPassingItOrTurningTooFast() {
        AimController aim = new AimController(AimController.Settings.DEFAULT);
        float yaw = 0;
        double settled = -1;
        for (int f = 0; f < 120; f++) {
            float next = aim.frame(yaw, 90, 0, FRAME);
            double rate = (next - yaw) / FRAME;
            assertTrue(Math.abs(rate) <= AimController.Settings.DEFAULT.maxRate() + 10,
                    "frame " + f + " turned at " + rate + " degrees a second");
            assertTrue(next <= 90 + 0.15, "passed the target: " + next);
            yaw = next;
            if (settled < 0 && Math.abs(90 - yaw) < 1) {
                settled = f * FRAME;
            }
        }
        // A sweep of about 0.4 s that falls a little short, then the steady turn closes it.
        assertTrue(settled > 0 && settled < 0.55, "within a degree after " + settled + " s");
        assertEquals(90, yaw, 0.15);
    }

    @Test
    void movesInWholeMouseCounts() {
        AimController.Settings s = AimController.Settings.DEFAULT;
        AimController aim = new AimController(s);
        float yaw = 10;
        for (int f = 0; f < 60; f++) {
            float next = aim.frame(yaw, 47.3, 0, FRAME);
            double counts = (next - yaw) / s.degreesPerCount();
            assertEquals(Math.round(counts), counts, 1e-3, "frame " + f);
            yaw = next;
        }
        assertEquals(47.3, yaw, s.degreesPerCount());
    }

    @Test
    void goesTheShortWayRound() {
        AimController aim = new AimController(AimController.Settings.DEFAULT);
        float yaw = 170;
        for (int f = 0; f < 60; f++) {
            yaw = aim.frame(yaw, -170, 0, FRAME);
        }
        assertEquals(190, yaw, 0.2);
    }

    @Test
    void keepsUpWithATargetThatTurnsSteadily() {
        AimController aim = new AimController(AimController.Settings.DEFAULT);
        float yaw = 0;
        double target = 0;
        double rate = 90; // degrees a second, a steady curve
        double worst = 0;
        for (int f = 0; f < 180; f++) {
            target += rate * FRAME;
            yaw = aim.frame(yaw, target, rate, FRAME);
            if (f > 30) {
                worst = Math.max(worst, Math.abs(target - yaw));
            }
        }
        // Without the lead it would trail by 2 / omega seconds of the curve (7 degrees); with
        // it, by no more than the frame it can't see coming.
        assertTrue(worst < rate * FRAME + 0.3, "lagged the curve by " + worst + " degrees");
    }

    @Test
    void wrapsAnglesIntoAHalfTurnEitherWay() {
        assertEquals(-170, AimController.wrap(190), 1e-9);
        assertEquals(170, AimController.wrap(-190), 1e-9);
        assertEquals(-180, AimController.wrap(180), 1e-9);
        assertEquals(0, AimController.wrap(720), 1e-9);
    }
}
