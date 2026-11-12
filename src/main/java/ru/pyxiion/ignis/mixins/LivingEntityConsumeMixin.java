package ru.pyxiion.ignis.mixins;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.pyxiion.ignis.events.GameEvents;

@Mixin(LivingEntity.class)
public abstract class LivingEntityConsumeMixin {

    // player.consume — fires when entity finishes using a consumable item
    @Inject(method = "completeUsingItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getUsedItemHand()Lnet/minecraft/world/InteractionHand;"), cancellable = true)
    private void pxrp$onConsumeItem(CallbackInfo ci) {
        if (!((Object) this instanceof ServerPlayer self)) return;

        ItemStack stack = self.getUseItem();
        if (stack.isEmpty()) return;

        if (!GameEvents.consume(self, stack)) {
            ci.cancel();
            // Without this the use timer stays at zero and the event fires again every tick; the client has
            // already consumed the item locally, so its inventory is resent.
            self.stopUsingItem();
            self.inventoryMenu.sendAllDataToRemote();
            self.containerMenu.sendAllDataToRemote();
        }
    }
}
