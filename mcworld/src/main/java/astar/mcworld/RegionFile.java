package astar.mcworld;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * One Anvil region file ({@code r.<x>.<z>.mca}): 32 x 32 chunks.
 *
 * <p>Layout: a 4 KiB table of chunk locations (3 bytes sector offset, 1 byte sector count),
 * a 4 KiB table of timestamps, then chunks in 4 KiB sectors. Each chunk is a 4-byte length, a
 * compression byte (1 gzip, 2 zlib, 3 none; +128 means the data is in a separate
 * {@code c.<x>.<z>.mcc} file) and the compressed NBT.
 */
public final class RegionFile {
    private static final Pattern NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    private static final int SECTOR = 4096;

    private final Path path;
    private final byte[] bytes;
    private final int regionX;
    private final int regionZ;

    public RegionFile(Path path) throws IOException {
        Matcher m = NAME.matcher(path.getFileName().toString());
        if (!m.matches()) {
            throw new IOException("Not a region file name: " + path.getFileName());
        }
        this.path = path;
        this.regionX = Integer.parseInt(m.group(1));
        this.regionZ = Integer.parseInt(m.group(2));
        this.bytes = Files.readAllBytes(path);
    }

    public static boolean isRegionFile(Path p) {
        return NAME.matcher(p.getFileName().toString()).matches();
    }

    public int regionX() {
        return regionX;
    }

    public int regionZ() {
        return regionZ;
    }

    public boolean hasChunk(int localX, int localZ) {
        return bytes.length >= 2 * SECTOR && location(localX, localZ) != 0;
    }

    private int location(int localX, int localZ) {
        int i = 4 * ((localX & 31) + (localZ & 31) * 32);
        return ((bytes[i] & 0xFF) << 24) | ((bytes[i + 1] & 0xFF) << 16)
                | ((bytes[i + 2] & 0xFF) << 8) | (bytes[i + 3] & 0xFF);
    }

    /** The chunk's root NBT compound, or {@code null} if the chunk was never generated. */
    public Map<String, Object> readChunk(int localX, int localZ) throws IOException {
        if (!hasChunk(localX, localZ)) {
            return null;
        }
        int loc = location(localX, localZ);
        int offset = (loc >>> 8) * SECTOR;
        if (loc >>> 8 < 2) {
            throw new IOException("Chunk " + localX + "," + localZ + " points into the header of " + path);
        }
        if (offset + 5 > bytes.length) {
            throw new IOException("Chunk " + localX + "," + localZ + " points past the end of " + path);
        }
        int length = ((bytes[offset] & 0xFF) << 24) | ((bytes[offset + 1] & 0xFF) << 16)
                | ((bytes[offset + 2] & 0xFF) << 8) | (bytes[offset + 3] & 0xFF);
        int compression = bytes[offset + 4] & 0xFF;

        InputStream raw;
        if ((compression & 0x80) != 0) {
            int cx = regionX * 32 + localX;
            int cz = regionZ * 32 + localZ;
            Path external = path.resolveSibling("c." + cx + "." + cz + ".mcc");
            raw = Files.newInputStream(external);
            compression &= 0x7F;
        } else {
            if (length < 1 || offset + 4 + length > bytes.length) {
                throw new IOException("Bad chunk length " + length + " in " + path);
            }
            raw = new ByteArrayInputStream(bytes, offset + 5, length - 1);
        }
        // Buffered, so the NBT reader's many small reads don't each call into the inflater.
        // The raw stream is closed even if decompressing can't start (a bad gzip header).
        try (raw; DataInputStream in = new DataInputStream(
                new BufferedInputStream(decompress(raw, compression), 1 << 16))) {
            return Nbt.readRoot(in);
        }
    }

    private static InputStream decompress(InputStream raw, int compression) throws IOException {
        return switch (compression) {
            case 1 -> new GZIPInputStream(raw);
            case 2 -> new InflaterInputStream(raw);
            case 3 -> raw;
            case 4 -> throw new IOException("LZ4-compressed chunks aren't supported. Set"
                    + " region-file-compression=deflate (the default) and re-save the world.");
            default -> throw new IOException("Unknown chunk compression " + compression);
        };
    }
}
