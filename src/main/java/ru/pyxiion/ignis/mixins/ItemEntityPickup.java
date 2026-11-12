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

    // player_pickup_item. Inventory.add is reached only after the pickup delay and the owner checks of
    // playerTouch passed, so the event fires once per actual pickup attempt, not on every tick of the delay.
    @Inject(method = "playerTouch", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Inventory;add(Lnet/minecraft/world/item/ItemStack;)Z"), cancellable = true)
    private void pxrp$onPlayerCollision(Player player, CallbackInfo ci) {

        var rt = PxIgnis.getRuntimeOrNull();
        if (rt == null) return;

        if (rt.getEventManager().fireCancellable("player_pickup_item", () -> new LuaValue[]{
            EntityFactory.INSTANCE.wrap(player),
            ItemStackWrap.INSTANCE.wrap(this.getItem()),
            LuaValue.valueOf(this.getItem().getCount())
        })) {
            ci.cancel();
        }
    }
}
