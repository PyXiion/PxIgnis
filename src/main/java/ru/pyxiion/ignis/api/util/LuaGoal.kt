package ru.pyxiion.ignis.api.util

import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.entity.Mob
import ru.pyxiion.ignis.api.manager.MobAIManager
import java.util.EnumSet

class LuaGoal(private val mob: Mob) : Goal() {
    init {
        flags = EnumSet.of(Control.MOVE, Control.LOOK, Control.JUMP)
    }

    private val alive: Boolean get() = mob.isAlive && !mob.isRemoved

    override fun canStart(): Boolean = alive
    override fun shouldContinue(): Boolean = alive
    override fun stop() = MobAIManager.onGoalStopped(mob)
    override fun tick() = MobAIManager.tickMob(mob)

    class Target(private val mob: Mob) : Goal() {
        init {
            flags = EnumSet.of(Control.TARGET)
        }

        override fun canStart(): Boolean = mob.isAlive && !mob.isRemoved
        override fun shouldContinue(): Boolean = mob.isAlive && !mob.isRemoved
    }
}
