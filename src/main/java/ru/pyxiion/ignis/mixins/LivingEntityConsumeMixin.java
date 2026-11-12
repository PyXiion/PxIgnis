package ru.pyxiion.ignis.mixins;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.luaj.vm2.LuaValue;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.pyxiion.ignis.PxIgnis;
import ru.pyxiion.ignis.api.wrapper.EntityFactory;
import ru.pyxiion.ignis.api.wrapper.ItemStackWrap;

@Mixin(LivingEntity.class)
public abstract class LivingEntityConsumeMixin {

    // player_consume_item — fires when entity finishes using a consumable item
    @Inject(method = "completeUsingItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getUsedItemHand()Lnet/minecraft/world/InteractionHand;"), cancellable = true)
    private void pxrp$onConsumeItem(CallbackInfo ci) {
        if (!((Object) this instanceof ServerPlayer self)) return;

        var rt = PxIgnis.getRuntimeOrNull();
        if (rt == null) return;

        ItemStack stack = self.getUseItem();
        if (stack.isEmpty()) return;

        if (rt.getEventManager().fireCancellable("player_consume_item", () -> new LuaValue[]{
            EntityFactory.INSTANCE.wrap(self),
            ItemStackWrap.INSTANCE.wrap(stack)
        })) {
            ci.cancel();
            // Without this the use timer stays at zero and the event fires again every tick; the client has
            // already consumed the item locally, so its inventory is resent.
            self.stopUsingItem();
            self.inventoryMenu.sendAllDataToRemote();
            self.containerMenu.sendAllDataToRemote();
        }
    }
}
