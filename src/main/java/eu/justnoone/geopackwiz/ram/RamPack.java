package eu.justnoone.geopackwiz.ram;

import com.google.gson.JsonObject;

import eu.justnoone.geopackwiz.GeoPackWiz;
import eu.justnoone.geopackwiz.io.network.GeopakClient;

import net.minecraft.DetectedVersion;
import net.minecraft.server.packs.PackType;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Holds the decrypted GeoRail pack contents in memory. Nothing is ever written
 * to disk in a readable form: zips are expanded straight into this structure
 * and Minecraft reads the assets from RAM via {@link RamPackResources}.
 *
 * <p>All asset bytes are stored as {@link SecureByteArray} instances whose
 * contents are XOR-obfuscated in the heap so the raw data cannot be trivially
 * read from a memory dump or debugger. The obfuscation key is held in a
 * separate allocation so the two never sit adjacent.
 */
public final class RamPack {

    /** Id of the pack as it appears in the resource-pack selection. */
    public static final String PACK_ID = "georail";

    /** Immutable snapshot of one loaded pack generation. */
    public static final class Data {
        /** namespace -> (path relative to assets/<ns>/ -> obfuscated file bytes) */
        public final Map<String, Map<String, SecureByteArray>> secureAssets;
        public final SecureByteArray securePackMcmeta;
        public final SecureByteArray securePackPng;

        Data(Map<String, Map<String, SecureByteArray>> secureAssets,
             SecureByteArray securePackMcmeta,
             SecureByteArray securePackPng) {
            this.secureAssets = secureAssets;
            this.securePackMcmeta = securePackMcmeta;
            this.securePackPng = securePackPng;
        }

        /** Convenience: deobfuscated assets for code that needs plain bytes. */
        public Map<String, Map<String, byte[]>> getAssets() {
            Map<String, Map<String, byte[]>> out = new HashMap<>();
            for (var ns : secureAssets.entrySet()) {
                Map<String, byte[]> nsMap = new LinkedHashMap<>();
                for (var entry : ns.getValue().entrySet()) {
                    nsMap.put(entry.getKey(), entry.getValue().get());
                }
                out.put(ns.getKey(), nsMap);
            }
            return out;
        }

        public byte[] getPackMcmeta() {
            return securePackMcmeta != null ? securePackMcmeta.get() : null;
        }

        public byte[] getPackPng() {
            return securePackPng != null ? securePackPng.get() : null;
        }

        /**
         * Overwrite all obfuscated data with zeros. After this call the Data
         * object is unusable and should be discarded.
         */
        public void wipe() {
            for (var ns : secureAssets.values()) {
                for (var entry : ns.values()) {
                    entry.wipe();
                }
            }
            if (securePackMcmeta != null) securePackMcmeta.wipe();
            if (securePackPng != null) securePackPng.wipe();
        }
    }

    private static volatile Data current;
    private static volatile boolean loaded = false;

    private RamPack() {
    }

    public static boolean isLoaded() {
        return loaded;
    }

    public static Data data() {
        return current;
    }

    /**
     * Install a new pack generation. Safe to call between resource reloads.
     * If a previous generation exists, it is wiped first.
     */
    public static void setData(Data data) {
        Data old = current;
        current = data;
        loaded = data != null;
        // Wipe old data after the new generation is published, so any in-flight
        // reads from the old generation still complete (they hold their own
        // deobfuscated snapshot).
        if (old != null && old != data) {
            new Thread(() -> {
                try { Thread.sleep(2000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                old.wipe();
            }, "GeoPackWiz-SecureWipe").start();
        }
    }

    /**
     * Merge decrypted packs (in {@code packs}, already in website order, newest
     * first) into one in-memory pack. Where two packs provide the same file the
     * newer pack wins.
     */
    public static Data mergePacks(List<GeopakClient.GeopakPack> packs) throws IOException {
        Map<String, Map<String, byte[]>> assets = new HashMap<>();
        byte[] packPng = null;

        // Walk oldest -> newest so that put() lets newer packs override duplicates.
        for (int i = packs.size() - 1; i >= 0; i--) {
            GeopakClient.GeopakPack pack = packs.get(i);
            byte[] png = absorbZip(assets, pack.zip);
            if (png != null && packPng == null) packPng = png;
        }
        if (packPng == null) packPng = null; // pack.png is optional

        int packFormat;
        try {
            packFormat = DetectedVersion.tryDetectVersion().getPackVersion(PackType.CLIENT_RESOURCES);
        } catch (Exception ex) {
            packFormat = 15; // 1.20.x fallback
        }
        JsonObject packSection = new JsonObject();
        packSection.addProperty("pack_format", packFormat);
        packSection.addProperty("description", "GeoRail pack — loaded in memory by GeoPackWiz");
        JsonObject root = new JsonObject();
        root.add("pack", packSection);
        byte[] mcmeta = root.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);

        // Wrap everything in SecureByteArray to obfuscate in the heap
        Map<String, Map<String, SecureByteArray>> secureAssets = new HashMap<>();
        for (var nsEntry : assets.entrySet()) {
            Map<String, SecureByteArray> nsMap = new LinkedHashMap<>();
            for (var fileEntry : nsEntry.getValue().entrySet()) {
                nsMap.put(fileEntry.getKey(), new SecureByteArray(fileEntry.getValue()));
                // The original plain byte[] is zeroed by SecureByteArray constructor
            }
            secureAssets.put(nsEntry.getKey(), nsMap);
        }

        Data data = new Data(
                secureAssets,
                new SecureByteArray(mcmeta),
                packPng != null ? new SecureByteArray(packPng) : null
        );
        setData(data);
        return data;
    }

    /**
     * Expand one zip into the assets map. Returns the zip's root {@code pack.png}
     * when present. Malformed entries are skipped (never trusted).
     *
     * Entry names are normalized the way Minecraft itself does on disk, so packs
     * created on any platform load fully: backslashes from Windows zip tools
     * become forward slashes, a leading {@code ./} or {@code /} is stripped, and
     * namespaces are lower-cased (Minecraft identifiers are case-insensitive in
     * lookups, so an {@code assets/MTR/…} folder must resolve as {@code mtr:…}).
     * Only real path traversal ({@code ..} segments) is rejected.
     */
    private static byte[] absorbZip(Map<String, Map<String, byte[]>> assets, byte[] zip) throws IOException {
        byte[] png = null;
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory()) {
                    zis.closeEntry();
                    continue;
                }
                // Normalize separators and prefixes used by Windows/macOS archivers.
                name = name.replace('\\', '/');
                while (name.startsWith("/")) name = name.substring(1);
                while (name.startsWith("./")) name = name.substring(2);
                if (name.isEmpty()) {
                    zis.closeEntry();
                    continue;
                }
                if (!name.startsWith("assets/")) {
                    if (name.equals("pack.png") && png == null) {
                        png = zis.readAllBytes();
                    }
                    zis.closeEntry();
                    continue;
                }
                String rest = name.substring("assets/".length());
                int slash = rest.indexOf('/');
                if (slash <= 0 || slash == rest.length() - 1) {
                    zis.closeEntry();
                    continue;
                }
                String namespace = rest.substring(0, slash).toLowerCase(Locale.ROOT);
                String path = rest.substring(slash + 1).toLowerCase(Locale.ROOT);
                if (path.isEmpty() || hasTraversal(path) || hasJunkSegment(path)) {
                    zis.closeEntry();
                    continue;
                }
                byte[] content = zis.readAllBytes();
                assets.computeIfAbsent(namespace, k -> new LinkedHashMap<>()).put(path, content);
                zis.closeEntry();
            }
        } catch (Exception ex) {
            // A corrupt zip should not take the whole sync down silently; the pack
            // entry just contributes nothing.
            GeoPackWiz.LOGGER.warn("GeoPackWiz: could not read one pack archive into memory", ex);
        }
        return png;
    }

    /** True when any path segment is exactly {@code ..} (path traversal). */
    private static boolean hasTraversal(String path) {
        for (String segment : path.split("/")) {
            if (segment.equals("..")) return true;
        }
        return false;
    }

    /**
     * Skip obviously-junk segments that some creators zip into their packs
     * (a whole launcher directory, macOS metadata, ...). Minecraft never
     * addresses hidden/meta files, so dropping them only removes noise.
     */
    private static boolean hasJunkSegment(String path) {
        for (String segment : path.split("/")) {
            if (segment.isEmpty()) continue;
            if (segment.equals("__MACOSX")) return true;
            if (segment.charAt(0) == '.') return true; // .var, .git, .DS_Store, ...
        }
        return false;
    }

    /** Diagnostics: count of assets loaded, used by the progress screen footer. */
    public static int assetCount() {
        Data data = current;
        if (data == null) return 0;
        int n = 0;
        for (var ns : data.secureAssets.values()) n += ns.size();
        return n;
    }
}
