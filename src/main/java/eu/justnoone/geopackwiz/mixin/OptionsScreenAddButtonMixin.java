package eu.justnoone.geopackwiz.mixin;

import eu.justnoone.geopackwiz.gui.ConfigScreen;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.OptionsScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(OptionsScreen.class)
public abstract class OptionsScreenAddButtonMixin extends Screen {

    protected OptionsScreenAddButtonMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void geopackwiz$addConfigButton(CallbackInfo ci) {
        addRenderableWidget(Button.builder(Component.literal("GeoRail Packs..."), (btn) -> {
            assert minecraft != null;
            minecraft.setScreen(new ConfigScreen());
        }).bounds(8, this.height - 28, 130, 20).build());
    }
}