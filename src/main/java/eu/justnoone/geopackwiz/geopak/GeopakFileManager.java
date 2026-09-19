package eu.justnoone.geopackwiz.geopak;

import com.google.gson.JsonObject;

import eu.justnoone.geopackwiz.GeoPackWiz;
import eu.justnoone.geopackwiz.ServerConfig;
import eu.justnoone.geopackwiz.io.network.GeopakClient;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Manages physical .geopak files on disk.
 *
 * .geopak files are AES-256-GCM encrypted and safe to share — without the
 * secret key they are undecryptable gibberish. This manager handles:
 *
 *   - Saving downloaded .geopak bytes to the geopak/ folder
 *   - Listing all .geopak files on disk
 *   - Importing .geopak files from external sources (drag-and-drop, file copy)
 *   - Decrypting and extracting pack data from .geopak files
 *   - Removing .geopak files
 *
 * The geopak/ folder lives in the Minecraft game directory alongside
 * resourcepacks/ and config/.
 */
public final class GeopakFileManager {

    /** Relative path inside the game directory for .geopak storage. */
    private static final String GEOPAK_FOLDER = "geopak";

    /** File extension for encrypted pack files. */
    private static final String GEOPAK_EXTENSION = ".geopak";

    /** Metadata sidecar file extension. */
    private static final String META_EXTENSION = ".meta.json";

    private GeopakFileManager() {}

    // ---- Paths ----------------------------------------------------------------

    /** The geopak storage folder. */
    public static Path getGeopakFolder() {
        return FabricLoader.getInstance().getGameDir().resolve(GEOPAK_FOLDER);
    }

    /** Resolve a .geopak file path by pack UUID. */
    public static Path getGeopakPath(String packUuid) {
        String safe = packUuid.toLowerCase(Locale.ROOT).replaceAll("[^0-9a-f-]", "");
        return getGeopakFolder().resolve(safe + GEOPAK_EXTENSION);
    }

    /** Resolve the metadata sidecar path for a pack. */
    public static Path getMetaPath(String packUuid) {
        String safe = packUuid.toLowerCase(Locale.ROOT).replaceAll("[^0-9a-f-]", "");
        return getGeopakFolder().resolve(safe + META_EXTENSION);
    }

    // ---- Save -----------------------------------------------------------------

    /**
     * Save raw .geopak bytes to disk. The file is encrypted and safe to share.
     *
     * @param packUuid the pack's UUID (used as filename)
     * @param geopakBytes the raw encrypted .geopak data
     * @param meta optional metadata JSON to save alongside the file
     * @throws IOException on file system errors
     */
    public static void saveGeopak(String packUuid, byte[] geopakBytes, JsonObject meta) throws IOException {
        Path folder = getGeopakFolder();
        Files.createDirectories(folder);

        Path geopakFile = getGeopakPath(packUuid);
        Path tmpFile = geopakFile.resolveSibling(geopakFile.getFileName() + ".tmp");

        // Write the .geopak file
        Files.write(tmpFile, geopakBytes);
        try {
            Files.move(tmpFile, geopakFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailed) {
            Files.move(tmpFile, geopakFile, StandardCopyOption.REPLACE_EXISTING);
        }

        // Write metadata sidecar
        if (meta != null) {
            Path metaFile = getMetaPath(packUuid);
            Files.writeString(metaFile, meta.toString());
        }

        GeoPackWiz.LOGGER.info("GeoPackWiz: saved .geopak file for pack {} ({} bytes)", packUuid, geopakBytes.length);
    }

    /**
     * Save raw .geopak bytes to disk without metadata.
     */
    public static void saveGeopak(String packUuid, byte[] geopakBytes) throws IOException {
        saveGeopak(packUuid, geopakBytes, null);
    }

    // ---- Load -----------------------------------------------------------------

    /**
     * Load raw .geopak bytes from disk.
     *
     * @param packUuid the pack's UUID
     * @return the raw encrypted bytes, or null if not found
     * @throws IOException on file system errors
     */
    public static byte[] loadGeopak(String packUuid) throws IOException {
        Path file = getGeopakPath(packUuid);
        if (!Files.isRegularFile(file)) return null;
        return Files.readAllBytes(file);
    }

    /**
     * Check if a .geopak file exists for the given pack UUID.
     */
    public static boolean hasGeopak(String packUuid) {
        return Files.isRegularFile(getGeopakPath(packUuid));
    }

    /**
     * Get the file size of a .geopak file.
     *
     * @return size in bytes, or -1 if not found
     */
    public static long getGeopakSize(String packUuid) {
        Path file = getGeopakPath(packUuid);
        if (!Files.isRegularFile(file)) return -1;
        try {
            return Files.size(file);
        } catch (IOException e) {
            return -1;
        }
    }

    // ---- List -----------------------------------------------------------------

    /** Information about a .geopak file on disk. */
    public static record GeopakFileInfo(
        String packUuid,
        Path filePath,
        long fileSize,
        long lastModified,
        JsonObject metadata
    ) {}

    /**
     * List all .geopak files on disk.
     *
     * @return list of file info, sorted by last modified (newest first)
     */
    public static List<GeopakFileInfo> listGeopakFiles() {
        List<GeopakFileInfo> result = new ArrayList<>();
        Path folder = getGeopakFolder();
        if (!Files.isDirectory(folder)) return result;

        try (Stream<Path> stream = Files.list(folder)) {
            for (Path file : stream.toList()) {
                String name = file.getFileName().toString();
                if (!name.endsWith(GEOPAK_EXTENSION)) continue;
                if (name.endsWith(".tmp")) continue;

                String uuid = name.substring(0, name.length() - GEOPAK_EXTENSION.length());
                try {
                    long size = Files.size(file);
                    long modified = Files.getLastModifiedTime(file).toMillis();
                    JsonObject meta = loadMeta(uuid);
                    result.add(new GeopakFileInfo(uuid, file, size, modified, meta));
                } catch (IOException e) {
                    GeoPackWiz.LOGGER.warn("GeoPackWiz: could not read .geopak file info for {}: {}", uuid, e.getMessage());
                }
            }
        } catch (IOException e) {
            GeoPackWiz.LOGGER.warn("GeoPackWiz: could not list geopak folder: {}", e.getMessage());
        }

        result.sort(Comparator.comparingLong(GeopakFileInfo::lastModified).reversed());
        return result;
    }

    // ---- Import ---------------------------------------------------------------

    /**
     * Import a .geopak file from an external path (e.g. drag-and-drop, file copy).
     * Validates the file format before importing.
     *
     * @param sourcePath path to the .geopak file to import
     * @return the pack UUID if successful
     * @throws IOException if the file is invalid or cannot be imported
     */
    public static String importGeopak(Path sourcePath) throws IOException {
        if (!Files.isRegularFile(sourcePath)) {
            throw new IOException("Source file does not exist: " + sourcePath);
        }

        byte[] data = Files.readAllBytes(sourcePath);
        validateGeopakFormat(data);

        // Decrypt to extract the UUID from metadata
        byte[] secretKey = GeopakClient.keyFromHex(ServerConfig.SECRET_KEY);
        List<GeopakClient.GeopakPack> packs;
        try {
            packs = GeopakClient.decryptAndParse(data, secretKey);
        } catch (Exception e) {
            throw new IOException("Failed to decrypt .geopak file: " + e.getMessage(), e);
        }

        if (packs.isEmpty()) {
            throw new IOException("The .geopak file contains no packs.");
        }

        // Use the first pack's UUID
        GeopakClient.GeopakPack firstPack = packs.get(0);
        String uuid = firstPack.uuid();

        // Build metadata
        JsonObject meta = new JsonObject();
        meta.addProperty("uuid", uuid);
        meta.addProperty("title", firstPack.title());
        meta.addProperty("importedAt", System.currentTimeMillis());
        meta.addProperty("sourceFile", sourcePath.getFileName().toString());
        if (firstPack.meta.has("zipSha256")) {
            meta.addProperty("zipSha256", firstPack.meta.get("zipSha256").getAsString());
        }

        // Save to geopak folder
        saveGeopak(uuid, data, meta);
        return uuid;
    }

    /**
     * Validate that a byte array is a valid .geopak file (correct magic and version).
     */
    public static void validateGeopakFormat(byte[] data) throws IOException {
        if (data.length < 35) {
            throw new IOException("File is too small to be a valid .geopak file.");
        }
        if (data[0] != 'G' || data[1] != 'E' || data[2] != 'O' || data[3] != 'P' || data[4] != 'A' || data[5] != 'K') {
            throw new IOException("File is not a valid .geopak file (bad magic header).");
        }
        int version = data[6];
        if (version != 1 && version != 2) {
            throw new IOException("Unsupported .geopak version " + version);
        }
    }

    // ---- Decrypt & Extract ----------------------------------------------------

    /**
     * Decrypt a .geopak file and return the decrypted pack data.
     *
     * @param packUuid the pack UUID to decrypt
     * @return list of decrypted packs, or null if not found
     * @throws IOException on decryption or file errors
     */
    public static List<GeopakClient.GeopakPack> decryptGeopak(String packUuid) throws IOException {
        byte[] data = loadGeopak(packUuid);
        if (data == null) return null;

        byte[] secretKey = GeopakClient.keyFromHex(ServerConfig.SECRET_KEY);
        try {
            List<GeopakClient.GeopakPack> packs = GeopakClient.decryptAndParse(data, secretKey);
            java.util.Arrays.fill(data, (byte) 0); // wipe from memory
            return packs;
        } catch (Exception e) {
            java.util.Arrays.fill(data, (byte) 0);
            throw new IOException("Failed to decrypt .geopak file for pack " + packUuid + ": " + e.getMessage(), e);
        }
    }

    // ---- Remove ---------------------------------------------------------------

    /**
     * Remove a .geopak file and its metadata sidecar.
     *
     * @param packUuid the pack UUID to remove
     * @return true if the file was removed
     */
    public static boolean removeGeopak(String packUuid) throws IOException {
        Path geopakFile = getGeopakPath(packUuid);
        Path metaFile = getMetaPath(packUuid);
        boolean removed = Files.deleteIfExists(geopakFile);
        Files.deleteIfExists(metaFile);
        if (removed) {
            GeoPackWiz.LOGGER.info("GeoPackWiz: removed .geopak file for pack {}", packUuid);
        }
        return removed;
    }

    /**
     * Remove all .geopak files (used for cleanup/reset).
     */
    public static void removeAllGeopaks() throws IOException {
        Path folder = getGeopakFolder();
        if (!Files.isDirectory(folder)) return;

        try (Stream<Path> stream = Files.list(folder)) {
            for (Path file : stream.toList()) {
                String name = file.getFileName().toString();
                if (name.endsWith(GEOPAK_EXTENSION) || name.endsWith(META_EXTENSION)) {
                    Files.deleteIfExists(file);
                }
            }
        }
        GeoPackWiz.LOGGER.info("GeoPackWiz: removed all .geopak files");
    }

    // ---- Metadata -------------------------------------------------------------

    /**
     * Load metadata sidecar for a pack.
     *
     * @return the metadata JSON, or null if not found
     */
    public static JsonObject loadMeta(String packUuid) {
        Path metaFile = getMetaPath(packUuid);
        if (!Files.isRegularFile(metaFile)) return null;
        try {
            String content = Files.readString(metaFile);
            return GeoPackWiz.JSON_PARSER.parse(content).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    // ---- Helpers --------------------------------------------------------------

    /**
     * Get a human-readable summary of all .geopak files on disk.
     */
    public static String getSummary() {
        List<GeopakFileInfo> files = listGeopakFiles();
        if (files.isEmpty()) {
            return "No .geopak files on disk.";
        }
        long totalSize = files.stream().mapToLong(GeopakFileInfo::fileSize).sum();
        return files.size() + " .geopak file(s) on disk (" + formatBytes(totalSize) + " total)";
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1048576) return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
        if (bytes < 1073741824) return String.format(Locale.ROOT, "%.1f MiB", bytes / 1048576.0);
        return String.format(Locale.ROOT, "%.2f GiB", bytes / 1073741824.0);
    }
}
