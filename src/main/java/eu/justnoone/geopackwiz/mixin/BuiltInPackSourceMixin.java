package eu.justnoone.geopackwiz.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.BuiltInPackSource;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;

import eu.justnoone.geopackwiz.ram.RamPack;
import eu.justnoone.geopackwiz.ram.RamPackResources;
import eu.justnoone.geopackwiz.ram.RamPackSource;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

/**
 * Injects the in-memory GeoRail pack into Minecraft's built-in pack
 * repository so it is discoverable alongside vanilla and Fabric packs.
 * The pack only appears once {@link RamPack#isLoaded()} returns true
 * (i.e. after a successful sync has loaded packs into RAM).
 */
@Mixin(BuiltInPackSource.class)
public abstract class BuiltInPackSourceMixin {

    @Inject(method = "loadPacks", at = @At("RETURN"))
    private void geopackwiz$addRamPack(Consumer<Pack> consumer, CallbackInfo ci) {
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
