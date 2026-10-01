package astar.client;

import astar.movement.exec.AimController.Hand;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/** Which hand /goto moves the mouse with, kept between games ({@code /goto hand}). */
final class MouseHand {

    private static Hand hand;

    private MouseHand() {
    }

    /** The hand chosen, the right one until another is. */
    static Hand get() {
        if (hand == null) {
            hand = Hand.RIGHT;
            try {
                Hand saved = Hand.named(Files.readString(file()));
                if (saved != null) {
                    hand = saved;
                }
            } catch (IOException | RuntimeException e) {
                // None chosen yet.
            }
        }
        return hand;
    }

    /** Moves the mouse with this hand from the next trip on, in this game and later ones. */
    static void set(Hand chosen) {
        hand = chosen;
        try {
            Files.writeString(file(), chosen.name().toLowerCase());
        } catch (IOException e) {
            // Kept for this game only.
        }
    }

    static String describe() {
        return "Moving the mouse " + (get() == Hand.LEFT ? "left" : "right") + "-handed.";
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("astar-mouse-hand.txt");
    }
}
