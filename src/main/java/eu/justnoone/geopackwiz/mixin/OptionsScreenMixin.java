package eu.justnoone.geopackwiz.mixin;

import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.OptionsScreen;
import net.minecraft.server.packs.repository.PackRepository;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import eu.justnoone.geopackwiz.GeoPackWiz;
import eu.justnoone.geopackwiz.GeoPackWizClient;

@Mixin(Options.class)
public class OptionsScreenMixin {

	@Inject(at = @At(
   value = "INVOKE",
   target = "Lnet/minecraft/client/Options;save()V"
), method = {"updateResourcePacks"})
	void updateResourcePacks(PackRepository packRepository, CallbackInfo info) {
		// Re-assert activation state (on the right server -> keep enabled, otherwise off).
		GeoPackWizClient.updatePackActivation();
	}
}