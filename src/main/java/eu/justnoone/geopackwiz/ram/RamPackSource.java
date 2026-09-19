package eu.justnoone.geopackwiz.ram;

import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.RepositorySource;

import java.util.function.Consumer;

/**
 * Makes the in-memory GeoRail pack available to Minecraft's pack repository
 * (the same mechanism Fabric uses for its "Fabric Mods" pack). The pack only
 * appears once its data has been downloaded and decrypted into RAM, so the game
 * can never select an empty or half-loaded pack.
 */
public class RamPackSource implements RepositorySource {

    public static final RamPackSource INSTANCE = new RamPackSource();

    @Override
    public void loadPacks(Consumer<Pack> consumer) {
        if (!RamPack.isLoaded()) return;
        Pack pack = Pack.readMetaAndCreate(
                RamPack.PACK_ID,
                Component.literal("GeoRail Pack"),
                false,
                RamPackResources::new,
                PackType.CLIENT_RESOURCES,
                Pack.Position.TOP,
                PackSource.BUILT_IN);
        if (pack != null) {
            consumer.accept(pack);
        }
    }
}
