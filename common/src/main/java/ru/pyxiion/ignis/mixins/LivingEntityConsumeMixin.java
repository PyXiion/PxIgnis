package ru.pyxiion.ignis.mixins;

import net.minecraft.world.entity.LivingEntity;
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
        LivingEntity self = (LivingEntity) (Object) this;
        if (self.level().isClientSide()) return;

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
