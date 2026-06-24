package ru.pyxiion.ignis.mixins;

import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import org.luaj.vm2.LuaValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.pyxiion.ignis.PxIgnis;
import ru.pyxiion.ignis.api.wrapper.EntityFactory;
import ru.pyxiion.ignis.api.wrapper.ItemStackWrap;

@Mixin(PlayerEntity.class)
public abstract class PlayerEntityPickupMixin {

    // player_pickup_item — intercept item pickup
    @Inject(method = "accept", at = @At("HEAD"), cancellable = true)
    private void pxrp$onPickupItem(ItemEntity itemEntity, CallbackInfo ci) {
        if (itemEntity == null) return;
        if (itemEntity.getEntityWorld().isClient()) return;

        var results = PxIgnis.instance.runtime.getEventManager()
            .fireWithResults("player_pickup_item",
                EntityFactory.INSTANCE.wrap((PlayerEntity) (Object) this),
                ItemStackWrap.INSTANCE.wrap(itemEntity.getStack()),
                LuaValue.valueOf(itemEntity.getStack().getCount()));

        if (results.stream().anyMatch(r -> r.isboolean() && !r.toboolean())) {
            ci.cancel();
        }
    }
}
