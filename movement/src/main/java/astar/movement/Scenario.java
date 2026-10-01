package astar.movement;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A scripted run of inputs, one {@link Frame} per tick, so the same movement can be played in
 * the game and in a simulator and the two traces compared.
 *
 * <p>Scripts are text, one step per line:
 *
 * <pre>
 * # Sprint, jump once, and coast to a stop.
 * 15 W sprint
 * 1  W sprint jump
 * 20 W sprint
 * 20 -
 * </pre>
 *
 * <p>Each step starts with how many ticks it lasts, then the keys held ({@code W A S D jump
 * sneak sprint}, or {@code -} for none), then optional camera settings:
 * <ul>
 *   <li>{@code yaw=<degrees>} and {@code pitch=<degrees>} set the camera at the start of the
 *       step. Yaw is relative to where the player faced when the script started (positive turns
 *       right, as in the game); pitch is absolute (positive looks down).
 *   <li>{@code turn=<degrees>} turns the yaw by that much every tick of the step, starting on
 *       its first tick.
 * </ul>
 * Blank lines and text after {@code #} are ignored.
 *
 * <p>A script may start with {@code at <x> <y> <z> <yaw>}: where to stand on the test course
 * before it plays, relative to the course's origin, facing {@code yaw} (-90 faces +x). It's
 * used only when a course has been built ({@code /trace course}); elsewhere scripts start
 * wherever the player stands. Or it may start with {@code from <x> <y> <z> <yaw>}: a place in
 * the world itself, which the player is teleported to wherever they are (it needs commands
 * allowed).
 */
public record Scenario(String name, String description, Start start, List<Frame> frames) {

    /** Names of the scripts shipped in {@code astar/movement/scenarios/}. */
    public static final List<String> BUILT_IN = List.of("walk", "sprint", "sneak", "jump",
            "walk-jump", "sprint-jump", "strafe", "diagonal", "turn", "sprint-stop",
            "course-steps", "course-wall", "course-wall-glance", "course-jump-up", "course-ice",
            "course-soul-sand", "course-ledge", "course-sneak-edge");

    /** The longest script accepted: five minutes of ticks. */
    public static final int MAX_TICKS = 20 * 60 * 5;

    /**
     * One tick of input.
     *
     * @param yaw the camera's yaw for this tick, relative to the start of the script
     * @param pitch the camera's pitch for this tick, or {@code NaN} to leave it as it is
     */
    public record Frame(Keys keys, float yaw, float pitch) {}

    /**
     * Where a script starts.
     *
     * @param world true for a place in the world ({@code from}), false for one on the test
     *     course, relative to its origin ({@code at})
     */
    public record Start(double x, double y, double z, float yaw, boolean world) {

        /** A place on the test course. */
        public Start(double x, double y, double z, float yaw) {
            this(x, y, z, yaw, false);
        }
    }

    public Scenario {
        frames = List.copyOf(frames);
    }

    /** A script with no course position. */
    public Scenario(String name, String description, List<Frame> frames) {
        this(name, description, null, frames);
    }

    public int ticks() {
        return frames.size();
    }

    /** Loads one of {@link #BUILT_IN}. */
    public static Scenario builtIn(String name) {
        if (!BUILT_IN.contains(name)) {
            throw new IllegalArgumentException("no built-in scenario \"" + name + "\"; there are "
                    + String.join(", ", BUILT_IN));
        }
        try (InputStream in = Scenario.class.getResourceAsStream(
                "/astar/movement/scenarios/" + name + ".txt")) {
            if (in == null) {
                throw new IllegalStateException("built-in scenario " + name + " is missing");
            }
            return parse(name, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("can't read built-in scenario " + name, e);
        }
    }

    /**
     * Parses a script. The description is the script's first comment line, if it starts with
     * one.
     *
     * @throws IllegalArgumentException naming the line, when a line can't be read
     */
    public static Scenario parse(String name, String text) {
        List<Frame> frames = new ArrayList<>();
        String description = "";
        Start start = null;
        float yaw = 0;
        float pitch = Float.NaN;
        String[] lines = text.split("\r?\n", -1);
        for (int n = 0; n < lines.length; n++) {
            String line = lines[n];
            int hash = line.indexOf('#');
            if (hash >= 0) {
                if (frames.isEmpty() && description.isEmpty() && line.substring(0, hash).isBlank()) {
                    description = line.substring(hash + 1).strip();
                }
                line = line.substring(0, hash);
            }
            line = line.strip();
            if (line.isEmpty()) {
                continue;
            }
            try {
                String[] tokens = line.split("\\s+");
                String word = tokens[0].toLowerCase(Locale.ROOT);
                if (word.equals("at") || word.equals("from")) {
                    if (start != null || !frames.isEmpty()) {
                        throw new IllegalArgumentException("\"at\" or \"from\" comes once,"
                                + " before the steps");
                    }
                    if (tokens.length != 5) {
                        throw new IllegalArgumentException("\"" + word + "\" takes x y z yaw");
                    }
                    start = new Start(parseCoordinate(tokens[1]), parseCoordinate(tokens[2]),
                            parseCoordinate(tokens[3]), parseAngle(tokens[4]),
                            word.equals("from"));
                    continue;
                }
                int ticks = parseTicks(tokens[0]);
                boolean f = false, l = false, b = false, r = false;
                boolean jump = false, sneak = false, sprint = false, none = false;
                float turn = 0;
                for (int i = 1; i < tokens.length; i++) {
                    String t = tokens[i];
                    String lower = t.toLowerCase(Locale.ROOT);
                    if (lower.startsWith("yaw=")) {
                        yaw = parseAngle(t.substring(4));
                    } else if (lower.startsWith("pitch=")) {
                        pitch = parseAngle(t.substring(6));
                        if (pitch < -90 || pitch > 90) {
                            throw new IllegalArgumentException("pitch must be between -90 and 90");
                        }
                    } else if (lower.startsWith("turn=")) {
                        turn = parseAngle(t.substring(5));
                    } else {
                        switch (lower) {
                            case "w" -> f = true;
                            case "a" -> l = true;
                            case "s" -> b = true;
                            case "d" -> r = true;
                            case "jump" -> jump = true;
                            case "sneak" -> sneak = true;
                            case "sprint" -> sprint = true;
                            case "-" -> none = true;
                            default -> throw new IllegalArgumentException("unknown word \"" + t
                                    + "\" (expected W A S D jump sneak sprint -, or yaw=, pitch=,"
                                    + " turn=)");
                        }
                    }
                }
                Keys keys = new Keys(f, l, b, r, jump, sneak, sprint);
                if (none && !keys.equals(Keys.NONE)) {
                    throw new IllegalArgumentException("- means no keys, but keys are listed too");
                }
                if (frames.size() + ticks > MAX_TICKS) {
                    throw new IllegalArgumentException("the script is longer than " + MAX_TICKS
                            + " ticks");
                }
                for (int i = 0; i < ticks; i++) {
                    yaw += turn;
                    frames.add(new Frame(keys, yaw, pitch));
                }
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(name + " line " + (n + 1) + ": "
                        + e.getMessage(), e);
            }
        }
        if (frames.isEmpty()) {
            throw new IllegalArgumentException(name + ": the script has no steps");
        }
        return new Scenario(name, description, start, frames);
    }

    /**
     * Writes the script back as text that {@link #parse} reads to the same frames: one line
     * per run of ticks with the same keys and camera.
     */
    public String toText() {
        StringBuilder b = new StringBuilder();
        if (!description.isEmpty()) {
            b.append("# ").append(description.replace('\n', ' ')).append('\n');
        }
        if (start != null) {
            b.append(start.world() ? "from " : "at ").append(start.x()).append(' ')
                    .append(start.y()).append(' ').append(start.z()).append(' ')
                    .append(start.yaw()).append('\n');
        }
        int i = 0;
        while (i < frames.size()) {
            Frame f = frames.get(i);
            int n = 1;
            while (i + n < frames.size() && frames.get(i + n).equals(f)) {
                n++;
            }
            b.append(n);
            Keys k = f.keys();
            if (k.equals(Keys.NONE)) {
                b.append(" -");
            }
            if (k.forward()) {
                b.append(" W");
            }
            if (k.left()) {
                b.append(" A");
            }
            if (k.back()) {
                b.append(" S");
            }
            if (k.right()) {
                b.append(" D");
            }
            if (k.jump()) {
                b.append(" jump");
            }
            if (k.sneak()) {
                b.append(" sneak");
            }
            if (k.sprint()) {
                b.append(" sprint");
            }
            b.append(" yaw=").append(f.yaw());
            if (!Float.isNaN(f.pitch())) {
                b.append(" pitch=").append(f.pitch());
            }
            b.append('\n');
            i += n;
        }
        return b.toString();
    }

    private static int parseTicks(String s) {
        int ticks;
        try {
            ticks = Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("a step starts with its length in ticks, not \""
                    + s + "\"");
        }
        if (ticks < 1) {
            throw new IllegalArgumentException("a step lasts at least 1 tick");
        }
        return ticks;
    }

    private static double parseCoordinate(String s) {
        try {
            double d = Double.parseDouble(s);
            if (!Double.isFinite(d)) {
                throw new NumberFormatException();
            }
            return d;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("\"" + s + "\" isn't a number");
        }
    }

    private static float parseAngle(String s) {
        try {
            float a = Float.parseFloat(s);
            if (!Float.isFinite(a)) {
                throw new NumberFormatException();
            }
            return a;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("\"" + s + "\" isn't a number");
        }
    }
}
