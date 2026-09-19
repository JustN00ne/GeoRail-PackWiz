package eu.justnoone.geopackwiz;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Local (non-secret) settings persisted to config/geopackwiz.json.
 *
 * Security-relevant values — the pack website URL, the API key, the decryption
 * key and the server the pack is meant for — are baked into the jar
 * ( {@link ServerConfig} ) and are deliberately NOT part of this file, so
 * players cannot point the mod at another server or swap the keys.
 */
public class Config {

    // UUIDs of the packs to sync. Empty = sync every pack the server makes visible.
    public final ConfigItem<List<String>> websitePacks = new ConfigItem<>(
        "websitePacks",
        (json) -> {
            List<String> list = new ArrayList<>();
            for (JsonElement source : json.getAsJsonArray()) {
                list.add(source.getAsString());
            }
            return list;
        },
        (value) -> {
            JsonArray array = new JsonArray();
            for (String packId : value) {
                array.add(packId);
            }
            return array;
        },
        new ArrayList<>()
    );
    // Epoch millis of the last successful sync (used by the config screen).
    public final ConfigItem<Long> lastSyncTime = new ConfigItem<>(
        "lastSyncTime", JsonElement::getAsLong, JsonPrimitive::new, 0L);

    // Port for the local HTTP server (enables website-to-mod real-time communication).
    // Set to 0 or negative to disable the local server.
    public final ConfigItem<Integer> localServerPort = new ConfigItem<>(
        "localServerPort", JsonElement::getAsInt, JsonPrimitive::new, 25585);

    // ---- Server-side (legacy lock / version enforcement, configured on the server) ----
    public final ConfigItem<String> serverLockKey = new ConfigItem<>(
        "serverLockKey", JsonElement::getAsString, JsonPrimitive::new, "");
    public final ConfigItem<Boolean> clientEnforceInstall = new ConfigItem<>(
        "clientEnforceInstall", JsonElement::getAsBoolean, JsonPrimitive::new, false);
    public final ConfigItem<String> clientEnforceVersion = new ConfigItem<>(
        "clientEnforceVersion", JsonElement::getAsString, JsonPrimitive::new, "");

    public List<ConfigItem<?>> configItems = List.of(
        websitePacks, lastSyncTime, localServerPort, serverLockKey, clientEnforceInstall, clientEnforceVersion
    );

    public void load() throws IOException {
        Path configFilePath = getConfigFilePath();
        if (!Files.isRegularFile(configFilePath)) {
            save();
            return;
        }

        JsonObject localConfig = (JsonObject) GeoPackWiz.JSON_PARSER.parse(Files.readString(configFilePath));
        for (ConfigItem<?> item : configItems) {
            item.load(localConfig, new JsonObject());
        }
    }

    public void save() throws IOException {
        JsonObject obj = new JsonObject();
        obj.addProperty("version", 3);

        for (ConfigItem<?> item : configItems) {
            item.save(obj);
        }
        Files.createDirectories(getConfigFilePath().getParent());
        Files.writeString(getConfigFilePath(), new GsonBuilder().setPrettyPrinting().create().toJson(obj));
    }

    public Path getConfigFilePath() {
        return FabricLoader.getInstance().getConfigDir().resolve(GeoPackWiz.MOD_ID + ".json");
    }

    public static class ConfigItem<T> {

        public T value;
        public boolean isFromLocal;

        private final String key;
        private final Function<JsonElement, T> fromCodec;
        private final Function<T, JsonElement> toCodec;
        private final Supplier<T> defaultSupplier;

        public ConfigItem(String key, Function<JsonElement, T> fromCodec, Function<T, JsonElement> toCodec, T defaultValue) {
            this.key = key;
            this.fromCodec = fromCodec;
            this.toCodec = toCodec;
            this.defaultSupplier = () -> defaultValue;
        }

        public void load(JsonObject localObject, JsonObject remoteObject) {
            if (localObject.has(key)) {
                value = fromCodec.apply(localObject.get(key));
                isFromLocal = true;
            } else {
                value = defaultSupplier.get();
                isFromLocal = false;
            }
        }

        public void save(JsonObject jsonObject) {
            if (isFromLocal && value != null) {
                jsonObject.add(key, toCodec.apply(value));
            }
        }

        /** Persist a value that was assigned in code (e.g. lastSyncTime). */
        public void persist() {
            isFromLocal = true;
        }
    }
}
