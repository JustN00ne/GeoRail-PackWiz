package eu.justnoone.geopackwiz.gui.gl;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceMetadata;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Set;

public class PreloadTextureResource extends Resource {

    private final ResourceLocation resourceLocation;

    public PreloadTextureResource(ResourceLocation resourceLocation) {
        super(new PreloadPackResources(resourceLocation), () -> {
            InputStream stream = PreloadTextureResource.class.getResourceAsStream(getAssetPath(resourceLocation));
            return stream != null ? stream : InputStream.nullInputStream();
        }, ResourceMetadata.EMPTY_SUPPLIER);
        this.resourceLocation = resourceLocation;
    }

    private static InputStream openClasspath(ResourceLocation resourceLocation) throws Exception {
        InputStream stream = PreloadTextureResource.class.getResourceAsStream(getAssetPath(resourceLocation));
        if (stream == null) {
            throw new FileNotFoundException("Missing preload texture: " + resourceLocation);
        }
        return stream;
    }

    private static String getAssetPath(ResourceLocation resourceLocation) {
        return "/assets/" + resourceLocation.getNamespace() + "/" + resourceLocation.getPath();
    }

    private static final class PreloadPackResources implements PackResources {

        private final String id;
        private final String namespace;

        private PreloadPackResources(ResourceLocation resourceLocation) {
            this.id = "preload/" + resourceLocation.toDebugFileName();
            this.namespace = resourceLocation.getNamespace();
        }

        @Override
        public IoSupplier<InputStream> getRootResource(String... strings) {
            return null;
        }

        @Override
        public IoSupplier<InputStream> getResource(PackType packType, ResourceLocation resourceLocation) {
            return null;
        }

        @Override
        public void listResources(PackType packType, String namespace, String path, ResourceOutput resourceOutput) {
        }

        @Override
        public Set<String> getNamespaces(PackType packType) {
            return Collections.singleton(namespace);
        }

        @Override
        public <T> T getMetadataSection(MetadataSectionSerializer<T> metadataSectionSerializer) {
            return null;
        }

        @Override
        public String packId() {
            return id;
        }

        @Override
        public void close() {
        }
    }

}
