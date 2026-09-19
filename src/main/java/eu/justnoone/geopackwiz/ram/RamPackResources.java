package eu.justnoone.geopackwiz.ram;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.resources.IoSupplier;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * A {@link PackResources} whose files live in RAM (populated from the encrypted
 * download). All asset bytes are stored obfuscated via {@link SecureByteArray}
 * and deobfuscated on-the-fly when Minecraft reads them. Nothing behind this is
 * ever written to disk, so players cannot pull the GeoRail assets out of their
 * game folder.
 */
public class RamPackResources implements PackResources {

    /** Snapshot taken when this pack is opened; survives later pack updates. */
    private final String id;
    private final RamPack.Data data;
    /** Lazily deobfuscated assets for the lifetime of this PackResources instance. */
    private final Map<String, Map<String, byte[]>> assets;

    public RamPackResources(String id) {
        this.id = id;
        this.data = RamPack.data();
        this.assets = data != null ? data.getAssets() : Map.of();
    }

    @Override
    public IoSupplier<InputStream> getRootResource(String... elements) {
        if (data == null || elements.length != 1) return null;
        String name = elements[0];
        if (name.equals("pack.mcmeta")) {
            byte[] mcmeta = data.getPackMcmeta();
            if (mcmeta != null) return () -> new ByteArrayInputStream(mcmeta);
        }
        if (name.equals("pack.png")) {
            byte[] png = data.getPackPng();
            if (png != null) return () -> new ByteArrayInputStream(png);
        }
        return null;
    }

    @Override
    public IoSupplier<InputStream> getResource(PackType packType, ResourceLocation location) {
        if (data == null || packType != PackType.CLIENT_RESOURCES) return null;
        Map<String, byte[]> namespace = assets.get(location.getNamespace());
        if (namespace == null) return null;
        byte[] content = namespace.get(location.getPath());
        if (content == null) return null;
        return () -> new ByteArrayInputStream(content);
    }

    @Override
    public void listResources(PackType packType, String namespace, String path, ResourceOutput output) {
        if (data == null || packType != PackType.CLIENT_RESOURCES) return;
        Map<String, byte[]> files = assets.get(namespace);
        if (files == null) return;
        String prefix = path.isEmpty() ? "" : path + "/";
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            String key = entry.getKey();
            if (!prefix.isEmpty() && !key.startsWith(prefix)) continue;
            byte[] content = entry.getValue();
            ResourceLocation location = ResourceLocation.tryParse(namespace + ":" + key);
            if (location == null) continue;
            output.accept(location, () -> new ByteArrayInputStream(content));
        }
    }

    @Override
    public Set<String> getNamespaces(PackType packType) {
        if (data == null || packType != PackType.CLIENT_RESOURCES) return Set.of();
        return assets.keySet();
    }

    @Override
    public <T> T getMetadataSection(MetadataSectionSerializer<T> serializer) throws IOException {
        if (data == null) return null;
        byte[] mcmeta = data.getPackMcmeta();
        if (mcmeta == null) return null;
        JsonObject root = JsonParser.parseString(new String(mcmeta, StandardCharsets.UTF_8)).getAsJsonObject();
        if (!root.has(serializer.getMetadataSectionName())) return null;
        return serializer.fromJson(root.getAsJsonObject(serializer.getMetadataSectionName()));
    }

    @Override
    public String packId() {
        return id;
    }

    @Override
    public void close() {
        // Deobfuscated snapshot is released; the underlying SecureByteArrays
        // in RamPack.Data remain obfuscated until the next pack generation.
    }
}
