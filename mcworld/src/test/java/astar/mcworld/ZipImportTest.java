package astar.mcworld;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import astar.pathing.BlockType;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

/** Worlds shared as .zip files, in the layouts people actually use. */
class ZipImportTest {
    private static final Path WORLD = Path.of("src/test/resources/skyblock-test");

    /** Writes a zip where each entry of {@code layout} puts a copy of the test world at a prefix. */
    private static Path zip(String name, Map<String, Path> layout) throws IOException {
        Path out = Files.createTempDirectory("zips").resolve(name);
        try (OutputStream file = Files.newOutputStream(out);
                ZipOutputStream zip = new ZipOutputStream(file)) {
            for (Map.Entry<String, Path> e : layout.entrySet()) {
                try (Stream<Path> walk = Files.walk(e.getValue())) {
                    for (Path p : walk.filter(Files::isRegularFile).sorted().toList()) {
                        String rel = e.getValue().relativize(p).toString().replace('\\', '/');
                        if (rel.equals("expected-blocks.txt")) {
                            continue;
                        }
                        zip.putNextEntry(new ZipEntry(e.getKey() + rel));
                        zip.write(Files.readAllBytes(p));
                        zip.closeEntry();
                    }
                }
            }
        }
        return out;
    }

    @Test
    void aZipWithTheWorldInsideAFolder() throws IOException {
        Path z = zip("MyIsland.zip", Map.of("MyIsland/", WORLD));
        List<Island> islands = WorldImporter.scan(z);
        assertEquals(3, islands.size());
        assertEquals("MyIsland/region", islands.get(0).location());

        ImportedWorld w = WorldImporter.load(z, islands.get(1));
        assertEquals("MyIsland / MyIsland / island 2", w.name());
        assertEquals(BlockType.HAZARD, w.world().blockAt(0 - w.origin().x(), 60 - w.origin().y(),
                0 - w.origin().z()), "the lava is where it should be");
    }

    @Test
    void aZipWithLevelDatAtTheTop() throws IOException {
        Path z = zip("flat-layout.zip", Map.of("", WORLD));
        List<Island> islands = WorldImporter.scan(z);
        assertEquals(3, islands.size());
        assertEquals("region", islands.get(0).location());
        assertEquals("", islands.get(0).worldLabel());
        assertEquals("flat-layout / island 1", WorldImporter.load(z, islands.get(0)).name());
    }

    @Test
    void aZipWithTwoWorldsSkipsTheNether() throws IOException {
        Path z = zip("pack.zip", Map.of(
                "pack/WorldA/", WORLD,
                "pack/WorldB/", WORLD,
                "pack/WorldB/DIM-1/", WORLD)); // a Nether copy that must be ignored
        List<Island> islands = WorldImporter.scan(z);
        assertEquals(6, islands.size(), islands.toString());
        assertEquals(3, islands.stream().filter(i -> i.location().equals("pack/WorldA/region")).count());
        assertEquals(3, islands.stream().filter(i -> i.location().equals("pack/WorldB/region")).count());
        assertEquals(List.of(1, 2, 3, 4, 5, 6), islands.stream().map(Island::index).toList());

        Island fromB = islands.stream().filter(i -> i.worldLabel().equals("pack/WorldB")).findFirst()
                .orElseThrow();
        assertTrue(fromB.toString().contains("in pack/WorldB"), fromB.toString());
        ImportedWorld w = WorldImporter.load(z, fromB);
        assertTrue(w.name().contains("WorldB"), w.name());
    }

    @Test
    void plainFoldersStillWork() throws IOException {
        List<Island> islands = WorldImporter.scan(WORLD);
        assertEquals("region", islands.get(0).location());
        assertEquals("", islands.get(0).worldLabel());
        assertTrue(islands.get(0).toString().startsWith("Island 1: "), islands.get(0).toString());
    }

    @Test
    void explainsWhatItNeeds() throws IOException {
        Path notAWorld = zip("photos.zip", Map.of());
        IOException e = assertThrows(IOException.class, () -> WorldImporter.scan(notAWorld));
        assertTrue(e.getMessage().contains(".zip"), e.getMessage());

        IOException missing = assertThrows(IOException.class,
                () -> WorldImporter.scan(Path.of("no/such/world.zip")));
        assertTrue(missing.getMessage().startsWith("Not found: "), missing.getMessage());
        assertTrue(missing.getMessage().contains("single quotes"), missing.getMessage());
        IOException empty = assertThrows(IOException.class, () -> WorldImporter.scan(Path.of("")));
        assertTrue(empty.getMessage().contains("empty"), empty.getMessage());

        Path text = Files.createTempFile("notes", ".txt");
        IOException e2 = assertThrows(IOException.class, () -> WorldImporter.scan(text));
        assertTrue(e2.getMessage().contains("isn't a folder or a .zip"), e2.getMessage());
    }
}
