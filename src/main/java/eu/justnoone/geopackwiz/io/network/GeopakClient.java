package eu.justnoone.geopackwiz.io.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import eu.justnoone.geopackwiz.GeoPackWiz;
import eu.justnoone.geopackwiz.GeoPackWizClient;
import eu.justnoone.geopackwiz.gui.gl.GlHelper;
import eu.justnoone.geopackwiz.io.ProgressReceiver;

import org.apache.commons.codec.binary.Hex;
import org.apache.commons.io.IOUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * Client for the GeoRail PackWiz website — the exact contract documented in
 * the website's MOD_INTEGRATION.md.
 *
 *  - fetchPackList  GET {baseUrl}/api/packs          (X-API-Key)
 *  - requestGeopak  GET {baseUrl}/request-pack?packs=uuid1,uuid2 (X-API-Key)
 *  - decryptAndParse  AES-256-GCM -> length-prefixed segments -> zips
 *
 * The API key and decryption secret are NEVER logged and never included in
 * exception messages. All requests go over the shared HTTP_CLIENT, which uses
 * the default (verified) TLS trust store and never follows redirects — so the
 * API key header can never be forwarded to a different host.
 */
public class GeopakClient {

    /** One pack as listed by GET /api/packs. */
    public static final class RemotePack {
        public final String id;
        public final String title;
        public final String description;
        public final String createdAt;
        public final String uploadedBy;
        public final String zipFilename;
        public final long zipSize;
        public final String zipSha256;

        private RemotePack(JsonObject obj) {
            id = obj.get("id").getAsString();
            title = obj.has("title") ? obj.get("title").getAsString() : id;
            description = obj.has("description") ? obj.get("description").getAsString() : "";
            createdAt = obj.has("createdAt") ? obj.get("createdAt").getAsString() : "";
            // The website enriches responses with a flat `uploadedBy` + `zip`
            // (MOD_INTEGRATION.md §2.4); fall back to owner/versions if absent.
            if (obj.has("uploadedBy") && obj.get("uploadedBy").isJsonObject()) {
                uploadedBy = obj.getAsJsonObject("uploadedBy").get("username").getAsString();
            } else if (obj.has("owner") && obj.get("owner").isJsonObject()) {
                uploadedBy = obj.getAsJsonObject("owner").get("username").getAsString();
            } else {
                uploadedBy = "";
            }
            JsonObject zip = null;
            if (obj.has("zip") && obj.get("zip").isJsonObject()) {
                zip = obj.getAsJsonObject("zip");
            } else if (obj.has("versions") && obj.get("versions").isJsonArray()) {
                JsonArray versions = obj.getAsJsonArray("versions");
                if (versions.size() > 0) zip = versions.get(versions.size() - 1).getAsJsonObject();
            }
            if (zip != null) {
                zipFilename = zip.has("filename") ? zip.get("filename").getAsString() : "";
                zipSize = zip.has("size") ? zip.get("size").getAsLong() : 0;
                zipSha256 = zip.has("sha256") ? zip.get("sha256").getAsString() : "";
            } else {
                zipFilename = "";
                zipSize = 0;
                zipSha256 = "";
            }
        }
    }

    /** One decrypted, verified pack segment from a .geopak file. */
    public static final class GeopakPack {
        public final JsonObject meta;
        public final byte[] thumbnail; // JPEG 650×300
        public final byte[] zip; // complete original .zip, ready to extract

        private GeopakPack(JsonObject meta, byte[] thumbnail, byte[] zip) {
            this.meta = meta;
            this.thumbnail = thumbnail;
            this.zip = zip;
        }

        public String uuid() {
            return meta.has("uuid") ? meta.get("uuid").getAsString() : "";
        }

        public String title() {
            return meta.has("title") ? meta.get("title").getAsString() : uuid();
        }
    }

    /** HTTP 404: some requested packs no longer exist. */
    public static final class GeopakMissingException extends IOException {
        public final Set<String> missingIds;

        public GeopakMissingException(String body) {
            super("Some packs are no longer available on the server (HTTP 404).");
            missingIds = parseMissingIds(body);
        }

        static Set<String> parseMissingIds(String body) {
            Set<String> ids = new HashSet<>();
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("Pack\\(s\\) not found:\\s*([0-9a-fA-F,\\-]+)")
                    .matcher(body);
            if (m.find()) {
                for (String id : m.group(1).split(",")) {
                    String t = id.trim().toLowerCase(Locale.ROOT);
                    if (!t.isEmpty()) ids.add(t);
                }
            }
            return ids;
        }
    }

    /** HTTP 413: the combined pack data exceeds the server's size limit. */
    public static final class GeopakTooLargeException extends IOException {
        public GeopakTooLargeException(String body) {
            super("Pack data is too large for one request (HTTP 413). Splitting into smaller batches.");
        }
    }

    private static final int GEOPAK_MIN_SIZE = 35; // smallest valid file: header + tag (v2) or legacy v1 header
    private static final int GEOPAK_V1_HEADER_SIZE = 35; // v1: magic 6 + version 1 + IV 12 + tag 16
    private static final int GEOPAK_V2_HEADER_SIZE = 19; // v2: magic 6 + version 1 + IV 12 (tag at end of file)
    private static final int GCM_TAG_BITS = 128;

    /** Receives incremental byte counts while one .geopak body is downloaded. */
    public interface ByteProgress {
        /** @param receivedBytes bytes of this body received so far
         *  @param totalBytes   expected total (Content-Length), or 0 when unknown */
        void onProgress(long receivedBytes, long totalBytes);
    }

    /** Trim a trailing slash so baseUrl + path concatenation is predictable. */
    public static String stripTrailingSlash(String baseUrl) {
        while (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        return baseUrl;
    }

    // ---- Discovery ----------------------------------------------------------

    /** GET {baseUrl}/api/packs — newest first, already filtered to what the key may see. */
    public static List<RemotePack> fetchPackList(String baseUrl, String apiKey) throws Exception {
        URI uri = URI.create(stripTrailingSlash(baseUrl) + "/api/packs");
        HttpResponse<String> response = send(uri, apiKey, HttpResponse.BodyHandlers.ofString(), Duration.ofSeconds(30));

        int status = response.statusCode();
        if (status == 401 || status == 403) {
            throw new IOException("Authentication with the pack website failed (HTTP " + status + "). "
                    + "Check websiteApiKey in config/geopackwiz.json.");
        }
        if (status != 200) {
            throw new IOException("Pack list request failed (HTTP " + status + "): " + snippet(response.body()));
        }

        JsonObject obj = GeoPackWiz.JSON_PARSER.parse(response.body()).getAsJsonObject();
        if (!obj.has("packs") || !obj.get("packs").isJsonArray()) {
            throw new IOException("Unexpected pack list response from the server.");
        }
        List<RemotePack> packs = new ArrayList<>();
        for (JsonElement el : obj.getAsJsonArray("packs")) {
            packs.add(new RemotePack(el.getAsJsonObject()));
        }
        return packs;
    }

    // ---- Download -----------------------------------------------------------

    /**
     * GET {baseUrl}<path>?packs=... and return the raw .geopak bytes.
     *
     * The endpoint is served at both /request-pack (contract) and
     * /api/request-pack (alias for proxies that only forward /api/*). The
     * configured path is tried first; if it answers with a web page instead of
     * pack data (SPA fallback, missing route, proxy misconfiguration), the
     * other path is tried once before giving up. 429/5xx are retried with
     * backoff; 404-with-JSON and 413 are surfaced to the caller (Dispatcher).
     */
    public static byte[] requestGeopak(String baseUrl, String apiKey, List<String> uuids,
                                       ProgressReceiver cb, ByteProgress onProgress) throws Exception {
        String path = eu.justnoone.geopackwiz.ServerConfig.REQUEST_PATH;
        if (path == null || path.trim().isEmpty()) path = "/api/request-pack";
        if (!path.startsWith("/")) path = "/" + path;
        String alternate = path.equals("/api/request-pack") ? "/request-pack" : "/api/request-pack";

        byte[] data = requestGeopakAtPath(baseUrl, apiKey, uuids, path, cb, onProgress);
        if (data == null) {
            cb.printLog("The pack endpoint returned a web page instead of data; trying the alternate path ...");
            data = requestGeopakAtPath(baseUrl, apiKey, uuids, alternate, cb, onProgress);
        }
        if (data == null) {
            throw new IOException("The pack website answered with a web page instead of pack data (" + path
                    + "). Check the server setup — the request-pack endpoint must be reachable (see MOD_INTEGRATION.md).");
        }
        return data;
    }

    private static byte[] requestGeopakAtPath(String baseUrl, String apiKey, List<String> uuids,
                                              String path, ProgressReceiver cb, ByteProgress onProgress) throws Exception {
        URI uri = URI.create(stripTrailingSlash(baseUrl) + path + "?packs=" + String.join(",", uuids));

        int attempt = 0;
        while (true) {
            attempt++;
            HttpResponse<InputStream> response = send(uri, apiKey, HttpResponse.BodyHandlers.ofInputStream(),
                    Duration.ofMinutes(10));
            int status = response.statusCode();
            if (status == 200) {
                String contentType = response.headers().firstValue("content-type").orElse("");
                if (contentType.toLowerCase(Locale.ROOT).contains("text/html")) {
                    response.body().close(); // SPA fallback page, not pack data
                    return null;
                }
                String geopakVersion = response.headers().firstValue("x-geopak-version").orElse("");
                if (!geopakVersion.isEmpty() && !geopakVersion.equals("1") && !geopakVersion.equals("2")) {
                    throw new IOException("Unsupported .geopak version " + geopakVersion + " — update the mod.");
                }
                return readBodyWithProgress(response, onProgress);
            }

            String body = readBody(response);
            switch (status) {
                case 401, 403 -> throw new IOException("Authentication with the pack website failed (HTTP " + status
                        + "). Check websiteApiKey in config/geopackwiz.json.");
                case 404 -> {
                    if (body.contains("Pack(s) not found")) throw new GeopakMissingException(body);
                    response.body().close(); // route missing (old backend / proxy) — try the alternate path
                    return null;
                }
                case 413 -> throw new GeopakTooLargeException(body);
                case 429 -> {
                    if (attempt >= 6) throw new IOException("Rate limited by the pack server too many times (HTTP 429).");
                    cb.printLog("Rate limited by the pack server (429). Waiting 60 s before retrying ...");
                    Thread.sleep(60_000);
                }
                default -> {
                    if (status >= 500 && attempt < 5) {
                        cb.printLog("Pack server error (HTTP " + status + "). Retrying in " + (attempt * 5) + " s ...");
                        Thread.sleep(5000L * attempt);
                    } else {
                        throw new IOException("Pack download failed (HTTP " + status + "): " + snippet(body));
                    }
                }
            }
        }
    }

    private static byte[] readBodyWithProgress(HttpResponse<InputStream> response, ByteProgress onProgress)
            throws IOException, GlHelper.MinecraftStoppingException {
        long total = response.headers().firstValueAsLong("Content-Length").orElse(0L);
        ByteArrayOutputStream bos = new ByteArrayOutputStream(total > 0 ? (int) Math.min(total, Integer.MAX_VALUE) : 64 * 1024);
        long[] lastAmount = {0};

        try (InputStream input = unwrapContentEncoding(response);
             ProgressOutputStream pos = new ProgressOutputStream(bos, amount -> {
                 if (amount - lastAmount[0] >= 64 * 1024 || amount == total) {
                     lastAmount[0] = amount;
                     if (onProgress != null) onProgress.onProgress(amount, total);
                 }
             })) {
            IOUtils.copy(new BufferedInputStream(input), pos);
        }
        return bos.toByteArray();
    }

    // ---- Decryption & parsing ------------------------------------------------

    /**
     * Validate magic/version, decrypt with AES-256-GCM and parse the
     * length-prefixed segments. Every zip's sha256 is verified against its
     * metadata, so a pack can never be silently swapped or tampered with.
     */
    public static List<GeopakPack> decryptAndParse(byte[] geopak, byte[] key) throws IOException {
        if (geopak.length < GEOPAK_MIN_SIZE) throw new IOException("Downloaded pack file is too short.");
        if (geopak[0] != 'G' || geopak[1] != 'E' || geopak[2] != 'O' || geopak[3] != 'P' || geopak[4] != 'A' || geopak[5] != 'K') {
            throw new IOException("Downloaded pack file has a bad header (not a .geopak).");
        }
        int version = geopak[6];
        if (version != 1 && version != 2) throw new IOException("Unsupported .geopak version " + version + " — update the mod.");

        // v1 files carry the auth tag at offset 19 and ciphertext after; v2
        // (current) puts the tag at the END so the server can stream the
        // response without buffering the whole payload in RAM.
        byte[] iv = Arrays.copyOfRange(geopak, 7, 19);
        byte[] tag;
        byte[] ciphertext;
        if (version == 1) {
            tag = Arrays.copyOfRange(geopak, 19, 35);
            ciphertext = Arrays.copyOfRange(geopak, GEOPAK_V1_HEADER_SIZE, geopak.length);
        } else {
            tag = Arrays.copyOfRange(geopak, geopak.length - 16, geopak.length);
            ciphertext = Arrays.copyOfRange(geopak, GEOPAK_V2_HEADER_SIZE, geopak.length - 16);
        }

        byte[] plaintext;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            // Java's GCM expects the auth tag appended to the ciphertext.
            plaintext = cipher.doFinal(concat(ciphertext, tag));
        } catch (Exception ex) {
            // Bad key, corruption or tampering — never parse garbage.
            throw new IOException("Pack decryption failed. Check websiteSecretKey in config/geopackwiz.json "
                    + "or ask an admin to re-issue the pack.", ex);
        }

        List<GeopakPack> packs = new ArrayList<>();
        ByteBuffer buf = ByteBuffer.wrap(plaintext).order(ByteOrder.BIG_ENDIAN);
        while (buf.hasRemaining()) {
            int metaLen = readInt(buf, "segment metadata");
            byte[] metaBytes = readBytes(buf, metaLen, "metadata");
            int thumbLen = readInt(buf, "segment thumbnail");
            byte[] thumbnail = readBytes(buf, thumbLen, "thumbnail");
            int zipLen = readInt(buf, "segment zip");
            byte[] zip = readBytes(buf, zipLen, "zip");

            JsonObject meta = GeoPackWiz.JSON_PARSER.parse(new String(metaBytes, StandardCharsets.UTF_8)).getAsJsonObject();
            String expectedSha = meta.has("zipSha256") ? meta.get("zipSha256").getAsString() : "";
            String actualSha = sha256Hex(zip);
            if (!expectedSha.isEmpty() && !expectedSha.equalsIgnoreCase(actualSha)) {
                throw new IOException("Integrity check failed for pack \"" + meta.get("title").getAsString()
                        + "\": sha256 mismatch. The file may be corrupted or tampered with.");
            }
            packs.add(new GeopakPack(meta, thumbnail, zip));
        }
        return packs;
    }

    // ---- Helpers -------------------------------------------------------------

    private static int readInt(ByteBuffer buf, String what) throws IOException {
        if (buf.remaining() < 4) throw new IOException("Truncated .geopak: missing " + what + " length.");
        return buf.getInt();
    }

    private static byte[] readBytes(ByteBuffer buf, int len, String what) throws IOException {
        if (len < 0 || buf.remaining() < len) throw new IOException("Truncated .geopak: " + what + " is cut off.");
        byte[] out = new byte[len];
        buf.get(out);
        return out;
    }

    public static byte[] keyFromHex(String hex) throws IOException {
        try {
            byte[] key = Hex.decodeHex(hex);
            if (key.length != 32) throw new IOException("websiteSecretKey must be 64 hex chars (32 bytes), got " + key.length + " bytes.");
            return key;
        } catch (Exception ex) {
            throw new IOException("websiteSecretKey in config/geopackwiz.json is not valid hex (expected 64 hex chars).", ex);
        }
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Hex.encodeHexString(digest.digest(data));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static <T> HttpResponse<T> send(URI uri, String apiKey,
                                            HttpResponse.BodyHandler<T> handler, Duration timeout) throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .setHeader("User-Agent", "GeoRailPackWiz/" + GeoPackWiz.MOD_VERSION + " (GeoPak mod client)")
                .setHeader("Accept-Encoding", "gzip")
                .GET();
        if (apiKey != null && !apiKey.isEmpty()) {
            builder.setHeader("X-API-Key", apiKey);
        }
        try {
            return GeoPackWizClient.HTTP_CLIENT.send(builder.build(), handler);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while talking to the pack website.", ex);
        }
    }

    /** Unwrap gzip unless the server chose to send identity. */
    static InputStream unwrapContentEncoding(HttpResponse<InputStream> response) throws IOException {
        String encoding = response.headers().firstValue("Content-Encoding").orElse("").toLowerCase(Locale.ROOT);
        return switch (encoding) {
            case "", "identity" -> response.body();
            case "gzip" -> new GZIPInputStream(response.body());
            default -> throw new IOException("Unsupported Content-Encoding: " + encoding);
        };
    }

    private static String readBody(HttpResponse<InputStream> response) throws IOException {
        try (InputStream in = unwrapContentEncoding(response)) {
            return new String(IOUtils.toByteArray(in), StandardCharsets.UTF_8);
        }
    }

    private static String snippet(String body) {
        String flat = body.replace('\n', ' ').trim();
        return flat.length() > 200 ? flat.substring(0, 200) + "…" : flat;
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1048576) return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MiB", bytes / 1048576.0);
    }
}