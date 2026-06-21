package ru.pyxiion.ignis.api.util

import net.minecraft.entity.ai.goal.Goal
import net.minecraft.entity.mob.MobEntity
import ru.pyxiion.ignis.api.manager.MobAIManager
import java.util.EnumSet

class LuaGoal(private val mob: MobEntity) : Goal() {
    init {
        controls = EnumSet.of(Control.MOVE, Control.LOOK, Control.JUMP)
    }

    private val alive: Boolean get() = mob.isAlive && !mob.isRemoved

    override fun canStart(): Boolean = alive
    override fun shouldContinue(): Boolean = alive
    override fun stop() = MobAIManager.onGoalStopped(mob)
    override fun tick() = MobAIManager.tickMob(mob)

    class Target(private val mob: MobEntity) : Goal() {
        init {
            controls = EnumSet.of(Control.TARGET)
        }

        override fun canStart(): Boolean = mob.isAlive && !mob.isRemoved
        override fun shouldContinue(): Boolean = mob.isAlive && !mob.isRemoved
    }
}
