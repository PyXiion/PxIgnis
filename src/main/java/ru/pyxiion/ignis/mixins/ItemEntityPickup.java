package ru.pyxiion.ignis.mixins;

import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
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
    public abstract ItemStack getItem();

    // player_pickup_item — intercept item pickup
    @Inject(method = "playerTouch", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/item/ItemEntity;getItem()Lnet/minecraft/world/item/ItemStack;"), cancellable = true)
    private void pxrp$onPlayerCollision(Player player, CallbackInfo ci) {

        var results = PxIgnis.instance.runtime.getEventManager()
            .fireWithResults("player_pickup_item",
                EntityFactory.INSTANCE.wrap(player),
                ItemStackWrap.INSTANCE.wrap(this.getItem()),
                LuaValue.valueOf(this.getItem().getCount()));

        if (results.stream().anyMatch(r -> r.isboolean() && !r.toboolean())) {
            ci.cancel();
        }
    }
}
