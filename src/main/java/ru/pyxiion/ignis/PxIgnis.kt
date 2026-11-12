package ru.pyxiion.ignis

import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityCombatEvents
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.event.player.*
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import ru.pyxiion.ignis.api.PlayerMoveDispatcher
import ru.pyxiion.ignis.api.manager.BossBarManager
import ru.pyxiion.ignis.api.manager.HologramManager
import ru.pyxiion.ignis.api.manager.MobAIManager
import ru.pyxiion.ignis.api.manager.RegionManager
import ru.pyxiion.ignis.api.manager.SidebarManager
import ru.pyxiion.ignis.commands.IgnisCommand
import ru.pyxiion.ignis.events.GameEvents
import ru.pyxiion.ignis.runtime.EditorSupport
import ru.pyxiion.ignis.runtime.ScriptWatchdog

class PxIgnis : ModInitializer {
    companion object {
        const val MOD_ID = "pxignis"

        /** 5 minutes at 20 TPS. */
        private const val AUTOSAVE_TICKS = 6000

        @JvmField
        val logger: Logger = LoggerFactory.getLogger(MOD_ID)

        /**
         * Рантайм, пока сервер запущен. Все обработчики (Fabric и миксины) начинаются с проверки на null
         * и без рантайма ведут себя как ваниль.
         */
        @JvmStatic
        @Volatile
        var runtimeOrNull: IgnisRuntime? = null
    }

    override fun onInitialize() {

        ServerLifecycleEvents.SERVER_STARTED.register(fun(server) {
            ScriptWatchdog.serverThread = Thread.currentThread()
            val rt = try {
                EditorSupport.install(
                    FabricLoader.getInstance().configDir.resolve("ignis"),
                    FabricLoader.getInstance().getModContainer(MOD_ID).flatMap { it.findPath("lua-types") }.orElse(null),
                )
                IgnisRuntime(server)
            } catch (e: Throwable) {
                logger.error("Ошибка при запуске PxIgnis: ${e.message}", e)
                return
            }
            runtimeOrNull = rt
            PlayerMoveDispatcher.handler = { player, from, to -> GameEvents.move(player, from, to) }
            // Ошибка скрипта не должна лишать сервер рантайма и `/ignis reload`.
            try {
                rt.reload()
                rt.eventManager.fire("server_start")
            } catch (e: Throwable) {
                logger.error("Ошибка при запуске скриптов PxIgnis: ${e.message}", e)
            }
        })

        // SERVER_STOPPING срабатывает в начале stopServer, игроки отключаются уже после него:
        // ссылку на рантайм держим до SERVER_STOPPED, чтобы DISCONNECT выполнил очистку.
        ServerLifecycleEvents.SERVER_STOPPING.register(fun(_) {
            runtimeOrNull?.shutdown()
        })

        ServerLifecycleEvents.SERVER_STOPPED.register(fun(server) {
            PlayerMoveDispatcher.handler = null
            runtimeOrNull = null
        })

        // Ванильный /reload пересобирает дерево команд, поэтому Lua-команды регистрируются заново вместе со скриптами.
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register { _, _, success ->
            if (!success) return@register
            val rt = runtimeOrNull ?: return@register
            try {
                rt.reload()
            } catch (e: Throwable) {
                logger.error("Ошибка при перезагрузке скриптов PxIgnis: ${e.message}", e)
            }
        }

        ServerTickEvents.END_SERVER_TICK.register(fun(server) {
            HologramManager.tick()
            val rt = runtimeOrNull ?: return
            // After the scheduler, so a coroutine whose sleep ends this tick resumes this tick.
            rt.scheduler.tick()
            rt.api.asyncLib.drain()
            rt.eventManager.fire("tick")
            rt.eventManager.tick()
            RegionManager.tick()
            if (server.tickCount % AUTOSAVE_TICKS == 0) rt.storage.saveAll()
        })

        ServerEntityEvents.ENTITY_LOAD.register { entity, world ->
            // До создания рантайма держателей голограмм нет, поэтому оставшиеся с прошлого запуска удаляются здесь.
            HologramManager.onEntityLoad(entity)
            if (runtimeOrNull == null || entity.isRemoved) return@register
            MobAIManager.onEntityLoad(entity, world)
            RegionManager.onEntityChunkLoad(entity)
            GameEvents.spawn(entity)
        }

        ServerEntityEvents.ENTITY_UNLOAD.register { entity, world ->
            if (runtimeOrNull == null) return@register
            RegionManager.onEntityChunkUnload(entity)
            GameEvents.despawn(entity)
        }

        ServerPlayConnectionEvents.INIT.register(fun(handler, server) {
            GameEvents.login(handler.player)?.let { handler.disconnect(Component.literal(it)) }
        })

        ServerPlayerEvents.JOIN.register { player -> GameEvents.join(player) }

        ServerPlayerEvents.AFTER_RESPAWN.register { oldPlayer, newPlayer, alive ->
            GameEvents.respawn(newPlayer, alive)
        }

        ServerPlayConnectionEvents.DISCONNECT.register(fun(handler, server) {
            val rt = runtimeOrNull ?: return
            rt.containers.closeAll(handler.player)
            GameEvents.leave(handler.player)
            rt.storage.removePlayerData(handler.player.uuid.toString())
            SidebarManager.removeForPlayer(handler.player)
            BossBarManager.onPlayerLeft(handler.player)
        })

        ServerLivingEntityEvents.ALLOW_DEATH.register { entity, source, amount ->
            if (!GameEvents.death(entity, source, amount)) return@register false
            if (runtimeOrNull != null) RegionManager.onEntityDeath(entity, source.msgId, amount.toDouble())
            true
        }

        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register(fun(message, sender, networkHandler): Boolean {
            return GameEvents.chat(sender, message.signedBody.content)
        })

        PlayerBlockBreakEvents.BEFORE.register { world, player, pos, state, _ ->
            if (player !is ServerPlayer) return@register true
            GameEvents.breakBlock(player, pos, BuiltInRegistries.BLOCK.getKey(state.block).toString())
        }

        UseItemCallback.EVENT.register { player, world, hand ->
            if (player is ServerPlayer && !world.isClientSide && !GameEvents.useItem(player, hand)) {
                return@register InteractionResult.FAIL
            }
            InteractionResult.PASS
        }

        AttackEntityCallback.EVENT.register { player, world, hand, entity, hitResult ->
            if (player is ServerPlayer && !world.isClientSide && !GameEvents.attack(player, entity)) {
                return@register InteractionResult.FAIL
            }
            InteractionResult.PASS
        }

        UseEntityCallback.EVENT.register { player, world, hand, entity, hitResult ->
            if (player is ServerPlayer && !world.isClientSide && !GameEvents.interact(player, entity, hand)) {
                return@register InteractionResult.FAIL
            }
            InteractionResult.PASS
        }

        ServerLivingEntityEvents.ALLOW_DAMAGE.register { entity, source, amount ->
            GameEvents.hurt(entity, source, amount)
        }

        ServerLivingEntityEvents.AFTER_DAMAGE.register { entity, source, _, damageTaken, blocked ->
            GameEvents.damaged(entity, source, damageTaken, blocked)
        }

        ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY.register { world, entity, killedEntity, damageSource ->
            if (entity is ServerPlayer) GameEvents.kill(entity, killedEntity, damageSource)
        }

        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ -> IgnisCommand.register(dispatcher) }
    }
}
