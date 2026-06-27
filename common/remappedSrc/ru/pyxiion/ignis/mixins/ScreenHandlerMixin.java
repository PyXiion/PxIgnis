package ru.pyxiion.ignis.mixins;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.pyxiion.ignis.api.manager.ContainerManager;

@Mixin(AbstractContainerMenu.class)
public abstract class ScreenHandlerMixin {
    @Inject(method = "clicked", at = @At("HEAD"), cancellable = true)
    private void pxrp$onSlotClick(int slot, int button, ClickType action, Player player, CallbackInfo ci) {
        if (player instanceof ServerPlayer sp) {
            if (!ContainerManager.INSTANCE.shouldAllowClick((AbstractContainerMenu) (Object) this, slot, button, action, sp)) {
                ci.cancel();
            }
        }
    }

    @Inject(method = "removed", at = @At("HEAD"))
    private void pxrp$onClosed(Player player, CallbackInfo ci) {
        if (player instanceof ServerPlayer) {
            ContainerManager.INSTANCE.onScreenClosed((AbstractContainerMenu) (Object) this);
        }
    }
}
