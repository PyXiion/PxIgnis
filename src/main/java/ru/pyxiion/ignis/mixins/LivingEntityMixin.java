package ru.pyxiion.ignis.mixins;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.pyxiion.ignis.api.manager.MobAIManager;

@Mixin(LivingEntity.class)
public class LivingEntityMixin {
    @Inject(method = "remove", at = @At(value="INVOKE", target="Lnet/minecraft/world/entity/LivingEntity;triggerOnDeathMobEffects(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Entity$RemovalReason;)V"))
    private void onRemove(Entity.RemovalReason reason, CallbackInfo ci) {
        if ((Object) this instanceof Mob mob) {
            MobAIManager.INSTANCE.onEntityRemove(mob);
        }
    }
}
