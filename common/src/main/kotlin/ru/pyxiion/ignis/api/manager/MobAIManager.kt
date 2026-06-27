package ru.pyxiion.ignis.api.manager

import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.ai.Brain
import net.minecraft.world.entity.Mob
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaFunction
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import ru.pyxiion.ignis.PxIgnis
import ru.pyxiion.ignis.api.util.LuaGoal
import ru.pyxiion.ignis.api.wrapper.MobWrap
import ru.pyxiion.ignis.mixins.MobEntityMixin
import java.util.UUID

object MobAIManager {
    data class ActiveMobAI(
        val behaviourId: String?,
        val fn: LuaFunction,
        val state: LuaTable,
        val goal: LuaGoal,
        val targetGoal: LuaGoal.Target,
        val mob: Mob,
    )

    data class PendingAttach(
        val behaviourId: String,
        val mob: Mob,
    )

    val behaviours = mutableMapOf<String, LuaFunction>()
    val activeMobs = mutableMapOf<UUID, ActiveMobAI>()
    val mobWrappers = mutableMapOf<UUID, LuaValue>()
    val pendingAttachments = mutableListOf<PendingAttach>()

    fun registerBehaviour(id: String, fn: LuaFunction) {
        behaviours[id] = fn

        pendingAttachments.removeAll { attach ->
            if (attach.behaviourId == id) {
                try {
                    applyBehaviour(attach.mob, id, fn)
                } catch (e: Throwable) {
                    PxIgnis.logger.warn("Не удалось применить поведение '$id' к мобу: ${e.message}")
                }
                true
            } else false
        }
    }

    private fun tryClearBrain(mob: Mob) {
        try {
            val brainMethod = mob.javaClass.getMethod("getBrain")
            val brain = brainMethod.invoke(mob) as? Brain<*>
            brain?.clearMemories()
        } catch (_: NoSuchMethodException) {
        } catch (_: Throwable) {
            PxIgnis.logger.warn("Не удалось очистить Brain моба: ${mob.type}")
        }
    }

    fun setAI(mob: Mob, idOrFn: LuaValue) {
        clearAI(mob)

        val id: String?
        val fn: LuaFunction
        val tag: String?

        when {
            idOrFn.isstring() -> {
                id = idOrFn.tojstring()
                fn = behaviours[id] ?: throw LuaError("Поведение '$id' не зарегистрировано. Используйте mc.registerBehaviour('$id', fn)")
                tag = "pxrp_behaviour:$id"
            }
            idOrFn.isfunction() -> {
                id = null
                fn = idOrFn.checkfunction()
                tag = null
            }
            else -> throw LuaError("setAI: ожидается ID поведения (строка) или функция")
        }

        applyBehaviour(mob, id, fn)

        mob.tags.filter { it.startsWith("pxrp_behaviour:") }.forEach {
            mob.removeTag(it)
        }
        tag?.let { mob.addTag(it) }
    }

    private fun applyBehaviour(mob: Mob, id: String?, fn: LuaFunction) {
        val mixin = mob as MobEntityMixin

        mixin.goalSelector.availableGoals.toList().forEach { mixin.goalSelector.removeGoal(it.goal) }
        mixin.targetSelector.availableGoals.toList().forEach { mixin.targetSelector.removeGoal(it.goal) }

        tryClearBrain(mob)

        val goal = LuaGoal(mob)
        val targetGoal = LuaGoal.Target(mob)

        mixin.goalSelector.addGoal(0, goal)
        mixin.targetSelector.addGoal(0, targetGoal)

        val state = LuaTable()
        mobWrappers[mob.uuid] = MobWrap.wrap(mob)

        activeMobs[mob.uuid] = ActiveMobAI(id, fn, state, goal, targetGoal, mob)
    }

    fun clearAI(mob: Mob): Boolean {
        val entry = activeMobs.remove(mob.uuid) ?: return false

        val mixin = mob as MobEntityMixin
        mixin.goalSelector.removeGoal(entry.goal)
        mixin.targetSelector.removeGoal(entry.targetGoal)
        mixin.invokeInitGoals()

        mob.tags.filter { it.startsWith("pxrp_behaviour:") }.forEach {
            mob.removeTag(it)
        }
        mobWrappers.remove(mob.uuid)

        return true
    }

    fun hasAI(mob: Mob): Boolean = mob.uuid in activeMobs

    fun getBehaviourId(mob: Mob): String? = activeMobs[mob.uuid]?.behaviourId

    internal fun tickMob(mob: Mob) {
        val entry = activeMobs[mob.uuid] ?: return

        @Suppress("DEPRECATION")
        if (mob.level() is ServerLevel && !mob.level().isLoaded(mob.blockPosition())) {
            return
        }

        val wrapper = mobWrappers.getOrPut(mob.uuid) {
            MobWrap.wrap(mob)
        }

        try {
            entry.fn.call(wrapper, entry.state)
        } catch (e: Throwable) {
            PxIgnis.logger.warn("Ошибка в AI-поведении моба ${mob.type}: ${e.message}", e)
        }
    }

    fun onGoalStopped(mob: Mob) {
        cleanUp(mob.uuid)
    }

    fun onEntityRemove(mob: Mob) {
        val entry = activeMobs[mob.uuid] ?: return
        try {
            val mixin = mob as MobEntityMixin
            mixin.goalSelector.removeGoal(entry.goal)
            mixin.targetSelector.removeGoal(entry.targetGoal)
        } catch (_: Throwable) {}
        cleanUp(mob.uuid)
    }

    fun onEntityLoad(entity: Entity, world: ServerLevel) {
        if (entity !is Mob) return

        val behaviourTag = entity.tags.firstOrNull { it.startsWith("pxrp_behaviour:") }
            ?: return

        val id = behaviourTag.removePrefix("pxrp_behaviour:")
        if (id.isEmpty()) return

        val fn = behaviours[id]
        if (fn != null) {
            try {
                applyBehaviour(entity, id, fn)
            } catch (e: Throwable) {
                PxIgnis.logger.warn("Не удалось восстановить поведение '$id' при загрузке моба: ${e.message}")
            }
        } else {
            pendingAttachments.add(PendingAttach(id, entity))
        }
    }

    fun restoreAll() {
        activeMobs.values.toList().forEach { entry ->
            try {
                val mob = entry.mob
                if (!mob.isRemoved && mob.isAlive) {
                    clearAI(mob)
                }
            } catch (_: Throwable) {}
        }
        activeMobs.clear()
        mobWrappers.clear()
        pendingAttachments.clear()
    }

    fun scanAndReapply(server: MinecraftServer) {
        for (world in server.allLevels) {
            for (entity in world.getAllEntities()) {
                if (entity is Mob) {
                    val tag = entity.tags.firstOrNull { it.startsWith("pxrp_behaviour:") }
                        ?: continue
                    val id = tag.removePrefix("pxrp_behaviour:")
                    val fn = behaviours[id] ?: continue
                    try {
                        applyBehaviour(entity, id, fn)
                    } catch (e: Throwable) {
                        PxIgnis.logger.warn("Не удалось восстановить поведение '$id' после перезагрузки: ${e.message}")
                    }
                }
            }
        }
    }

    private fun cleanUp(uuid: UUID) {
        activeMobs.remove(uuid)
        mobWrappers.remove(uuid)
    }
}
