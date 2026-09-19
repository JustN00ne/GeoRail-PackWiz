package eu.justnoone.geopackwiz.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.google.common.base.Optional;
import eu.justnoone.geopackwiz.GeoPackWiz;
import eu.justnoone.geopackwiz.gui.gl.PreloadTextureResource;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.Resource;

@Mixin(MultiPackResourceManager.class)
public class MultiPackResourceManagerMixin {
    public MultiPackResourceManagerMixin() {
   }

   @Inject(
      at = {@At("HEAD")},
      method = {"getResource"},
      cancellable = true
   )
   void getResource(ResourceLocation resourceLocation, CallbackInfoReturnable<Optional<Resource>> cir) {
      if (resourceLocation.getNamespace().equals(GeoPackWiz.MOD_ID)) {
         cir.setReturnValue(Optional.of(new PreloadTextureResource(resourceLocation)));
         cir.cancel();
      }

   }
}
