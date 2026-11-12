package ru.pyxiion.ignis

import me.lucko.fabric.api.permissions.v0.Permissions
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
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.commands.Commands
import net.minecraft.server.level.ServerPlayer
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaValue
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import ru.pyxiion.ignis.api.PlayerMoveDispatcher
import ru.pyxiion.ignis.api.Vector
import ru.pyxiion.ignis.api.manager.BossBarManager
import ru.pyxiion.ignis.api.manager.HologramManager
import ru.pyxiion.ignis.api.manager.MobAIManager
import ru.pyxiion.ignis.api.manager.RegionManager
import ru.pyxiion.ignis.api.manager.SidebarManager
import ru.pyxiion.ignis.api.wrapper.EntityFactory
import ru.pyxiion.ignis.api.wrapper.ItemStackWrap
import ru.pyxiion.ignis.api.wrapper.PlayerWrap

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
            val rt = try {
                IgnisRuntime(server)
            } catch (e: Throwable) {
                logger.error("Ошибка при запуске PxIgnis: ${e.message}", e)
                return
            }
            runtimeOrNull = rt
            PlayerMoveDispatcher.handler = { player, from, to ->
                runtimeOrNull?.eventManager?.fire("player_move") {
                    arrayOf(
                        PlayerWrap.wrap(player),
                        Vector.fromMc(from).toLuaValue(),
                        Vector.fromMc(to).toLuaValue(),
                    )
                }
            }
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
            RegionManager.tick()
            if (server.tickCount % AUTOSAVE_TICKS == 0) rt.storage.saveAll()
        })

        ServerEntityEvents.ENTITY_LOAD.register { entity, world ->
            // До создания рантайма держателей голограмм нет, поэтому оставшиеся с прошлого запуска удаляются здесь.
            HologramManager.onEntityLoad(entity)
            val rt = runtimeOrNull ?: return@register
            if (entity.isRemoved) return@register
            MobAIManager.onEntityLoad(entity, world)
            RegionManager.onEntityChunkLoad(entity)
            rt.eventManager.fire("entity_spawn") { arrayOf(EntityFactory.wrap(entity)) }
        }

        ServerEntityEvents.ENTITY_UNLOAD.register { entity, world ->
            val rt = runtimeOrNull ?: return@register
            RegionManager.onEntityChunkUnload(entity)
            rt.eventManager.fire("entity_despawn") { arrayOf(EntityFactory.wrap(entity)) }
        }

        ServerPlayConnectionEvents.INIT.register(fun(handler, server) {
            val rt = runtimeOrNull ?: return
            if (rt.eventManager.fireCancellable("player_join_init") { arrayOf(PlayerWrap.wrap(handler.player)) }) {
                handler.disconnect(Component.literal("You are not allowed to join this server"))
            }
        })

        ServerPlayerEvents.JOIN.register { player ->
            val rt = runtimeOrNull ?: return@register
            rt.eventManager.fire("player_join") { arrayOf(PlayerWrap.wrap(player)) }
        }

        ServerPlayerEvents.AFTER_RESPAWN.register { oldPlayer, newPlayer, alive ->
            val rt = runtimeOrNull ?: return@register
            rt.eventManager.fire("player_respawn") { arrayOf(PlayerWrap.wrap(newPlayer), LuaValue.valueOf(alive)) }
        }

        ServerPlayConnectionEvents.DISCONNECT.register(fun(handler, server) {
            val rt = runtimeOrNull ?: return
            rt.containers.closeAll(handler.player)
            rt.eventManager.fire("player_leave") { arrayOf(PlayerWrap.wrap(handler.player)) }
            rt.storage.removePlayerData(handler.player.uuid.toString())
            SidebarManager.removeForPlayer(handler.player)
            BossBarManager.onPlayerLeft(handler.player)
        })

        ServerLivingEntityEvents.ALLOW_DEATH.register { entity, source, amount ->
            val rt = runtimeOrNull ?: return@register true
            val args by lazy { damageArgs(entity, source, amount) }
            val bus = rt.eventManager
            val cancelled = bus.fireCancellable("entity_death") { args } ||
                (entity is ServerPlayer && bus.fireCancellable("player_death") { args })
            if (!cancelled) RegionManager.onEntityDeath(entity, source.msgId, amount.toDouble())
            !cancelled
        }

        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register(fun(message, sender, networkHandler): Boolean {
            val rt = runtimeOrNull ?: return true
            return !rt.eventManager.fireCancellable("player_chat") {
                arrayOf(PlayerWrap.wrap(sender), LuaValue.valueOf(message.signedBody.content))
            }
        })

        PlayerBlockBreakEvents.BEFORE.register { world, player, pos, state, _ ->
            val rt = runtimeOrNull ?: return@register true
            if (player !is ServerPlayer) return@register true
            !rt.eventManager.fireCancellable("player_block_break") {
                arrayOf(
                    PlayerWrap.wrap(player),
                    Vector.of(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble()).toLuaValue(),
                    LuaValue.valueOf(BuiltInRegistries.BLOCK.getKey(state.block).toString()),
                )
            }
        }

        UseItemCallback.EVENT.register { player, world, hand ->
            val rt = runtimeOrNull ?: return@register InteractionResult.PASS
            if (player !is ServerPlayer || world.isClientSide) return@register InteractionResult.PASS
            val cancelled = rt.eventManager.fireCancellable("player_use_item") {
                val itemStack = player.getItemInHand(hand)
                arrayOf(
                    PlayerWrap.wrap(player),
                    LuaValue.valueOf(if (hand == InteractionHand.MAIN_HAND) "main" else "off"),
                    if (itemStack.isEmpty) LuaValue.NIL else ItemStackWrap.wrap(itemStack),
                    LuaValue.valueOf(BuiltInRegistries.ITEM.getKey(itemStack.item).toString()),
                )
            }
            if (cancelled) InteractionResult.FAIL else InteractionResult.PASS
        }

        AttackEntityCallback.EVENT.register { player, world, hand, entity, hitResult ->
            val rt = runtimeOrNull ?: return@register InteractionResult.PASS
            if (player !is ServerPlayer || world.isClientSide) return@register InteractionResult.PASS
            val cancelled = rt.eventManager.fireCancellable("player_attack_entity") {
                arrayOf(PlayerWrap.wrap(player), EntityFactory.wrap(entity))
            }
            if (cancelled) InteractionResult.FAIL else InteractionResult.PASS
        }

        UseEntityCallback.EVENT.register { player, world, hand, entity, hitResult ->
            val rt = runtimeOrNull ?: return@register InteractionResult.PASS
            if (player !is ServerPlayer || world.isClientSide) return@register InteractionResult.PASS
            val cancelled = rt.eventManager.fireCancellable("player_interact_entity") {
                arrayOf(
                    PlayerWrap.wrap(player),
                    EntityFactory.wrap(entity),
                    LuaValue.valueOf(if (hand == InteractionHand.MAIN_HAND) "main" else "off"),
                )
            }
            if (cancelled) InteractionResult.FAIL else InteractionResult.PASS
        }

        ServerLivingEntityEvents.ALLOW_DAMAGE.register { entity, source, amount ->
            val rt = runtimeOrNull ?: return@register true
            val event = if (entity is ServerPlayer) "player_hurt" else "entity_hurt"
            !rt.eventManager.fireCancellable(event) { damageArgs(entity, source, amount) }
        }

        ServerLivingEntityEvents.AFTER_DAMAGE.register { entity, source, _, damageTaken, blocked ->
            val rt = runtimeOrNull ?: return@register
            if (entity is ServerPlayer) {
                rt.eventManager.fire("player_damage") {
                    arrayOf(
                        PlayerWrap.wrap(entity),
                        LuaValue.valueOf(source.msgId.substringAfterLast(".")),
                        LuaValue.valueOf(damageTaken.toDouble()),
                        LuaValue.valueOf(blocked),
                    )
                }
            } else {
                rt.eventManager.fire("entity_damage") {
                    arrayOf(
                        EntityFactory.wrap(entity),
                        LuaValue.valueOf(source.msgId.substringAfterLast(".")),
                        LuaValue.valueOf(damageTaken.toDouble()),
                        source.entity?.let { EntityFactory.wrap(it) } ?: LuaValue.NIL,
                        LuaValue.valueOf(blocked),
                    )
                }
            }
        }

        ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY.register { world, entity, killedEntity, damageSource ->
            val rt = runtimeOrNull ?: return@register
            if (entity !is ServerPlayer) return@register
            rt.eventManager.fire("player_kill") {
                arrayOf(
                    PlayerWrap.wrap(entity),
                    EntityFactory.wrap(killedEntity),
                    LuaValue.valueOf(damageSource.msgId.substringAfterLast(".")),
                )
            }
        }

        CommandRegistrationCallback.EVENT.register(fun(dispatcher, reg, env) {
            dispatcher.register(
                Commands.literal("ignis")
                    .requires(Permissions.require("px.ignis", 4))
                    .then(
                        Commands.literal("reload").executes { ctx ->
                            val rt = runtimeOrNull
                            if (rt == null) {
                                ctx.source.sendSuccess({ Component.literal("PxIgnis не запущен") }, false)
                                return@executes 0
                            }
                            try {
                                rt.reload()
                                ctx.source.sendSuccess({
                                    Component.literal("Перезагрузилось")
                                }, false)
                                return@executes 1
                            } catch (e: LuaError) {
                                logger.error("Ошибка при перезагрузке PxIgnis: ${e.message}")
                                ctx.source.sendSuccess({
                                    Component.literal("Ошибка при перезагрузке PxIgnis: ${e.message}")
                                }, false)
                            } catch (e: Throwable) {
                                logger.error("Ошибка при перезагрузке PxIgnis: ${e.message}", e)
                                ctx.source.sendSuccess({
                                    Component.literal("Ошибка при перезагрузке PxIgnis: ${e.message}")
                                }, false)
                            }
                            0
                        }
                    ))
        })
    }
}

/** Arguments of `player_hurt`, `entity_hurt`, `player_death`, `entity_death`: victim, amount, full msgId, attacker or nil. */
private fun damageArgs(victim: net.minecraft.world.entity.LivingEntity, source: net.minecraft.world.damagesource.DamageSource, amount: Float): Array<LuaValue> =
    arrayOf(
        EntityFactory.wrap(victim),
        LuaValue.valueOf(amount.toDouble()),
        LuaValue.valueOf(source.msgId),
        source.entity?.let { EntityFactory.wrap(it) } ?: LuaValue.NIL,
    )
