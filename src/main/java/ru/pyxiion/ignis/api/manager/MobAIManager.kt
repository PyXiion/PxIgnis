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
import ru.pyxiion.ignis.Compat
import ru.pyxiion.ignis.PxIgnis
import ru.pyxiion.ignis.api.util.LuaGoal
import ru.pyxiion.ignis.api.wrapper.MobWrap
import ru.pyxiion.ignis.mixins.MobEntityMixin
import ru.pyxiion.ignis.runtime.ScriptWatchdog
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

        Compat.entityTags(mob).filter { it.startsWith("pxrp_behaviour:") }.forEach {
            mob.removeTag(it)
        }
        tag?.let { mob.addTag(it) }
    }

    private fun applyBehaviour(mob: Mob, id: String?, fn: LuaFunction) {
        val mixin = mob as MobEntityMixin

        mixin.goalSelector.availableGoals.toList().forEach { mixin.goalSelector.removeGoal(it.goal) }
        mixin.targetSelector.availableGoals.toList().forEach { mixin.targetSelector.removeGoal(it.goal) }

        // The Brain is left alone: Brain.removeAllBehaviors() is not undone when the AI is detached, so a villager
        // or piglin would stay without behaviour for good. Brain-driven mobs get the Lua goal next to their own.

        val goal = LuaGoal(mob)
        val targetGoal = LuaGoal.Target(mob)

        mixin.goalSelector.addGoal(0, goal)
        mixin.targetSelector.addGoal(0, targetGoal)

        val state = LuaTable()
        mobWrappers[mob.uuid] = MobWrap.wrap(mob)

        activeMobs[mob.uuid] = ActiveMobAI(id, fn, state, goal, targetGoal, mob)
    }

    /** Takes the AI off the mob and forgets the wrapper; the persistent tag stays. */
    private fun detach(mob: Mob): Boolean {
        val entry = activeMobs.remove(mob.uuid) ?: return false

        val mixin = mob as MobEntityMixin
        mixin.goalSelector.removeGoal(entry.goal)
        mixin.targetSelector.removeGoal(entry.targetGoal)
        mixin.invokeInitGoals()
        mobWrappers.remove(mob.uuid)

        return true
    }

    /** `mob:clearAI()`: detaches and also forgets the behaviour tag, so the mob stays vanilla after a reload. */
    fun clearAI(mob: Mob): Boolean {
        val detached = detach(mob)
        Compat.entityTags(mob).filter { it.startsWith("pxrp_behaviour:") }.forEach {
            mob.removeTag(it)
        }
        return detached
    }

    fun hasAI(mob: Mob): Boolean = mob.uuid in activeMobs

    fun getBehaviourId(mob: Mob): String? = activeMobs[mob.uuid]?.behaviourId

    internal fun tickMob(mob: Mob) {
        val entry = activeMobs[mob.uuid] ?: return

        @Suppress("DEPRECATION")
        if (mob.level() is ServerLevel && !mob.level().hasChunkAt(mob.blockPosition())) {
            return
        }

        val wrapper = mobWrappers.getOrPut(mob.uuid) {
            MobWrap.wrap(mob)
        }

        try {
            ScriptWatchdog.guard { entry.fn.call(wrapper, entry.state) }
        } catch (e: Throwable) {
            PxIgnis.logger.warn("Ошибка в AI-поведении ${entry.behaviourId ?: "<функция>"} моба ${mob.type}: ${e.message}", e)
        }
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

        val behaviourTag = Compat.entityTags(entity).firstOrNull { it.startsWith("pxrp_behaviour:") }
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

    /**
     * Reload: detach every mob (goals restored, wrappers forgotten) but keep the `pxrp_behaviour:*` tags so
     * [scanAndReapply] can re-attach them. Registered behaviours are dropped; scripts register them again.
     */
    fun restoreAll() {
        activeMobs.values.toList().forEach { entry ->
            try {
                val mob = entry.mob
                if (!mob.isRemoved && mob.isAlive) {
                    detach(mob)
                }
            } catch (_: Throwable) {}
        }
        activeMobs.clear()
        mobWrappers.clear()
        pendingAttachments.clear()
        behaviours.clear()
    }

    fun scanAndReapply(server: MinecraftServer) {
        for (world in server.allLevels) {
            for (entity in world.allEntities) {
                if (entity is Mob) {
                    val tag = Compat.entityTags(entity).firstOrNull { it.startsWith("pxrp_behaviour:") }
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
