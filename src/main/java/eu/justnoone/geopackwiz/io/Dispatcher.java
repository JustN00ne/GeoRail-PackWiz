package eu.justnoone.geopackwiz.io;

import com.google.gson.JsonObject;

import eu.justnoone.geopackwiz.GeoPackWiz;
import eu.justnoone.geopackwiz.ServerConfig;
import eu.justnoone.geopackwiz.io.network.GeopakClient;
import eu.justnoone.geopackwiz.geopak.GeopakFileManager;
import eu.justnoone.geopackwiz.ram.RamPack;
import eu.justnoone.geopackwiz.sync.SyncCancelledException;
import eu.justnoone.geopackwiz.sync.SyncState;

import net.fabricmc.loader.api.FabricLoader;

import org.apache.commons.codec.binary.Hex;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Synchronises the GeoRail resource packs from the GeoPak website to disk:
 *
 *   1. discover packs via GET /api/packs (X-API-Key)
 *   2. skip packs whose encrypted local cache already matches the website
 *   3. download only the new / updated packs — several at once, like a
 *      multi-connection file transfer
 *   4. decrypt + verify each zip's sha256, write them to resourcepacks/SyncedPack/
 *   5. Minecraft loads the packs from disk as regular resource packs
 */
public class Dispatcher {

    /** How many packs are fetched at the same time (FileZilla-style). */
    public static final int CONCURRENT_DOWNLOADS = 3;

    private static final byte[] CACHE_MAGIC = "GEOPKC2".getBytes(StandardCharsets.US_ASCII);
    private static final int CACHE_VERSION = 2;

    /** Only push UI updates at most this often, even with N parallel downloads. */
    private static final long UI_THROTTLE_MS = 150;

    // ---- One run --------------------------------------------------------------

    public void runSync(ProgressReceiver cb) throws Exception {
        checkAborted(cb);
        String baseUrl = GeopakClient.stripTrailingSlash(ServerConfig.WEBSITE_BASE_URL);
        String apiKey = ServerConfig.API_KEY;
        byte[] secretKey = GeopakClient.keyFromHex(ServerConfig.SECRET_KEY);

        cb.printLog("GeoRail Pack Sync v" + GeoPackWiz.MOD_VERSION);
        cb.printLog("Server: " + baseUrl);
        cb.printLog("Mode: disk — packs are written to resourcepacks/SyncedPack/");
        cb.printLog("Geopak files saved to: " + GeopakFileManager.getGeopakFolder());
        cb.printLog("");

        // ---- 1. Discover -----------------------------------------------------
        List<GeopakClient.RemotePack> all;
        try {
            cb.printLog("Fetching pack list from the website ...");
            all = GeopakClient.fetchPackList(baseUrl, apiKey);
            cb.amendLastLog("Done (" + all.size() + " pack(s) found)");
        } catch (Exception listError) {
            // Website unreachable (no internet, DNS, server down ...). Fall back
            // to the most recent downloaded copies so the packs still work.
            cb.printLog("The pack website could not be reached (" + rootMessage(listError) + ").");
            List<GeopakClient.GeopakPack> cached = loadCachedOffline(secretKey, cb);
            if (cached.isEmpty()) throw listError;
            cb.printLog("Using the most recently downloaded pack copies already on this computer.");
            finishLoad(cached, cb);
            return;
        }
        checkAborted(cb);

        List<GeopakClient.RemotePack> chosen = selectPacks(all, cb);

        // ---- 2. Serve from cache what is unchanged --------------------------
        long totalChosenBytes = 0;
        for (GeopakClient.RemotePack p : chosen) totalChosenBytes += p.zipSize;

        final Map<String, GeopakPackRow> rows = new ConcurrentHashMap<>();
        for (GeopakClient.RemotePack p : chosen) rows.put(p.id, new GeopakPackRow(p));

        AtomicLong doneBytes = new AtomicLong(0); // bytes already on disk (fresh cache counts now)
        Map<String, GeopakClient.GeopakPack> ready = new LinkedHashMap<>();
        List<GeopakClient.RemotePack> toDownload = new ArrayList<>();

        for (GeopakClient.RemotePack p : chosen) {
            GeopakClient.GeopakPack fromCache = readFreshCache(p, secretKey);
            if (fromCache != null) {
                ready.put(p.id, fromCache);
                doneBytes.addAndGet(p.zipSize);
                rows.get(p.id).state = RowState.CACHED;
                rows.get(p.id).received = p.zipSize;
            } else {
                toDownload.add(p);
            }
        }
        pushUi(cb, rows, doneBytes.get(), totalChosenBytes, true);

        // ---- 3. Download only what is new / updated, in parallel -------------
        if (!toDownload.isEmpty()) {
            cb.printLog(toDownload.size() == 1
                    ? "1 pack needs downloading (updated or not cached yet) ..."
                    : "Downloading " + toDownload.size() + " new or updated pack(s) at once (up to "
                    + Math.min(CONCURRENT_DOWNLOADS, toDownload.size()) + " parallel) ...");
            cb.setInfo("", "Downloading ...");
            DownloadOutcome outcome = downloadPacks(toDownload, secretKey, cb, rows, doneBytes, totalChosenBytes);
            checkAborted(cb);
            pushUi(cb, rows, doneBytes.get(), totalChosenBytes, true);

            // Packs that failed: reuse an older cached copy if we still have one,
            // otherwise mark them skipped so a bad network can't break everything.
            int staleUsed = 0;
            for (GeopakClient.RemotePack p : toDownload) {
                if (outcome.results.containsKey(p.id)) {
                    ready.put(p.id, outcome.results.get(p.id));
                    continue;
                }
                GeopakClient.GeopakPack stale = readStaleCache(p, secretKey);
                if (stale != null) {
                    ready.put(p.id, stale);
                    rows.get(p.id).state = RowState.CACHED;
                    staleUsed++;
                } else {
                    rows.get(p.id).state = RowState.ERROR;
                }
            }
            if (!outcome.failures.isEmpty() || staleUsed > 0) {
                cb.printLog("Some downloads did not complete; using the best cached copies available.");
            }
            if (staleUsed > 0) {
                cb.printLog(staleUsed + " pack(s) were loaded from an older cached copy.");
            }
            checkAborted(cb);
        } else {
            cb.printLog("All " + chosen.size() + " pack(s) are already up to date on this computer — nothing to download.");
        }

        for (GeopakClient.RemotePack p : chosen) {
            if (!ready.containsKey(p.id)) {
                cb.printLog("Skipped \"" + p.title + "\" — no downloadable or cached copy is available.");
            }
        }
        if (ready.isEmpty()) {
            throw new IOException("No pack data could be downloaded or loaded from the cache.");
        }

        // Website order (newest first) so newer packs override older ones.
        List<GeopakClient.GeopakPack> ordered = new ArrayList<>();
        for (GeopakClient.RemotePack p : chosen) {
            GeopakClient.GeopakPack gp = ready.remove(p.id);
            if (gp != null) ordered.add(gp);
        }
        ordered.addAll(ready.values());

        // Old caches (packs removed from the website, legacy combined files from
        // earlier builds) are only cleaned after a successful sync.
        pruneCaches(all);
        finishLoad(ordered, cb);
    }

    // ---- Merge into RAM + bookkeeping ---------------------------------------

    private void finishLoad(List<GeopakClient.GeopakPack> ordered, ProgressReceiver cb) throws Exception {
        checkAborted(cb);
        cb.setProgress(0.99f, 0);
        cb.printLog("Merging " + ordered.size() + " pack(s) into RAM ...");

        // Merge all packs into the in-memory RamPack (primary source).
        // RamPack.mergePacks() walks oldest->newest so newer packs override
        // older ones on path conflicts, then wraps everything in SecureByteArray.
        RamPack.Data ramData = RamPack.mergePacks(ordered);
        cb.amendLastLog("Merged " + ordered.size() + " pack(s) into RAM.");

        // Wipe the raw zip bytes from all packs now that they're in RAM.
        for (GeopakClient.GeopakPack pack : ordered) {
            if (pack.zip != null) java.util.Arrays.fill(pack.zip, (byte) 0);
        }

        try {
            GeoPackWiz.CONFIG.lastSyncTime.value = System.currentTimeMillis();
            GeoPackWiz.CONFIG.lastSyncTime.persist();
            GeoPackWiz.CONFIG.save();
        } catch (IOException ignored) {
        }

        cb.setProgress(1f, 0);
        cb.setInfo("", "");
        cb.printLog("");
        cb.printLog("Done! The GeoRail pack is loaded in RAM — it applies while you are on play.georail.eu.");
    }

    // ---- Selection -----------------------------------------------------------

    private List<GeopakClient.RemotePack> selectPacks(List<GeopakClient.RemotePack> all, ProgressReceiver cb)
            throws Exception {
        List<String> selected = GeoPackWiz.CONFIG.websitePacks.value;
        List<GeopakClient.RemotePack> chosen = new ArrayList<>();
        for (GeopakClient.RemotePack p : all) {
            if (selected.isEmpty() || selected.contains(p.id)) chosen.add(p);
        }
        if (chosen.isEmpty()) {
            throw new IOException("No packs are selected. Open the mod config screen and tick at least one pack.");
        }
        cb.printLog("Syncing " + chosen.size() + " pack(s).");
        cb.setProgress(0, 0);
        return chosen;
    }

    // ---- Parallel download ---------------------------------------------------

    private static final class DownloadOutcome {
        final Map<String, GeopakClient.GeopakPack> results = new ConcurrentHashMap<>();
        final Map<String, Throwable> failures = new ConcurrentHashMap<>();
    }

    private DownloadOutcome downloadPacks(List<GeopakClient.RemotePack> packs, byte[] secretKey, ProgressReceiver cb,
                                          Map<String, GeopakPackRow> rows, AtomicLong doneBytes, long totalChosenBytes)
            throws Exception {
        DownloadOutcome outcome = new DownloadOutcome();
        if (packs.isEmpty()) return outcome;

        int threads = Math.max(1, Math.min(CONCURRENT_DOWNLOADS, packs.size()));
        ExecutorService pool = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "GeoPackWiz-Download");
            if (cb instanceof SyncState syncState) syncState.registerWorker(thread);
            return thread;
        });

        try {
            List<Future<?>> futures = new ArrayList<>();
            for (GeopakClient.RemotePack pack : packs) {
                futures.add(pool.submit(() -> downloadOne(pack, secretKey, cb, outcome, rows, doneBytes, totalChosenBytes)));
            }

            for (Future<?> future : futures) {
                while (true) {
                    if (cb.isAborted()) throw new SyncCancelledException();
                    try {
                        future.get(100, TimeUnit.MILLISECONDS);
                        break;
                    } catch (TimeoutException ignored) {
                        // still running — poll the abort flag again
                    } catch (ExecutionException ex) {
                        Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                        if (cause instanceof SyncCancelledException) throw (SyncCancelledException) cause;
                        throw new IOException("Download worker failed: " + cause.getMessage(), cause);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new SyncCancelledException(ex);
                    }
                }
            }
            return outcome;
        } finally {
            pool.shutdownNow();
        }
    }

    private void downloadOne(GeopakClient.RemotePack pack, byte[] secretKey, ProgressReceiver cb,
                             DownloadOutcome outcome, Map<String, GeopakPackRow> rows,
                             AtomicLong doneBytes, long totalChosenBytes) {
        GeopakPackRow row = rows.get(pack.id);
        try {
            if (cb.isAborted()) throw new SyncCancelledException();
            byte[] raw = GeopakClient.requestGeopak(ServerConfig.WEBSITE_BASE_URL, ServerConfig.API_KEY,
                    List.of(pack.id), cb, (received, total) -> {
                        if (received <= row.received) return;
                        long delta = received - row.received;
                        row.received = received;
                        row.state = RowState.DOWNLOADING;
                        doneBytes.addAndGet(delta);
                        pushUi(cb, rows, doneBytes.get(), totalChosenBytes, false);
                    });
            if (cb.isAborted()) throw new SyncCancelledException();

            // Save the raw .geopak file to disk for sharing (encrypted, safe to share)
            try {
                GeopakFileManager.saveGeopak(pack.id, raw);
            } catch (IOException saveError) {
                cb.printLog("Warning: could not save .geopak file for \"" + pack.title + "\": " + rootMessage(saveError));
            }

            // Write the encrypted cache BEFORE decrypting (cache needs the raw bytes)
            writeCache(pack, raw);

            List<GeopakClient.GeopakPack> parsed;
            try {
                parsed = GeopakClient.decryptAndParse(raw, secretKey);
            } catch (IOException decryptError) {
                throw new IOException("Downloaded pack \"" + pack.title
                        + "\" could not be decrypted: " + rootMessage(decryptError), decryptError);
            }
            // Wipe the raw encrypted bytes now that we've decrypted and parsed them.
            java.util.Arrays.fill(raw, (byte) 0);
            GeopakClient.GeopakPack matched = null;
            for (GeopakClient.GeopakPack parsedPack : parsed) {
                if (parsedPack.uuid().equalsIgnoreCase(pack.id)) {
                    matched = parsedPack;
                    break;
                }
            }
            if (matched == null) {
                throw new IOException("The server answered with a different pack than requested.");
            }
            GeopakPackRow doneRow = rows.get(pack.id);
            long toAdd = Math.max(0, pack.zipSize - doneRow.received); // last chunk may be < 64 KiB
            doneRow.received = pack.zipSize;
            doneRow.state = RowState.DONE;
            doneBytes.addAndGet(toAdd);
            pushUi(cb, rows, doneBytes.get(), totalChosenBytes, true);
            outcome.results.put(pack.id, matched);
        } catch (SyncCancelledException ex) {
            throw ex;
        } catch (GeopakClient.GeopakMissingException missing) {
            outcome.failures.put(pack.id, missing);
            row.state = RowState.ERROR;
            pushUi(cb, rows, doneBytes.get(), totalChosenBytes, true);
        } catch (Throwable t) {
            if (cb.isAborted()) throw new SyncCancelledException(t);
            outcome.failures.put(pack.id, t);
            row.state = RowState.ERROR;
            pushUi(cb, rows, doneBytes.get(), totalChosenBytes, true);
        }
    }

    // ---- Live UI state --------------------------------------------------------

    private enum RowState { QUEUED, DOWNLOADING, DONE, CACHED, ERROR }

    private static final class GeopakPackRow {
        final String title;
        final long zipSize;
        volatile long received;
        volatile RowState state = RowState.QUEUED;

        GeopakPackRow(GeopakClient.RemotePack pack) {
            this.title = pack.title;
            this.zipSize = pack.zipSize;
        }
    }

    private final Object uiLock = new Object();
    private long lastUiPush;

    /** Rebuild the pack rows + aggregate progress and hand them to the UI. */
    private void pushUi(ProgressReceiver cb, Map<String, GeopakPackRow> rows, long doneBytes,
                        long totalBytes, boolean force) {
        long now = System.currentTimeMillis();
        synchronized (uiLock) {
            if (!force && now - lastUiPush < UI_THROTTLE_MS) return;
            lastUiPush = now;
        }

        List<String> lines = new ArrayList<>();
        for (GeopakPackRow row : rows.values()) {
            lines.add(formatRow(row));
        }
        try {
            cb.setPackStatus(lines);
        } catch (RuntimeException ignored) {
        }

        float fraction = totalBytes > 0 ? (float) Math.min(1.0, doneBytes / (double) totalBytes) : 0f;
        try {
            cb.setProgress(fraction, 0);
            if (doneBytes < totalBytes) {
                cb.setInfo("", String.format("%d%% · %s of %s",
                        Math.round(fraction * 100f), formatBytes(doneBytes), formatBytes(totalBytes)));
            }
        } catch (RuntimeException ignored) {
        }
    }

    private static String formatRow(GeopakPackRow row) {
        String title = row.title.length() > 34 ? row.title.substring(0, 34) + "…" : row.title;
        return switch (row.state) {
            case QUEUED -> "[..] " + title;
            case DOWNLOADING -> {
                int pct = row.zipSize > 0 ? (int) (row.received * 100 / row.zipSize) : 0;
                yield String.format("[%3d%%] %s", Math.min(100, pct), title);
            }
            case DONE -> "[OK] " + title;
            case CACHED -> "[cached] " + title;
            case ERROR -> "[ERR] " + title;
        };
    }

    // ---- Per-pack encrypted cache --------------------------------------------
    // One file per pack: magic + version + uuid + the pack's sha256 (from the
    // website at download time) + the raw encrypted .geopak bytes. When the
    // website still lists the same sha256 the pack is reused without a download.

    private static Path cacheDir() {
        return FabricLoader.getInstance().getGameDir().resolve(".geopak-cache");
    }

    private static Path cacheFile(String uuid) {
        String name = uuid.toLowerCase(Locale.ROOT).replaceAll("[^0-9a-f-]", "");
        return cacheDir().resolve(name + ".gpkc");
    }

    private static void writeCache(GeopakClient.RemotePack pack, byte[] raw) throws IOException {
        Path file = cacheFile(pack.id);
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
            dos.write(CACHE_MAGIC);
            dos.writeInt(CACHE_VERSION);
            dos.writeUTF(pack.id.toLowerCase(Locale.ROOT));
            dos.writeUTF(pack.zipSha256 == null ? "" : pack.zipSha256.toLowerCase(Locale.ROOT));
            dos.writeInt(raw.length);
            dos.write(raw);
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailed) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Cache header (uuid + sha) without loading the whole encrypted body. */
    private static String[] readCacheHeader(Path file) throws IOException {
        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            byte[] magic = dis.readNBytes(CACHE_MAGIC.length);
            if (magic.length != CACHE_MAGIC.length || !MessageDigest.isEqual(magic, CACHE_MAGIC)) {
                throw new IOException("cache header mismatch");
            }
            if (dis.readInt() != CACHE_VERSION) throw new IOException("unsupported cache version");
            String uuid = dis.readUTF();
            String sha = dis.readUTF();
            return new String[] { uuid, sha };
        }
    }

    private static byte[] readCacheBody(Path file) throws IOException {
        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            byte[] magic = dis.readNBytes(CACHE_MAGIC.length);
            if (magic.length != CACHE_MAGIC.length || !MessageDigest.isEqual(magic, CACHE_MAGIC)) {
                throw new IOException("cache header mismatch");
            }
            if (dis.readInt() != CACHE_VERSION) throw new IOException("unsupported cache version");
            dis.readUTF(); // uuid
            dis.readUTF(); // sha
            int len = dis.readInt();
            if (len <= 0 || len > 2L * 1024 * 1024 * 1024) throw new IOException("implausible cache size");
            byte[] raw = new byte[len];
            dis.readFully(raw);
            return raw;
        }
    }

    /** Decrypt a cache file, returning the pack only when it matches the uuid. */
    private static GeopakClient.GeopakPack decryptCache(Path file, String wantUuid, byte[] secretKey) {
        try {
            byte[] raw = readCacheBody(file);
            for (GeopakClient.GeopakPack pack : GeopakClient.decryptAndParse(raw, secretKey)) {
                if (pack.uuid().equalsIgnoreCase(wantUuid)) {
                    java.util.Arrays.fill(raw, (byte) 0);
                    return pack;
                }
            }
            java.util.Arrays.fill(raw, (byte) 0);
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Cache whose sha256 still matches the website -> use without downloading. */
    private static GeopakClient.GeopakPack readFreshCache(GeopakClient.RemotePack pack, byte[] secretKey) {
        Path file = cacheFile(pack.id);
        if (!Files.isRegularFile(file)) return null;
        try {
            String[] header = readCacheHeader(file);
            if (pack.zipSha256 == null || pack.zipSha256.isEmpty()
                    || !pack.zipSha256.equalsIgnoreCase(header[1])) return null;
            return decryptCache(file, pack.id, secretKey);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Any cache copy (even an older version) — used when a download fails. */
    private static GeopakClient.GeopakPack readStaleCache(GeopakClient.RemotePack pack, byte[] secretKey) {
        Path file = cacheFile(pack.id);
        if (!Files.isRegularFile(file)) return null;
        return decryptCache(file, pack.id, secretKey);
    }

    /** Offline fallback: decrypt every per-pack cache we have for the selection. */
    private static List<GeopakClient.GeopakPack> loadCachedOffline(byte[] secretKey, ProgressReceiver cb) {
        List<GeopakClient.GeopakPack> packs = new ArrayList<>();
        List<String> selected = GeoPackWiz.CONFIG.websitePacks.value;
        Map<String, GeopakClient.GeopakPack> byUuid = new LinkedHashMap<>();

        List<Path> files;
        try (Stream<Path> stream = Files.list(cacheDir())) {
            files = stream
                    .filter(p -> p.getFileName().toString().endsWith(".gpkc"))
                    .filter(p -> !p.getFileName().toString().endsWith(".tmp"))
                    .sorted(Comparator.comparingLong(p -> -p.toFile().lastModified()))
                    .toList();
        } catch (IOException ex) {
            return packs;
        }

        for (Path file : files) {
            try {
                String[] header = readCacheHeader(file);
                String uuid = header[0];
                if (!selected.isEmpty() && !selected.contains(uuid)) continue;
                if (byUuid.containsKey(uuid)) continue;
                byte[] raw = readCacheBody(file);
                for (GeopakClient.GeopakPack pack : GeopakClient.decryptAndParse(raw, secretKey)) {
                    if (pack.uuid().equalsIgnoreCase(uuid)) {
                        byUuid.put(uuid, pack);
                        packs.add(pack);
                        if (cb != null) cb.printLog("  [cached] " + pack.title());
                        break;
                    }
                }
                // Wipe the raw encrypted bytes after decryption
                java.util.Arrays.fill(raw, (byte) 0);
            } catch (Exception ignored) {
                // corrupt or unreadable cache entry — skip
            }
        }
        return packs;
    }

    /** Remove caches for packs that no longer exist on the website (and legacy files). */
    private static void pruneCaches(List<GeopakClient.RemotePack> all) {
        try {
            Set<String> live = new java.util.HashSet<>();
            for (GeopakClient.RemotePack p : all) live.add(p.id.toLowerCase(Locale.ROOT));
            try (Stream<Path> stream = Files.list(cacheDir())) {
                for (Path file : stream.filter(p -> p.getFileName().toString().endsWith(".gpkc")).toList()) {
                    String stem = file.getFileName().toString();
                    stem = stem.substring(0, stem.length() - ".gpkc".length());
                    // Legacy combined caches were named after a 64-char hex signature.
                    if (stem.length() != 36 || !live.contains(stem)) {
                        Files.deleteIfExists(file);
                    }
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static void checkAborted(ProgressReceiver cb) throws SyncCancelledException {
        if (cb != null && cb.isAborted()) throw new SyncCancelledException();
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        String msg = c.getMessage();
        return msg == null || msg.isEmpty() ? c.getClass().getSimpleName() : msg;
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1048576) return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MiB", bytes / 1048576.0);
    }
}
