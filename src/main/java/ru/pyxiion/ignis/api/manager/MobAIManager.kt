package ru.pyxiion.ignis.api.manager

import net.minecraft.entity.Entity
import net.minecraft.entity.ai.brain.Brain
import net.minecraft.entity.mob.MobEntity
import net.minecraft.server.MinecraftServer
import net.minecraft.server.world.ServerWorld
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
        val mob: MobEntity,
    )

    data class PendingAttach(
        val behaviourId: String,
        val mob: MobEntity,
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

    private fun tryClearBrain(mob: MobEntity) {
        try {
            val brainMethod = mob.javaClass.getMethod("getBrain")
            val brain = brainMethod.invoke(mob) as? Brain<*>
            brain?.clear()
        } catch (_: NoSuchMethodException) {
        } catch (_: Throwable) {
            PxIgnis.logger.warn("Не удалось очистить Brain моба: ${mob.type}")
        }
    }

    fun setAI(mob: MobEntity, idOrFn: LuaValue) {
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

        mob.commandTags.filter { it.startsWith("pxrp_behaviour:") }.forEach {
            mob.removeCommandTag(it)
        }
        tag?.let { mob.addCommandTag(it) }
    }

    private fun applyBehaviour(mob: MobEntity, id: String?, fn: LuaFunction) {
        val mixin = mob as MobEntityMixin

        mixin.goalSelector.goals.toList().forEach { mixin.goalSelector.remove(it.goal) }
        mixin.targetSelector.goals.toList().forEach { mixin.targetSelector.remove(it.goal) }

        tryClearBrain(mob)

        val goal = LuaGoal(mob)
        val targetGoal = LuaGoal.Target(mob)

        mixin.goalSelector.add(0, goal)
        mixin.targetSelector.add(0, targetGoal)

        val state = LuaTable()
        mobWrappers[mob.uuid] = MobWrap.wrap(mob)

        activeMobs[mob.uuid] = ActiveMobAI(id, fn, state, goal, targetGoal, mob)
    }

    fun clearAI(mob: MobEntity): Boolean {
        val entry = activeMobs.remove(mob.uuid) ?: return false

        val mixin = mob as MobEntityMixin
        mixin.goalSelector.remove(entry.goal)
        mixin.targetSelector.remove(entry.targetGoal)
        mixin.invokeInitGoals()

        mob.commandTags.filter { it.startsWith("pxrp_behaviour:") }.forEach {
            mob.removeCommandTag(it)
        }
        mobWrappers.remove(mob.uuid)

        return true
    }

    fun hasAI(mob: MobEntity): Boolean = mob.uuid in activeMobs

    fun getBehaviourId(mob: MobEntity): String? = activeMobs[mob.uuid]?.behaviourId

    internal fun tickMob(mob: MobEntity) {
        val entry = activeMobs[mob.uuid] ?: return

        @Suppress("DEPRECATION")
        if (mob.entityWorld is ServerWorld && !mob.entityWorld.isChunkLoaded(mob.blockPos)) {
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

    fun onGoalStopped(mob: MobEntity) {
        cleanUp(mob.uuid)
    }

    fun onEntityRemove(mob: MobEntity) {
        val entry = activeMobs[mob.uuid] ?: return
        try {
            val mixin = mob as MobEntityMixin
            mixin.goalSelector.remove(entry.goal)
            mixin.targetSelector.remove(entry.targetGoal)
        } catch (_: Throwable) {}
        cleanUp(mob.uuid)
    }

    fun onEntityLoad(entity: Entity, world: ServerWorld) {
        if (entity !is MobEntity) return

        val behaviourTag = entity.commandTags.firstOrNull { it.startsWith("pxrp_behaviour:") }
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
        for (world in server.worlds) {
            for (entity in world.iterateEntities()) {
                if (entity is MobEntity) {
                    val tag = entity.commandTags.firstOrNull { it.startsWith("pxrp_behaviour:") }
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
