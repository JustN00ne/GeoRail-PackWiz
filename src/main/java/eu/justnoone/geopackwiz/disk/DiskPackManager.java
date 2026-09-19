package eu.justnoone.geopackwiz.disk;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import eu.justnoone.geopackwiz.GeoPackWiz;
import eu.justnoone.geopackwiz.io.network.GeopakClient;
import net.fabricmc.loader.api.FabricLoader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Manages writing decrypted resource pack ZIPs to disk at
 * {@code resourcepacks/}. All synced packs are merged into a single
 * {@code geopackwiz_merged.zip} file so Minecraft treats them as one
 * resource pack. A {@code .geopak-info.json} marker file in the game
 * directory lets the mod recognise which packs it wrote.
 */
public final class DiskPackManager {

    /** Filename of the single merged pack written to resourcepacks/. */
    private static final String MERGED_FILENAME = "geopackwiz_merged.zip";

    /** Marker file written in the game directory root. */
    private static final String MARKER_FILE = ".geopak-info.json";

    private DiskPackManager() {}

    // ---- Paths ----------------------------------------------------------------

    /** The resourcepacks folder (standard Minecraft location). */
    public static Path getResourcePackFolder() {
        return FabricLoader.getInstance().getGameDir().resolve("resourcepacks");
    }

    /** The marker file in the game directory root. */
    public static Path getMarkerPath() {
        return FabricLoader.getInstance().getGameDir().resolve(MARKER_FILE);
    }

    /** True when the marker file exists and the merged pack was written by this mod. */
    public static boolean isOurFolder(Path folder) {
        return Files.isRegularFile(getMarkerPath());
    }

    /**
     * True when we have written a valid merged pack zip to the resourcepacks
     * folder and the marker file confirms it. Prevents re-enabling when there
     * is nothing to enable.
     */
    public static boolean hasValidContent(Path folder) {
        if (!Files.isRegularFile(getMarkerPath())) return false;
        Path rpFolder = getResourcePackFolder();
        if (!Files.isDirectory(rpFolder)) return false;
        Path merged = rpFolder.resolve(MERGED_FILENAME);
        return Files.isRegularFile(merged);
    }

    /**
     * Returns the Minecraft pack ID for the single merged pack,
     * read from the marker file. Returns empty string if not present.
     */
    public static String getMergedPackId() {
        if (!hasValidContent(null)) return "";
        return "file/" + MERGED_FILENAME.substring(0, MERGED_FILENAME.length() - 4);
    }

    // ---- Write ----------------------------------------------------------------

    /**
     * Merges all given decrypted packs into a single {@code geopackwiz_merged.zip}
     * written directly into {@code resourcepacks/}. Earlier packs have lower
     * priority (overridden by later packs on file conflicts). The merged pack
     * contains a valid {@code pack.mcmeta} and uses the first pack's metadata.
     */
    public static void writePacksToDisk(List<GeopakClient.GeopakPack> packs,
                                         long totalBytes,
                                         java.util.function.DoubleConsumer onProgress) throws IOException {
        Path rpFolder = getResourcePackFolder();
        Files.createDirectories(rpFolder);

        // 1. Remove old merged pack from previous syncs
        cleanOldPacks(rpFolder);

        if (packs.isEmpty()) {
            Files.deleteIfExists(getMarkerPath());
            return;
        }

        // 2. Merge all pack ZIPs into one, later packs override earlier ones
        Map<String, byte[]> mergedEntries = new LinkedHashMap<>();
        byte[] packMcmeta = null;
        byte[] packPng = null;

        long processed = 0;
        for (GeopakClient.GeopakPack pack : packs) {
            if (pack.zip == null || pack.zip.length == 0) continue;
            try (ZipInputStream zis = new ZipInputStream(new java.io.ByteArrayInputStream(pack.zip))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.isDirectory()) continue;
                    String name = entry.getName();
                    // Ensure forward slashes
                    name = name.replace('\\', '/');

                    byte[] data = zis.readAllBytes();

                    // Strip leading "./" or "/"
                    while (name.startsWith("./") || name.startsWith("/")) {
                        name = name.substring(1);
                    }

                    if (name.equals("pack.mcmeta")) {
                        packMcmeta = data;
                    } else if (name.equals("pack.png")) {
                        packPng = data;
                    } else {
                        mergedEntries.put(name, data);
                    }
                }
            }
            processed += pack.zip.length;
            if (onProgress != null && totalBytes > 0) {
                onProgress.accept(Math.min(1.0, (double) processed / totalBytes));
            }
        }

        // 3. Generate pack.mcmeta if none was found
        if (packMcmeta == null) {
            packMcmeta = """
                    {
                      "pack": {
                        "pack_format": 15,
                        "description": "GeoRail synced resource packs"
                      }
                    }""".getBytes();
        }

        // 4. Write merged ZIP
        Path mergedPath = rpFolder.resolve(MERGED_FILENAME);
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(mergedPath))) {
            // Write pack.mcmeta first (Minecraft expects it)
            zos.putNextEntry(new ZipEntry("pack.mcmeta"));
            zos.write(packMcmeta);
            zos.closeEntry();

            // Write pack.png if present
            if (packPng != null) {
                zos.putNextEntry(new ZipEntry("pack.png"));
                zos.write(packPng);
                zos.closeEntry();
            }

            // Write all merged asset entries
            for (Map.Entry<String, byte[]> e : mergedEntries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }

        // 5. Write marker in game directory root
        writeMarker(packs);

        GeoPackWiz.LOGGER.info("GeoPackWiz: merged {} pack(s) into {} ({} entries)",
                packs.size(), mergedPath, mergedEntries.size());
    }

    // ---- Clean ----------------------------------------------------------------

    /**
     * Removes pack ZIPs from a previous sync from the resourcepacks folder.
     * Removes both the old per-pack files and the merged file.
     */
    private static void cleanOldPacks(Path rpFolder) throws IOException {
        if (!Files.isDirectory(rpFolder)) return;
        try (var stream = Files.list(rpFolder)) {
            for (Path entry : stream.toList()) {
                String name = entry.getFileName().toString();
                if (name.equals(MERGED_FILENAME)
                        || (name.startsWith("geopackwiz_") && name.endsWith(".zip"))) {
                    Files.deleteIfExists(entry);
                }
            }
        }
    }

    /** Public API: remove all packs written by this mod. */
    public static void removePackFolder() {
        try {
            Path rpFolder = getResourcePackFolder();
            cleanOldPacks(rpFolder);
            Files.deleteIfExists(getMarkerPath());
            GeoPackWiz.LOGGER.info("GeoPackWiz: removed synced pack files");
        } catch (Exception ignored) {
        }
    }

    // ---- Marker ---------------------------------------------------------------

    private static void writeMarker(List<GeopakClient.GeopakPack> packs) throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("version", 4);
        json.addProperty("mergedFile", MERGED_FILENAME);
        json.addProperty("packCount", packs.size());
        json.addProperty("syncTime", System.currentTimeMillis());
        json.addProperty("modVersion", GeoPackWiz.MOD_VERSION);
        json.addProperty("packFormat", 15);
        com.google.gson.JsonArray names = new com.google.gson.JsonArray();
        for (GeopakClient.GeopakPack p : packs) names.add(p.title());
        json.add("packNames", names);
        Files.writeString(getMarkerPath(), new Gson().toJson(json));
    }
}
