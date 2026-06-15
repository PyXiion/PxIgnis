package ru.pyxiion.ignis.mixins;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.pyxiion.ignis.api.MobAIManager;

@Mixin(LivingEntity.class)
public class LivingEntityMixin {
    @Inject(method = "remove", at = @At("HEAD"))
    private void onRemove(Entity.RemovalReason reason, CallbackInfo ci) {
        if ((Object) this instanceof MobEntity mob) {
            MobAIManager.INSTANCE.onEntityRemove(mob);
        }
    }
}
