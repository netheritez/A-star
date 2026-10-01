package astar.viz;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.Properties;

/**
 * Remembers the last world save opened, so {@code route}, {@code islands} and {@code edit} can
 * open it again without being given the path. With nothing saved, they open the Dwarven Mines
 * map that ships in {@code maps/} (see {@link #bundled()}).
 *
 * <p>It lives in {@code ~/.astar/settings.properties} (on Windows,
 * {@code C:\Users\<you>\.astar\settings.properties}); delete that file to forget the map. The
 * system property {@code astar.settings} points it somewhere else, for tests.
 */
public final class SavedMap {
    private static final String KEY = "map";

    private SavedMap() {}

    /** Where the setting is stored. */
    public static Path file() {
        String override = System.getProperty("astar.settings");
        return override != null ? Path.of(override)
                : Path.of(System.getProperty("user.home"), ".astar", "settings.properties");
    }

    /** The saved map, if one was saved. It may since have been moved or deleted. */
    public static Optional<Path> load() {
        Path file = file();
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file)) {
            p.load(r);
            String map = p.getProperty(KEY);
            return map == null || map.isBlank() ? Optional.empty() : Optional.of(Path.of(map));
        } catch (IOException | IllegalArgumentException e) { // includes InvalidPathException
            return Optional.empty();
        }
    }

    /** Saves this map as the default. Failing to save only prints a warning. */
    public static void save(Path world) {
        Path file = file();
        Path absolute = world.toAbsolutePath().normalize();
        if (load().map(absolute::equals).orElse(false)) {
            return;
        }
        Properties p = new Properties();
        p.setProperty(KEY, absolute.toString());
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            // Written aside and moved into place, so another tool reading it at the same moment
            // never sees a half-written (or empty) file.
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(temp)) {
                p.store(w, "A* tools: the map route, islands and edit open when given none");
            }
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            System.out.println("  saved " + absolute + " as your default map (in " + file + ")");
        } catch (IOException e) {
            System.out.println("  couldn't save the default map to " + file + ": " + e.getMessage());
        }
    }

    /**
     * The map that ships with the repo, {@code maps/dwarven-mines.zip}, relative to the project
     * folder (where the Gradle tasks run). The system property {@code astar.bundledMap} points
     * it somewhere else, for tests.
     */
    public static Path bundled() {
        String override = System.getProperty("astar.bundledMap");
        return override != null ? Path.of(override) : Path.of("maps", "dwarven-mines.zip");
    }

    /**
     * The map to open when none is given: the saved one if it's still there, otherwise the
     * bundled Dwarven Mines, if present. Prints which one, and why a saved map was skipped.
     */
    static Optional<Path> fallback() {
        Optional<Path> saved = load();
        if (saved.isPresent() && Files.exists(saved.get())) {
            System.out.println("Using your saved map " + saved.get());
            return saved;
        }
        if (saved.isPresent()) {
            System.out.println("Your saved map " + saved.get() + " isn't there any more.");
        }
        Path bundled = bundled();
        if (Files.isRegularFile(bundled)) {
            System.out.println("Using the built-in Dwarven Mines map (" + bundled + ")");
            return Optional.of(bundled);
        }
        return Optional.empty();
    }

    /**
     * The world to open: the one given on the command line if any, otherwise the saved one,
     * otherwise the bundled Dwarven Mines. Prints which, or why there's none.
     *
     * @param given the first argument, or {@code null} if there was none
     */
    static Optional<Path> resolve(String given, String usage) {
        if (given != null && !given.isBlank()) {
            return Optional.of(Path.of(given));
        }
        Optional<Path> map = fallback();
        if (map.isEmpty()) {
            System.out.println(given != null
                    ? "No world given: the path was empty. (In PowerShell, set"
                            + " $zip = \"C:\\...\\map.zip\" first.)"
                    : usage);
            System.out.println("Once you've opened a map, it's remembered and used when you give"
                    + " none.");
        }
        return map;
    }
}
