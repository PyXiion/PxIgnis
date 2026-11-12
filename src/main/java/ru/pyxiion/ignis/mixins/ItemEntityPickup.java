package ru.pyxiion.ignis.mixins;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.pyxiion.ignis.events.GameEvents;

@Mixin(ItemEntity.class)
public abstract class ItemEntityPickup {

    @Shadow
    public abstract ItemStack getItem();

    // player.pickup. Inventory.add is reached only after the pickup delay and the owner checks of
    // playerTouch passed, so the event fires once per actual pickup attempt, not on every tick of the delay.
    @Inject(method = "playerTouch", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Inventory;add(Lnet/minecraft/world/item/ItemStack;)Z"), cancellable = true)
    private void pxrp$onPlayerCollision(Player player, CallbackInfo ci) {
        if (player instanceof ServerPlayer serverPlayer && !GameEvents.pickup(serverPlayer, this.getItem())) {
            ci.cancel();
        }
    }
}
