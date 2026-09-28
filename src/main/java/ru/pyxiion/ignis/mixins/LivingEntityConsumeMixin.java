package ru.pyxiion.ignis.mixins;

import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.pyxiion.ignis.events.GameEvents;

@Mixin(LivingEntity.class)
public abstract class LivingEntityConsumeMixin {

    // player.consume — fires when entity finishes using a consumable item
    @Inject(method = "consumeItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/LivingEntity;getActiveHand()Lnet/minecraft/util/Hand;"), cancellable = true)
    private void pxrp$onConsumeItem(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self.getEntityWorld().isClient()) return;

        ItemStack stack = self.getActiveItem();
        if (stack.isEmpty()) return;

        if (!GameEvents.consume(self, stack)) {
            ci.cancel();
        }
    }
}
