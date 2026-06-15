package ru.pyxiion.ignis.mixins;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.pyxiion.ignis.api.ContainerManager;

@Mixin(ScreenHandler.class)
public abstract class ScreenHandlerMixin {
    @Inject(method = "onSlotClick", at = @At("HEAD"), cancellable = true)
    private void pxrp$onSlotClick(int slot, int button, SlotActionType action, PlayerEntity player, CallbackInfo ci) {
        if (player instanceof ServerPlayerEntity sp) {
            if (!ContainerManager.INSTANCE.shouldAllowClick((ScreenHandler) (Object) this, slot, button, action, sp)) {
                ci.cancel();
            }
        }
    }

    @Inject(method = "onClosed", at = @At("HEAD"))
    private void pxrp$onClosed(PlayerEntity player, CallbackInfo ci) {
        if (player instanceof ServerPlayerEntity) {
            ContainerManager.INSTANCE.onScreenClosed((ScreenHandler) (Object) this);
        }
    }
}
