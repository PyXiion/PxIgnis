package ru.pyxiion.ignis.mixins;

import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
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
    @Inject(method = "consumeItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/LivingEntity;getActiveHand()Lnet/minecraft/util/Hand;"), cancellable = true)
    private void pxrp$onConsumeItem(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self.getEntityWorld().isClient()) return;

        ItemStack stack = self.getActiveItem();
        if (stack.isEmpty()) return;

        var results = PxIgnis.instance.runtime.getEventManager()
                .fireWithResults("player_consume_item",
                        EntityFactory.INSTANCE.wrap(self),
                        ItemStackWrap.INSTANCE.wrap(stack));

        if (results.stream().anyMatch(r -> r.isboolean() && !r.toboolean())) {
            ci.cancel();
        }
    }
}
