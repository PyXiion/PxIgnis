package ru.pyxiion.ignis.mixins;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Mob.class)
public interface MobEntityMixin {
    @Accessor("goalSelector")
    GoalSelector getGoalSelector();

    @Accessor("targetSelector")
    GoalSelector getTargetSelector();

    @Invoker("registerGoals")
    void invokeInitGoals();
}
