package eu.justnoone.geopackwiz.mixin;

import java.util.concurrent.CompletableFuture;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import eu.justnoone.geopackwiz.GeoPackWizClient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.main.GameConfig;

@Mixin({ Minecraft.class })
public class MinecraftMixin {
    @Inject(at = { @At("HEAD") }, method = { "reloadResourcePacks()Ljava/util/concurrent/CompletableFuture;" })
    void reloadResourcePacks(CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        // Packs are synced once (at startup) and kept in RAM, already enabled.
        // A plain resource reload (F3+T, pack screen changes, ...) must never
        // block on the network — only the activation state is re-asserted here.
        // modifyPackList is idempotent, so this is cheap and cannot chain reloads.
        GeoPackWizClient.updatePackActivation();
    }

    @Inject(at = { @At(value = "INVOKE", target = "Lnet/minecraft/server/packs/repository/PackRepository;openAllSelected()Ljava/util/List;") }, method = { "<init>" })
    void ctor(GameConfig gameConfig, CallbackInfo ci) {
        // Sync BEFORE the initial game load so the pack is ready when the first
        // resource reload runs. The sync runs on a background thread while the
        // macOS-style boot screen is shown, so a slow or missing internet
        // connection can never freeze the game. The pack is then selected into
        // the enabled list, so it is part of the very first load — active from
        // the moment the game loads, on any server, without a second download.
        GeoPackWizClient.runBootSync();
        GeoPackWizClient.activateForBoot();
    }
}