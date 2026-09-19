package eu.justnoone.geopackwiz.localserver;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import eu.justnoone.geopackwiz.GeoPackWiz;
import eu.justnoone.geopackwiz.ServerConfig;
import eu.justnoone.geopackwiz.geopak.GeopakFileManager;
import eu.justnoone.geopackwiz.io.network.GeopakClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Local HTTP server that enables real-time website-to-mod communication.
 *
 * The server runs on localhost only (not accessible from the network) and
 * provides endpoints for:
 *   - Installing packs from the website in real-time
 *   - Listing installed .geopak files
 *   - Checking if the mod is running
 *   - Serving the installation web page
 *
 * Cross-platform: uses com.sun.net.httpserver which is available in all
 * mainstream JDK distributions (Oracle, OpenJDK, Adoptium, etc.).
 *
 * Security: The server only listens on 127.0.0.1 (loopback), so it's
 * only accessible from the same machine. No authentication is needed
 * since physical access to the machine is already required.
 */
public class LocalServer {

    /** Default port for the local server. */
    public static final int DEFAULT_PORT = 25585;

    /** The running server instance, or null if not started. */
    private static volatile HttpServer server;

    /** The port the server is actually listening on. */
    private static volatile int actualPort;

    /** Installation state for tracking progress. */
    private static final AtomicReference<InstallState> installState = new AtomicReference<>(InstallState.idle());

    private LocalServer() {}

    // ---- Lifecycle ------------------------------------------------------------

    /**
     * Start the local server on the configured port (or default).
     * If the port is already in use, tries the next 10 ports.
     *
     * @return true if the server started successfully
     */
    public static boolean start() {
        if (server != null) return true;

        int port = GeoPackWiz.CONFIG.localServerPort.value;
        if (port <= 0) port = DEFAULT_PORT;

        for (int attempt = 0; attempt < 10; attempt++) {
            try {
                int tryPort = port + attempt;
                HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", tryPort), 0);
                httpServer.setExecutor(Executors.newFixedThreadPool(4, r -> {
                    Thread t = new Thread(r, "GeoPak-LocalServer");
                    t.setDaemon(true);
                    return t;
                }));

                // Register routes
                httpServer.createContext("/", LocalServer::handleRequest);
                httpServer.start();

                server = httpServer;
                actualPort = tryPort;
                GeoPackWiz.LOGGER.info("GeoPackWiz: local server started on http://127.0.0.1:{}", actualPort);
                return true;
            } catch (IOException e) {
                if (attempt < 9) {
                    GeoPackWiz.LOGGER.debug("GeoPackWiz: port {} in use, trying {}", port + attempt, port + attempt + 1);
                } else {
                    GeoPackWiz.LOGGER.warn("GeoPackWiz: could not start local server after 10 attempts: {}", e.getMessage());
                }
            }
        }
        return false;
    }

    /**
     * Stop the local server.
     */
    public static void stop() {
        HttpServer s = server;
        if (s != null) {
            s.stop(0);
            server = null;
            actualPort = 0;
            GeoPackWiz.LOGGER.info("GeoPackWiz: local server stopped");
        }
    }

    /**
     * Check if the local server is running.
     */
    public static boolean isRunning() {
        return server != null;
    }

    /**
     * Get the port the server is listening on.
     */
    public static int getPort() {
        return actualPort;
    }

    /**
     * Get the base URL for the local server.
     */
    public static String getBaseUrl() {
        return "http://127.0.0.1:" + actualPort;
    }

    // ---- Request handling -----------------------------------------------------

    private static void handleRequest(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        Map<String, String> query = parseQuery(exchange.getRequestURI().getQuery());

        try {
            // CORS headers for local server
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");

            if ("OPTIONS".equals(method)) {
                sendJson(exchange, 204, "");
                return;
            }

            switch (path) {
                case "/" -> serveInstallPage(exchange);
                case "/api/health" -> handleHealth(exchange);
                case "/api/packs" -> handleListPacks(exchange);
                case "/api/install" -> handleInstall(exchange, query);
                case "/api/status" -> handleStatus(exchange);
                default -> sendJson(exchange, 404, "{\"error\":\"Not found\"}");
            }
        } catch (Exception e) {
            GeoPackWiz.LOGGER.error("GeoPackWiz: local server error: {}", e.getMessage(), e);
            try {
                sendJson(exchange, 500, "{\"error\":\"Internal server error\"}");
            } catch (Exception ignored) {}
        } finally {
            exchange.close();
        }
    }

    // ---- Endpoints ------------------------------------------------------------

    /**
     * GET /api/health - Health check endpoint.
     * Returns mod version and port info.
     */
    private static void handleHealth(HttpExchange exchange) throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("status", "ok");
        json.addProperty("modVersion", GeoPackWiz.MOD_VERSION);
        json.addProperty("port", actualPort);
        json.addProperty("geopakFolder", GeopakFileManager.getGeopakFolder().toString());
        sendJson(exchange, 200, json.toString());
    }

    /**
     * GET /api/packs - List all .geopak files on disk.
     */
    private static void handleListPacks(HttpExchange exchange) throws IOException {
        JsonObject json = new JsonObject();
        JsonArray packs = new JsonArray();

        for (GeopakFileManager.GeopakFileInfo info : GeopakFileManager.listGeopakFiles()) {
            JsonObject pack = new JsonObject();
            pack.addProperty("uuid", info.packUuid());
            pack.addProperty("fileSize", info.fileSize());
            pack.addProperty("lastModified", info.lastModified());
            if (info.metadata() != null) {
                pack.add("metadata", info.metadata());
            }
            packs.add(pack);
        }

        json.add("packs", packs);
        json.addProperty("total", packs.size());
        sendJson(exchange, 200, json.toString());
    }

    /**
     * GET /api/status - Current installation status.
     */
    private static void handleStatus(HttpExchange exchange) throws IOException {
        InstallState state = installState.get();
        sendJson(exchange, 200, state.toJson().toString());
    }

    /**
     * POST /api/install?pack=<uuid> - Install a pack from the website.
     *
     * This is the core endpoint that enables real-time installation.
     * The website opens this URL, and the mod downloads the pack from
     * the main server and installs it.
     *
     * Query parameters:
     *   pack - UUID of the pack to install
     *
     * The response is streamed as the installation progresses.
     */
    private static void handleInstall(HttpExchange exchange, Map<String, String> query) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }

        String packIdRaw = query.get("pack");
        if (packIdRaw == null || packIdRaw.trim().isEmpty()) {
            sendJson(exchange, 400, "{\"error\":\"Missing 'pack' query parameter\"}");
            return;
        }
        final String packId = packIdRaw.trim().toLowerCase();

        // Check if already installing
        InstallState current = installState.get();
        if (current.phase() == InstallPhase.DOWNLOADING || current.phase() == InstallPhase.DECRYPTING) {
            sendJson(exchange, 409, "{\"error\":\"Another installation is in progress\"}");
            return;
        }

        // Start installation in background
        installState.set(InstallState.downloading(packId));
        CompletableFuture.runAsync(() -> performInstall(packId));

        // Return immediately with accepted status
        JsonObject resp = new JsonObject();
        resp.addProperty("status", "accepted");
        resp.addProperty("packId", packId);
        resp.addProperty("message", "Installation started");
        sendJson(exchange, 202, resp.toString());
    }

    // ---- Installation logic ---------------------------------------------------

    private static void performInstall(String packId) {
        try {
            GeoPackWiz.LOGGER.info("GeoPackWiz: local server installing pack {} from website", packId);

            // Step 1: Download from the main server
            installState.set(InstallState.downloading(packId));
            byte[] geopakBytes = GeopakClient.requestGeopak(
                ServerConfig.WEBSITE_BASE_URL,
                ServerConfig.API_KEY,
                List.of(packId),
                new eu.justnoone.geopackwiz.io.ProgressReceiver() {
                    @Override public void setProgress(float primary, float secondary) {}
                    @Override public void setInfo(String title, String info) {}
                    @Override public void setPackStatus(List<String> lines) {}
                    @Override public void printLog(String line) {
                        GeoPackWiz.LOGGER.info("GeoPackWiz: {}", line);
                    }
                    @Override public void printLogOutsidePolling(String line) {
                        GeoPackWiz.LOGGER.info("GeoPackWiz: {}", line);
                    }
                    @Override public void amendLastLog(String line) {
                        GeoPackWiz.LOGGER.info("GeoPackWiz: {}", line);
                    }
                    @Override public void setException(Exception exception) {
                        GeoPackWiz.LOGGER.error("GeoPackWiz: {}", exception.getMessage(), exception);
                    }
                    @Override public boolean isAborted() { return false; }
                },
                null
            );

            // Step 2: Save to disk as .geopak
            installState.set(InstallState.saving(packId));
            GeopakFileManager.saveGeopak(packId, geopakBytes);

            // Step 3: Decrypt and merge into RAM
            installState.set(InstallState.decrypting(packId));
            byte[] secretKey = GeopakClient.keyFromHex(ServerConfig.SECRET_KEY);
            List<GeopakClient.GeopakPack> packs = GeopakClient.decryptAndParse(geopakBytes, secretKey);
            java.util.Arrays.fill(geopakBytes, (byte) 0); // wipe

            // Merge into RAM pack
            eu.justnoone.geopackwiz.ram.RamPack.mergePacks(packs);

            // Wipe decrypted data
            for (GeopakClient.GeopakPack pack : packs) {
                if (pack.zip != null) java.util.Arrays.fill(pack.zip, (byte) 0);
            }

            installState.set(InstallState.done(packId));
            GeoPackWiz.LOGGER.info("GeoPackWiz: pack {} installed successfully from website", packId);

            // Trigger a resource pack reload so the newly loaded RAM pack takes effect
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc != null) {
                mc.execute(() -> mc.reloadResourcePacks());
            }

        } catch (Exception e) {
            GeoPackWiz.LOGGER.error("GeoPackWiz: failed to install pack {} from website: {}", packId, e.getMessage(), e);
            installState.set(InstallState.error(packId, e.getMessage()));
        }
    }

    // ---- Install page (served at /) -------------------------------------------

    private static void serveInstallPage(HttpExchange exchange) throws IOException {
        String html = getInstallPageHtml();
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String getInstallPageHtml() {
        return """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>GeoPak Installer</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            background: #0a0a1a;
            color: #e0e0e0;
            min-height: 100vh;
            display: flex;
            align-items: center;
            justify-content: center;
        }
        .container {
            max-width: 480px;
            width: 100%;
            padding: 2rem;
        }
        .card {
            background: #1a1a2e;
            border-radius: 12px;
            padding: 2rem;
            box-shadow: 0 4px 24px rgba(0,0,0,0.4);
            text-align: center;
        }
        .logo {
            font-size: 1.5rem;
            font-weight: 700;
            color: #4fc3f7;
            margin-bottom: 0.5rem;
        }
        .subtitle {
            color: #888;
            font-size: 0.875rem;
            margin-bottom: 1.5rem;
        }
        .pack-name {
            font-size: 1.25rem;
            font-weight: 600;
            margin-bottom: 1rem;
        }
        .status {
            padding: 1rem;
            border-radius: 8px;
            margin: 1rem 0;
            font-size: 0.9rem;
        }
        .status.idle { background: #1e3a5f; color: #64b5f6; }
        .status.downloading { background: #1e3a5f; color: #64b5f6; }
        .status.saving { background: #1e4a3a; color: #81c784; }
        .status.decrypting { background: #3a3a1e; color: #fff176; }
        .status.done { background: #1e4a3a; color: #81c784; }
        .status.error { background: #4a1e1e; color: #ef9a9a; }
        .progress-bar {
            width: 100%;
            height: 6px;
            background: #2a2a4a;
            border-radius: 3px;
            overflow: hidden;
            margin: 1rem 0;
        }
        .progress-fill {
            height: 100%;
            background: linear-gradient(90deg, #4fc3f7, #29b6f6);
            border-radius: 3px;
            transition: width 0.3s ease;
        }
        .spinner {
            display: inline-block;
            width: 20px;
            height: 20px;
            border: 2px solid #4fc3f7;
            border-top-color: transparent;
            border-radius: 50%;
            animation: spin 0.8s linear infinite;
            margin-right: 0.5rem;
            vertical-align: middle;
        }
        @keyframes spin { to { transform: rotate(360deg); } }
        .btn {
            display: inline-block;
            padding: 0.75rem 1.5rem;
            border-radius: 8px;
            font-size: 0.9rem;
            font-weight: 600;
            cursor: pointer;
            border: none;
            margin: 0.5rem;
            transition: all 0.2s;
        }
        .btn-primary {
            background: #4fc3f7;
            color: #0a0a1a;
        }
        .btn-primary:hover { background: #29b6f6; }
        .btn-secondary {
            background: #2a2a4a;
            color: #e0e0e0;
        }
        .btn-secondary:hover { background: #3a3a5a; }
        .btn:disabled { opacity: 0.5; cursor: not-allowed; }
        .error-msg {
            color: #ef9a9a;
            font-size: 0.85rem;
            margin-top: 0.5rem;
        }
        .checkmark {
            font-size: 3rem;
            margin-bottom: 1rem;
        }
    </style>
</head>
<body>
    <div class="container">
        <div class="card">
            <div class="logo">GeoPak</div>
            <div class="subtitle">Resource Pack Installer</div>
            <div id="pack-name" class="pack-name">Loading...</div>
            <div id="status" class="status idle">
                <span class="spinner"></span> Connecting to mod...
            </div>
            <div class="progress-bar">
                <div id="progress" class="progress-fill" style="width: 0%"></div>
            </div>
            <div id="actions"></div>
            <div id="error" class="error-msg"></div>
        </div>
    </div>
    <script>
        const params = new URLSearchParams(window.location.search);
        const packId = params.get('pack');
        const packName = document.getElementById('pack-name');
        const statusEl = document.getElementById('status');
        const progressEl = document.getElementById('progress');
        const actionsEl = document.getElementById('actions');
        const errorEl = document.getElementById('error');

        if (!packId) {
            statusEl.className = 'status error';
            statusEl.innerHTML = 'No pack specified';
            packName.textContent = 'Error';
            return;
        }

        packName.textContent = 'Pack: ' + packId.substring(0, 8) + '...';

        // Poll status
        let pollInterval;
        async function pollStatus() {
            try {
                const res = await fetch('/api/status');
                const state = await res.json();
                updateUI(state);

                if (state.phase === 'done' || state.phase === 'error') {
                    clearInterval(pollInterval);
                }
            } catch (e) {
                // Server might have restarted
            }
        }

        function updateUI(state) {
            switch (state.phase) {
                case 'idle':
                    statusEl.className = 'status idle';
                    statusEl.innerHTML = '<span class="spinner"></span> Starting installation...';
                    progressEl.style.width = '10%';
                    break;
                case 'downloading':
                    statusEl.className = 'status downloading';
                    statusEl.innerHTML = '<span class="spinner"></span> Downloading pack from server...';
                    progressEl.style.width = '40%';
                    break;
                case 'saving':
                    statusEl.className = 'status saving';
                    statusEl.innerHTML = '<span class="spinner"></span> Saving .geopak file to disk...';
                    progressEl.style.width = '70%';
                    break;
                case 'decrypting':
                    statusEl.className = 'status decrypting';
                    statusEl.innerHTML = '<span class="spinner"></span> Decrypting and installing pack...';
                    progressEl.style.width = '90%';
                    break;
                case 'done':
                    statusEl.className = 'status done';
                    statusEl.innerHTML = '<div class="checkmark">&#10003;</div> Pack installed successfully!';
                    progressEl.style.width = '100%';
                    actionsEl.innerHTML = '<button class="btn btn-secondary" onclick="window.close()">Close</button>';
                    break;
                case 'error':
                    statusEl.className = 'status error';
                    statusEl.innerHTML = '&#10007; Installation failed';
                    errorEl.textContent = state.error || 'Unknown error';
                    progressEl.style.width = '0%';
                    actionsEl.innerHTML = '<button class="btn btn-secondary" onclick="retry()">Retry</button>';
                    break;
            }
        }

        async function startInstall() {
            try {
                statusEl.className = 'status idle';
                statusEl.innerHTML = '<span class="spinner"></span> Requesting installation...';
                progressEl.style.width = '5%';

                const res = await fetch('/api/install?pack=' + encodeURIComponent(packId), { method: 'POST' });
                const data = await res.json();

                if (res.status === 202) {
                    pollInterval = setInterval(pollStatus, 1000);
                } else {
                    statusEl.className = 'status error';
                    statusEl.innerHTML = '&#10007; ' + (data.error || 'Failed to start installation');
                }
            } catch (e) {
                statusEl.className = 'status error';
                statusEl.innerHTML = '&#10007; Could not connect to mod';
                errorEl.textContent = 'Make sure Minecraft is running with GeoPackWiz installed.';
            }
        }

        function retry() {
            errorEl.textContent = '';
            actionsEl.innerHTML = '';
            startInstall();
        }

        // Auto-start installation
        startInstall();
    </script>
</body>
</html>
""";
    }

    // ---- Helpers ---------------------------------------------------------------

    private static void sendJson(HttpExchange exchange, int statusCode, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> result = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) return result;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                result.put(key, value);
            }
        }
        return result;
    }

    // ---- Install state --------------------------------------------------------

    public enum InstallPhase {
        IDLE, DOWNLOADING, SAVING, DECRYPTING, DONE, ERROR
    }

    public record InstallState(
        InstallPhase phase,
        String packId,
        String error,
        long timestamp
    ) {
        static InstallState idle() {
            return new InstallState(InstallPhase.IDLE, null, null, System.currentTimeMillis());
        }
        static InstallState downloading(String packId) {
            return new InstallState(InstallPhase.DOWNLOADING, packId, null, System.currentTimeMillis());
        }
        static InstallState saving(String packId) {
            return new InstallState(InstallPhase.SAVING, packId, null, System.currentTimeMillis());
        }
        static InstallState decrypting(String packId) {
            return new InstallState(InstallPhase.DECRYPTING, packId, null, System.currentTimeMillis());
        }
        static InstallState done(String packId) {
            return new InstallState(InstallPhase.DONE, packId, null, System.currentTimeMillis());
        }
        static InstallState error(String packId, String error) {
            return new InstallState(InstallPhase.ERROR, packId, error, System.currentTimeMillis());
        }

        JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("phase", phase.name().toLowerCase());
            if (packId != null) json.addProperty("packId", packId);
            if (error != null) json.addProperty("error", error);
            json.addProperty("timestamp", timestamp);
            return json;
        }
    }
}
