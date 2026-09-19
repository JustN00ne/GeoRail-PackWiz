package eu.justnoone.geopackwiz;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.packs.repository.PackRepository;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonParser;
import eu.justnoone.geopackwiz.drm.ServerLockRegistry;
import eu.justnoone.geopackwiz.gui.GlProgressScreen;
import eu.justnoone.geopackwiz.gui.gl.GlHelper;
import eu.justnoone.geopackwiz.io.Dispatcher;
import eu.justnoone.geopackwiz.network.ClientVersionC2SPacket;
import eu.justnoone.geopackwiz.network.ServerLockS2CPacket;
import eu.justnoone.geopackwiz.util.MismatchingVersionException;
import eu.justnoone.geopackwiz.util.MtrVersion;

public class GeoPackWiz implements ModInitializer {
    public static final String MOD_ID = "geopackwiz";
    public static final Logger LOGGER = LoggerFactory.getLogger("GeoPackWiz");
    public static String MOD_VERSION = "";

    public static final Config CONFIG = new Config();
    public static final JsonParser JSON_PARSER = new JsonParser();

    @Override
    public void onInitialize() {
        MOD_VERSION = FabricLoader.getInstance().getModContainer(MOD_ID).get()
                .getMetadata().getVersion().getFriendlyString();
        try {
            CONFIG.load();
        } catch (IOException e) {
            LOGGER.error("Failed to load config", e);
        }

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (ServerPlayNetworking.canSend(handler.getPlayer(), ServerLockS2CPacket.TYPE)) {
                ServerPlayNetworking.send(handler.getPlayer(), new ServerLockS2CPacket(CONFIG.serverLockKey.value));
            } else {
                if (GeoPackWiz.CONFIG.clientEnforceInstall.value) {
                    handler.disconnect(Component
                            .literal(new MismatchingVersionException(GeoPackWiz.MOD_VERSION, "NOT INSTALLED")
                                    .getMessage().trim()));
                }
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(ClientVersionC2SPacket.TYPE, (MinecraftServer server, ServerPlayer player, ServerGamePacketListenerImpl handler, FriendlyByteBuf buf, PacketSender responseSender) -> {
            String clientVersion = buf.toString();
            server.execute(() -> {
                if (!GeoPackWiz.CONFIG.clientEnforceVersion.value.isEmpty()) {

                    String versionCriteria = GeoPackWiz.CONFIG.clientEnforceVersion.value.replace("current", GeoPackWiz.MOD_VERSION);

                    if (!MtrVersion.parse(clientVersion).matches(versionCriteria)) {
                        handler.disconnect(Component.literal(new MismatchingVersionException(GeoPackWiz.MOD_VERSION, clientVersion).getMessage().trim()));
                    }
                }
            });
        });
    }
}