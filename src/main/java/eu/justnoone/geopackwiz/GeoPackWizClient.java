package eu.justnoone.geopackwiz;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.packs.repository.PackRepository;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import net.fabricmc.loader.api.FabricLoader;

import eu.justnoone.geopackwiz.drm.ServerLockRegistry;
import eu.justnoone.geopackwiz.gui.GlProgressScreen;
import eu.justnoone.geopackwiz.gui.SyncProgressScreen;
import eu.justnoone.geopackwiz.gui.gl.GlHelper;
import eu.justnoone.geopackwiz.localserver.LocalServer;
import eu.justnoone.geopackwiz.network.ClientVersionC2SPacket;
import eu.justnoone.geopackwiz.network.ServerLockS2CPacket;
import eu.justnoone.geopackwiz.ram.RamPack;
import eu.justnoone.geopackwiz.sync.SyncEngine;

public class GeoPackWizClient implements ClientModInitializer {

    /**
     * Guard flag: set to true while modifyPackList is executing a state
     * change + reload, cleared after. Prevents the reload mixin from
     * re-entering modifyPackList and creating an infinite loop.
     */
    private static volatile boolean reloadInProgress;

    public static final HttpClient HTTP_CLIENT;

    /** Opens the GeoRail pack config screen (default key: K). Registered in onInitializeClient. */
    public static KeyMapping CONFIG_KEY;

    /** The one sync that may run while in-game ("Sync now" from the config screen). */
    private static volatile SyncEngine sessionSync;

    /**
     * Whether the RAM pack should be enabled in the current context. Set at each
     * transition (boot success, connect, disconnect) and read back on every
     * resource reload, so a reload can never re-enable the pack while a non-GeoRail
     * server (or singleplayer) is being joined — the pack works ONLY on GeoRail
     * hosts (any *.georail* domain or the baked-in direct IP).
     */
    private static volatile boolean desiredEnabled;

    static {
        ExecutorService HTTP_CLIENT_EXECUTOR = Executors.newFixedThreadPool(4);
        HTTP_CLIENT = HttpClient.newBuilder()
                // The API key travels in a custom header, which would be forwarded
                // to whatever host a redirect points at. Never follow redirects so
                // the key can only ever reach the configured server, and use the
                // default (verified) TLS trust store — no self-signed bypass.
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .executor(HTTP_CLIENT_EXECUTOR)
                .build();
    }

    @Override
    public void onInitializeClient() {
        // NOTE: this class is force-loaded from server-side code too (Config), so
        // everything client-only must stay inside this method — not in static
        // initializers. The static block above is plain JDK code.
        CONFIG_KEY = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.geopackwiz.config",
                InputConstants.Type.KEYSYM,
                InputConstants.KEY_K,
                "category.geopackwiz"
        ));

        // Start the local server for website-to-mod real-time communication
        try {
            LocalServer.start();
            // Register shutdown hook to stop the local server
            Runtime.getRuntime().addShutdownHook(new Thread(LocalServer::stop, "GeoPak-LocalServer-Shutdown"));
        } catch (Exception e) {
            GeoPackWiz.LOGGER.warn("GeoPackWiz: could not start local server: {}", e.getMessage());
        }

        ClientLoginConnectionEvents.INIT.register((handler, client) -> {
            // NOTE: the pack is NOT toggled here. Minecraft.getCurrentServer() is
            // derived from the PLAY-phase listener and is always null during the
            // login handshake, so any check here would misread every server as
            // "not GeoRail" and disable the pack on every join. The decision is
            // made once the world has entered the play phase (see JOIN below).
            ServerLockRegistry.onLoginInitiated();
        });
        ClientPlayNetworking.registerGlobalReceiver(ServerLockS2CPacket.TYPE,
                (client, handler, buf, responseSender) -> {
                    String serverLockKey = buf.readUtf();
                    client.execute(() -> {
                        ServerLockRegistry.onSetServerLock(serverLockKey);

                        FriendlyByteBuf responseBuf = PacketByteBufs.create();
                        responseBuf.writeUtf(GeoPackWiz.MOD_VERSION);
                        ClientPlayNetworking.send(ClientVersionC2SPacket.TYPE, responseBuf);
                    });
                });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            // Play phase: getCurrentServer() is now valid. Decide the pack's
            // state here — enabled on GeoRail hosts, off everywhere else. On the
            // GeoRail server this is idempotent (the pack is already enabled
            // from boot) so joining stays instant with zero join-time work;
            // elsewhere one deferred reload removes it before the world shows
            // any pack content. Runs on the render thread (JOIN fires there),
            // deferred via execute() for safety like the other handlers.
            client.execute(() -> {
                onJoinedServer(handler);
                // Only when the boot sync never loaded anything (offline launch,
                // user skipped, server was down): retry in the background. When
                // the pack is already loaded this does nothing.
                if (!RamPack.isLoaded()) {
                    startBackgroundSync();
                }
            });
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            // Back in the menu: re-arm the pack (menu resources reload with it),
            // so the next join of play.georail.eu is instant and fully loaded.
            client.execute(GeoPackWizClient::onDisconnected);
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Watch the in-game/background sync: when it finishes, apply it.
            SyncEngine engine = sessionSync;
            if (engine != null && engine.state().isFinished()) {
                onSessionSyncFinished(engine);
            }
            while (CONFIG_KEY.consumeClick()) {
                client.setScreen(new eu.justnoone.geopackwiz.gui.ConfigScreen());
            }
        });
    }

    /**
     * Pre-game-load sync: shows the macOS-style boot screen on the render
     * thread while a background engine downloads / decrypts the packs. The
     * render thread only draws and handles input, so the game never freezes —
     * with no internet the failure fades in red and offers "Play without the
     * pack" / "Report error" instead of blocking forever.
     */
    public static void runBootSync() {
        SyncEngine engine = new SyncEngine();
        engine.start();
        GlHelper.initGlStates();
        try {
            new GlProgressScreen(engine).runBoot();
        } finally {
            GlHelper.resetGlStates();
            engine.cancel();
        }
    }

    /** Returns the pack ID for the in-memory RAM pack. */
    private static String getDiskPackId() {
        return RamPack.PACK_ID;
    }

    /**
     * Called from the {@code Minecraft.<init>} hook right before the initial
     * resource load starts. Adds the merged pack to the enabled list so
     * {@code openAllSelected()} includes it in the very first resource load.
     */
    public static void activateForBoot() {
        if (!RamPack.isLoaded()) return;
        desiredEnabled = true;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        Options options = mc.options;

        String id = getDiskPackId();
        if (id.isEmpty()) return;

        boolean present = options.resourcePacks.contains(id)
                || options.incompatibleResourcePacks.contains(id);
        if (present) return;

        if (!options.resourcePacks.contains("vanilla")) {
            options.resourcePacks.add("vanilla");
        }
        if (!options.resourcePacks.contains("fabric")) {
            options.resourcePacks.add("fabric");
        }
        options.resourcePacks.add(id);

        PackRepository repository = mc.getResourcePackRepository();
        repository.reload();
        options.loadSelectedResourcePacks(repository);
        options.save();
        GeoPackWiz.LOGGER.info("GeoPackWiz: RAM pack armed for the initial game load ({}).", id);
    }

    /**
     * Entered the play phase of a connection (any server, or singleplayer). The
     * pack is fully loaded and enabled from game start; this decides whether it
     * stays enabled: only while the target is a GeoRail host (any *.georail*
     * domain or the baked-in direct IP). For any other server (or singleplayer)
     * it is switched off before the world displays any pack content. Must run
     * on the render thread, and only after the play-phase listener exists
     * (mc.getCurrentServer() is null during the login handshake).
     */
    public static void onJoinedServer(ClientPacketListener handler) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        boolean target = isTargetServer(handler);
        desiredEnabled = target && RamPack.isLoaded();
        modifyPackList(desiredEnabled);
    }

    /**
     * True when the current connection is a GeoRail server. Primary source:
     * the ServerData the player clicked in the server list (hostname, survives
     * DNS changes). Fallback: the actual socket address, so direct-connecting
     * by raw IP works too.
     */
    private static boolean isTargetServer(ClientPacketListener handler) {
        Minecraft mc = Minecraft.getInstance();
        ServerData server = mc.getCurrentServer();
        if (server != null && isGeorailServer(server.ip)) return true;
        if (handler != null && handler.getConnection() != null
                && handler.getConnection().getRemoteAddress() instanceof InetSocketAddress isa) {
            return isGeorailServer(isa.getHostString());
        }
        return false;
    }

    /** Left the server / world — back in the menu, arm the pack again. */
    public static void onDisconnected() {
        desiredEnabled = RamPack.isLoaded();
        modifyPackList(desiredEnabled);
    }

    /**
     * Runs a sync in the background while in-game and shows the in-game
     * progress screen. Used by the config screen's "Sync now" button. Never
     * blocks the game.
     */
    public static void startSessionSync() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        SyncEngine running = sessionSync;
        if (running != null && !running.state().isFinished()) return;
        SyncEngine engine = new SyncEngine();
        sessionSync = engine;
        engine.start();
        mc.setScreen(new SyncProgressScreen(engine));
    }

    /**
     * Background sync without any screen (used when joining the GeoRail server
     * after an offline/skipped boot). The end-of-tick watcher applies the result
     * when it finishes.
     */
    public static void startBackgroundSync() {
        SyncEngine running = sessionSync;
        if (running != null && !running.state().isFinished()) return;
        SyncEngine engine = new SyncEngine();
        sessionSync = engine;
        engine.start();
        GeoPackWiz.LOGGER.info("GeoPackWiz: boot sync never loaded the packs — retrying in the background.");
    }

    /**
     * Called by the in-game progress screen (render thread) once a manual sync
     * has finished successfully or was cancelled. Applies the freshly loaded
     * packs: close the screen, re-evaluate the enabled state for the current
     * context, and reload so the new in-memory snapshot takes effect.
     */
    public static void onSessionSyncFinished(SyncEngine engine) {
        // Guard against double handling (the progress screen's tick and the
        // global tick watcher may both observe the finished state).
        if (sessionSync != engine) return;
        sessionSync = null;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;

        if (mc.screen instanceof SyncProgressScreen) {
            mc.setScreen(null);
        }

        if (!engine.state().succeeded() || !RamPack.isLoaded()) return;

        // A sync that finished on the GeoRail server (or in the menu) arms the
        // pack; on any other server it stays disabled.
        ServerData server = mc.getCurrentServer();
        if (mc.getConnection() == null
                || (server != null && isGeorailServer(server.ip))) {
            desiredEnabled = true;
        }
        boolean enabled = desiredEnabled && RamPack.isLoaded();
        if (!modifyPackList(enabled) && enabled) {
            // Already enabled — reload anyway so the game opens the new snapshot.
            mc.reloadResourcePacks();
        }
    }

    /**
     * Re-asserts the pack's enabled state for the current context (cheap, no
     * network). Called on every resource reload and from the vanilla pack
     * screen. Returns true when the enabled pack list actually changed.
     *
     * IMPORTANT: This must only DISABLE the pack, never enable it.
     * Enabling is only done by activateForBoot, onJoinedServer,
     * onDisconnected, or onSessionSyncFinished. If we enable here during
     * a reload triggered by Minecraft removing an invalid pack, it creates
     * an infinite reload loop.
     */
    public static boolean updatePackActivation() {
        if (reloadInProgress) return false;
        if (!desiredEnabled || !RamPack.isLoaded()) {
            return modifyPackList(false);
        }
        return false;
    }

    /**
     * True when the joined address belongs to a GeoRail server: any host
     * containing "georail" (play.georail.eu, georail.eu, any subdomain) or one
     * of the extra hosts/IPs baked into ServerConfig (e.g. 159.195.108.223).
     */
    public static boolean isGeorailServer(String serverAddress) {
        String[] parts = parseHostPort(serverAddress);
        String host = parts[0];
        if (host.isEmpty()) return false;
        // parseHostPort lower-cases the host, so the marker match is case-insensitive.
        if (host.contains(ServerConfig.ENABLED_HOST_MARKER)) return true;
        for (String extra : ServerConfig.ENABLED_EXTRA_HOSTS) {
            if (host.equals(extra.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private static String[] parseHostPort(String address) {
        if (address == null) return new String[] { "", "" };
        String trimmed = address.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty()) return new String[] { "", "" };
        // IPv6 literals are wrapped in brackets: [::1]:25565
        int bracketEnd = trimmed.startsWith("[") ? trimmed.indexOf(']') : -1;
        if (bracketEnd != -1) {
            String host = trimmed.substring(1, bracketEnd);
            String port = trimmed.length() > bracketEnd + 1 && trimmed.charAt(bracketEnd + 1) == ':'
                    ? trimmed.substring(bracketEnd + 2) : "";
            return new String[] { host, port };
        }
        int colon = trimmed.lastIndexOf(':');
        if (colon == -1) return new String[] { trimmed, "" };
        return new String[] { trimmed.substring(0, colon), trimmed.substring(colon + 1) };
    }

    /**
     * Add (enable=true) or remove (enable=false) the RAM pack from the enabled
     * resource-pack list and re-apply the selection. Idempotent: when the pack is
     * already in the requested state this does nothing, so reloads triggered by
     * activation changes cannot chain. A resource reload is scheduled (next tick)
     * only when the selection actually changed.
     *
     * @return true when the selection changed and a reload was scheduled
     */
    public static boolean modifyPackList(boolean enable) {
        if (reloadInProgress) return false;
        desiredEnabled = enable;
        Minecraft mc = Minecraft.getInstance();
        Options options = mc.options;

        String id = getDiskPackId();
        if (id.isEmpty()) return false;

        boolean present = options.resourcePacks.contains(id)
                || options.incompatibleResourcePacks.contains(id);
        if (enable == present) return false;

        options.resourcePacks.remove(id);
        options.incompatibleResourcePacks.remove(id);
        if (enable) {
            if (!options.resourcePacks.contains("vanilla")) {
                options.resourcePacks.add("vanilla");
            }
            if (!options.resourcePacks.contains("fabric")) {
                options.resourcePacks.add("fabric");
            }
            options.resourcePacks.add(id);
        }

        PackRepository repository = mc.getResourcePackRepository();
        repository.reload();
        options.loadSelectedResourcePacks(repository);
        options.save();

        reloadInProgress = true;
        mc.execute(() -> {
            reloadInProgress = false;
            mc.reloadResourcePacks();
        });
        GeoPackWiz.LOGGER.info("GeoPackWiz: merged pack {} on the next reload.", enable ? "enabled" : "disabled");
        return true;
    }

}