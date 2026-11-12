package ru.pyxiion.ignis.api.util

import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.entity.Mob
import ru.pyxiion.ignis.api.manager.MobAIManager
import java.util.EnumSet

class LuaGoal(private val mob: Mob) : Goal() {
    init {
        flags = EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP)
    }

    private val alive: Boolean get() = mob.isAlive && !mob.isRemoved

    override fun canUse(): Boolean = alive
    override fun canContinueToUse(): Boolean = alive
    // stop() is the selector pausing the goal (a higher priority goal took the flags): that is not the end of the AI.
    override fun tick() = MobAIManager.tickMob(mob)

    class Target(private val mob: Mob) : Goal() {
        init {
            flags = EnumSet.of(Flag.TARGET)
        }

        override fun canUse(): Boolean = mob.isAlive && !mob.isRemoved
        override fun canContinueToUse(): Boolean = mob.isAlive && !mob.isRemoved
    }
}
