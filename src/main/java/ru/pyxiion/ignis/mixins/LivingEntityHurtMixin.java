package ru.pyxiion.ignis.mixins;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import ru.pyxiion.ignis.events.GameEvents;

@Mixin(LivingEntity.class)
public abstract class LivingEntityHurtMixin {

    // entity.hurt. Wraps the whole method instead of using Fabric's ALLOW_DAMAGE, which cannot change the amount.
    // The handlers' e.amount replaces the damage; an amount of 0 or less (or a cancel) drops the hit.
    @WrapMethod(method = "hurtServer")
    private boolean pxrp$onHurt(ServerLevel level, DamageSource source, float amount, Operation<Boolean> original) {
        float result = GameEvents.hurt((LivingEntity) (Object) this, source, amount);
        if (result <= 0f) return false;
        return original.call(level, source, result);
    }
}
