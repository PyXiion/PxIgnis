package ru.pyxiion.ignis.mixins;

import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import org.luaj.vm2.LuaValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.pyxiion.ignis.PxIgnis;
import ru.pyxiion.ignis.api.wrapper.EntityFactory;
import ru.pyxiion.ignis.api.wrapper.ItemStackWrap;

@Mixin(ItemEntity.class)
public abstract class ItemEntityPickup {

    @Shadow
    public abstract ItemStack getStack();

    // player_pickup_item — intercept item pickup
    @Inject(method = "onPlayerCollision", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/ItemEntity;getStack()Lnet/minecraft/item/ItemStack;"), cancellable = true)
    private void pxrp$onPlayerCollision(PlayerEntity player, CallbackInfo ci) {

        var results = PxIgnis.instance.runtime.getEventManager()
            .fireWithResults("player_pickup_item",
                EntityFactory.INSTANCE.wrap(player),
                ItemStackWrap.INSTANCE.wrap(this.getStack()),
                LuaValue.valueOf(this.getStack().getCount()));

        if (results.stream().anyMatch(r -> r.isboolean() && !r.toboolean())) {
            ci.cancel();
        }
    }
}
