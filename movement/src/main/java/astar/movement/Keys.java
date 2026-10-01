package astar.movement;

/**
 * The movement keys held during one tick, as the game reads them: W A S D, jump, sneak and
 * sprint.
 *
 * <p>{@link #code()} writes them as a short string, one letter per held key in the order
 * {@code WASDJNR} (J jump, N sneak, R sprint), or {@code -} when none is held.
 */
public record Keys(boolean forward, boolean left, boolean back, boolean right, boolean jump,
        boolean sneak, boolean sprint) {

    public static final Keys NONE = new Keys(false, false, false, false, false, false, false);

    private static final String LETTERS = "WASDJNR";

    public String code() {
        StringBuilder b = new StringBuilder();
        boolean[] held = held();
        for (int i = 0; i < held.length; i++) {
            if (held[i]) {
                b.append(LETTERS.charAt(i));
            }
        }
        return b.isEmpty() ? "-" : b.toString();
    }

    /** Reads {@link #code()}'s format back. */
    public static Keys parse(String code) {
        if (code.equals("-")) {
            return NONE;
        }
        boolean[] held = new boolean[LETTERS.length()];
        for (char c : code.toCharArray()) {
            int i = LETTERS.indexOf(c);
            if (i < 0) {
                throw new IllegalArgumentException("unknown key '" + c + "' in \"" + code
                        + "\" (expected letters from " + LETTERS + ", or -)");
            }
            held[i] = true;
        }
        return new Keys(held[0], held[1], held[2], held[3], held[4], held[5], held[6]);
    }

    private boolean[] held() {
        return new boolean[] {forward, left, back, right, jump, sneak, sprint};
    }
}
