package ru.pyxiion.ignis.mixins;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import ru.pyxiion.ignis.events.DeathDrops;
import ru.pyxiion.ignis.events.GameEvents;

@Mixin(LivingEntity.class)
public abstract class LivingEntityDropsMixin {
    @Unique
    private int pxrp$xp = -1;

    // entity.drops: one event per death, before items, equipment and XP. A cancel skips all three; e.xp replaces the XP.
    @WrapMethod(method = "dropAllDeathLoot")
    private void pxrp$onDrops(ServerLevel level, DamageSource source, Operation<Void> original) {
        LivingEntity self = (LivingEntity) (Object) this;
        int xp = GameEvents.drops(self, level, source);
        if (xp == DeathDrops.CANCELLED) return;
        pxrp$xp = xp;
        try {
            original.call(level, source);
        } finally {
            pxrp$xp = -1;
        }
    }

    @WrapOperation(method = "dropExperience", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getExperienceReward(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Entity;)I"))
    private int pxrp$xpReward(LivingEntity self, ServerLevel level, Entity killer, Operation<Integer> original) {
        return pxrp$xp >= 0 ? pxrp$xp : original.call(self, level, killer);
    }
}
