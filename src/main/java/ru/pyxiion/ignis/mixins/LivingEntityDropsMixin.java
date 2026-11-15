package ru.pyxiion.ignis.mixins;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import ru.pyxiion.ignis.events.DeathDrops;
import ru.pyxiion.ignis.events.DropBuffer;
import ru.pyxiion.ignis.events.DropCapture;
import ru.pyxiion.ignis.events.GameEvents;

@Mixin(LivingEntity.class)
public abstract class LivingEntityDropsMixin {
    @Unique
    private int pxrp$xp = -1;

    @Shadow
    protected abstract void dropEquipment(ServerLevel level);

    @Shadow
    protected abstract void dropExperience(ServerLevel level, Entity killer);

    // entity.drops: vanilla runs its whole loot step inside a capture window (see DropCapture), so the stacks it
    // would spawn land in a buffer and XP waits. The event then shows them as the list e.drops, and what is left
    // after the handlers is spawned. A cancel throws the buffer away.
    @WrapMethod(method = "dropAllDeathLoot")
    private void pxrp$onDrops(ServerLevel level, DamageSource source, Operation<Void> original) {
        LivingEntity self = (LivingEntity) (Object) this;
        DropBuffer<ItemStack> buffer = DropCapture.begin(self);
        try {
            original.call(level, source);
        } finally {
            DropCapture.end(buffer);
        }
        DeathDrops.Outcome<ItemStack> outcome = GameEvents.drops(self, level, source, buffer.getItems());
        if (outcome == null) return;
        for (ItemStack stack : outcome.getItems()) self.spawnAtLocation(level, stack);
        if (buffer.getEquipmentDeferred()) dropEquipment(level);
        pxrp$xp = outcome.getXp();
        try {
            dropExperience(level, source.getEntity());
        } finally {
            pxrp$xp = -1;
        }
    }

    // Inside the capture window XP orbs wait for the event; the event's own dropExperience call comes after it.
    @WrapMethod(method = "dropExperience")
    private void pxrp$holdXp(ServerLevel level, Entity killer, Operation<Void> original) {
        if (!DropCapture.active(this)) original.call(level, killer);
    }

    @WrapOperation(method = "dropExperience", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getExperienceReward(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Entity;)I"))
    private int pxrp$xpReward(LivingEntity self, ServerLevel level, Entity killer, Operation<Integer> original) {
        return pxrp$xp >= 0 ? pxrp$xp : original.call(self, level, killer);
    }
}
