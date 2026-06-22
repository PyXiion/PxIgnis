package ru.pyxiion.ignis

import me.lucko.fabric.api.permissions.v0.Permissions
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.item.BlockItem
import net.minecraft.registry.Registries
import net.minecraft.server.command.CommandManager
import net.minecraft.text.Text
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaValue
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityCombatEvents
import net.fabricmc.fabric.api.event.player.AttackEntityCallback
import net.fabricmc.fabric.api.event.player.UseEntityCallback
import net.fabricmc.fabric.api.event.player.UseItemCallback
import net.minecraft.util.Hand
import ru.pyxiion.ignis.api.wrapper.EntityFactory
import ru.pyxiion.ignis.api.wrapper.ItemStackWrap
import ru.pyxiion.ignis.api.manager.MobAIManager
import ru.pyxiion.ignis.api.wrapper.PlayerWrap
import ru.pyxiion.ignis.api.manager.RegionManager
import ru.pyxiion.ignis.api.Vector
import ru.pyxiion.ignis.api.manager.ContainerManager
import ru.pyxiion.ignis.api.manager.SidebarManager
import ru.pyxiion.ignis.storage.JsonBackend
import ru.pyxiion.ignis.storage.StorageManager

class PxIgnis : ModInitializer {
    companion object {
        const val MOD_ID = "pxignis"

        val logger: Logger = LoggerFactory.getLogger(MOD_ID)
        lateinit var instance: PxIgnis
        var storageManager: StorageManager? = null
    }

    lateinit var runtime: IgnisRuntime


    override fun onInitialize() {
        instance = this
        ServerLifecycleEvents.SERVER_STARTED.register(fun(server) {
            try {
                val storagePath = FabricLoader.getInstance().configDir.resolve("ignis/storage")
                storageManager = StorageManager(JsonBackend(storagePath))
                runtime = IgnisRuntime(server, storageManager!!)
                runtime.reload()
                runtime.eventManager.fire("server_start")
                runtime.eventManager.fire("init")

            } catch (e: Throwable) {
                logger.error("Ошибка при запуске PxIgnis: ${e.message}", e)
            }
        })

        ServerLifecycleEvents.SERVER_STOPPING.register(fun(server) {
            try {
                if (storageManager != null) {
                    runtime.scheduler.clear()
                    runtime.eventManager.fire("uninit")
                    runtime.eventManager.fire("server_stop")
                }
            } catch (_: UninitializedPropertyAccessException) { }
            storageManager?.close()
        })

        ServerTickEvents.END_SERVER_TICK.register(fun(server) {
            if (::runtime.isInitialized) {
                runtime.scheduler.tick()
                val em = runtime.eventManager
                if (em.hasHandlers("tick")) {
                    em.fire("tick")
                    em.tick()
                }
                RegionManager.tick()
            }
        })

        ServerEntityEvents.ENTITY_LOAD.register { entity, world ->
            if (::runtime.isInitialized) {
                MobAIManager.onEntityLoad(entity, world)
                RegionManager.onEntityChunkLoad(entity)
                runtime.eventManager.fire("entity_spawn", EntityFactory.wrap(entity))
            }
        }

        ServerEntityEvents.ENTITY_UNLOAD.register { entity, world ->
            if (::runtime.isInitialized) {
                RegionManager.onEntityChunkUnload(entity)
                runtime.eventManager.fire("entity_despawn", EntityFactory.wrap(entity))
            }
        }

        ServerPlayConnectionEvents.INIT.register(fun(handler, server) {
            if (storageManager != null) {
                val luaPlayer = PlayerWrap.wrap(handler.player)
                val results = runtime.eventManager.fireWithResults("player_join_init", luaPlayer)
                if (results.any { it.isboolean() && !it.toboolean() }) {
                    handler.disconnect(Text.literal("You are not allowed to join this server"))
                }
            }
        })

        ServerPlayerEvents.JOIN.register { player ->
            if (::runtime.isInitialized) {
                runtime.eventManager.fire("player_join", PlayerWrap.wrap(player))
            }
        }

        ServerPlayerEvents.AFTER_RESPAWN.register { oldPlayer, newPlayer, alive ->
            if (::runtime.isInitialized) {
                runtime.eventManager.fire("player_respawn", PlayerWrap.wrap(newPlayer), LuaValue.valueOf(alive))
            }
        }

        ServerPlayConnectionEvents.DISCONNECT.register(fun(handler, server) {
            runtime.api.invalidatePlayer(handler.player.uuid)
            ContainerManager.closeAll(handler.player)
            if (storageManager != null) {
                val luaPlayer = PlayerWrap.wrap(handler.player)
                runtime.eventManager.fire("player_leave", luaPlayer)
            }
            storageManager?.removePlayerData(handler.player.uuid.toString())
            SidebarManager.removeForPlayer(handler.player)
            MobAIManager.mobWrappers.remove(handler.player.uuid)
        })

        ServerLivingEntityEvents.ALLOW_DEATH.register { entity, source, amount ->
            if (::runtime.isInitialized) {
                val results = runtime.eventManager.fireWithResults("entity_death", EntityFactory.wrap(entity), LuaValue.valueOf(source.name), LuaValue.valueOf(amount.toDouble()))
                if (results.any { it.isboolean() && !it.toboolean() }) return@register false
            }
            true
        }

        ServerLivingEntityEvents.AFTER_DEATH.register(fun(entity, source) {
            if (::runtime.isInitialized) {
                val damageTypeName = source.name.substringAfterLast(".")
                RegionManager.onEntityDeath(entity, damageTypeName, 0.0)
            }
            if (entity is net.minecraft.server.network.ServerPlayerEntity && storageManager != null) {
                val luaPlayer = PlayerWrap.wrap(entity)
                val damageTypeName = source.name.substringAfterLast(".")
                runtime.eventManager.fire("player_death", luaPlayer, LuaValue.valueOf(damageTypeName))
            }
        })

        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register(fun(message, sender, networkHandler): Boolean {
            if (storageManager != null) {
                val luaPlayer = PlayerWrap.wrap(sender)
                val text = message.signedBody.content
                val results = runtime.eventManager.fireWithResults("player_chat", luaPlayer, LuaValue.valueOf(text))
                if (results.any { it.isboolean() && !it.toboolean() }) return false
            }
            return true
        })

        PlayerBlockBreakEvents.BEFORE.register { world, player, pos, state, _ ->
            if (storageManager != null && player is net.minecraft.server.network.ServerPlayerEntity) {
                val luaPlayer = PlayerWrap.wrap(player)
                val posTable = Vector(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble()).toLuaValue()
                val blockId = LuaValue.valueOf(Registries.BLOCK.getId(state.block).toString())
                val results = runtime.eventManager.fireWithResults("player_block_break", luaPlayer, posTable, blockId)
                if (results.any { it.isboolean() && !it.toboolean() }) return@register false
            }
            true
        }

        UseBlockCallback.EVENT.register { player, world, hand, hitResult ->
            if (storageManager != null && player is net.minecraft.server.network.ServerPlayerEntity && !world.isClient) {
                val stack = player.getStackInHand(hand)
                if (stack.item is BlockItem) {
                    val luaPlayer = PlayerWrap.wrap(player)
                    val pos = hitResult.blockPos
                    val posTable = Vector(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble()).toLuaValue()
                    val blockId = LuaValue.valueOf(Registries.BLOCK.getId((stack.item as BlockItem).block).toString())
                    val results = runtime.eventManager.fireWithResults("player_block_place", luaPlayer, posTable, blockId)
                    if (results.any { it.isboolean() && !it.toboolean() }) return@register net.minecraft.util.ActionResult.FAIL
                }
            }
            net.minecraft.util.ActionResult.PASS
        }

        UseItemCallback.EVENT.register { player, world, hand ->
            if (storageManager != null && player is net.minecraft.server.network.ServerPlayerEntity && !world.isClient) {
                val luaPlayer = PlayerWrap.wrap(player)
                val handStr = if (hand == Hand.MAIN_HAND) "main" else "off"
                val itemStack = player.getStackInHand(hand)
                val itemWrapper = if (itemStack.isEmpty) LuaValue.NIL else ItemStackWrap.wrap(itemStack)
                val itemId = LuaValue.valueOf(net.minecraft.registry.Registries.ITEM.getId(itemStack.item).toString())
                val results = runtime.eventManager.fireWithResults("player_use_item", luaPlayer, LuaValue.valueOf(handStr), itemWrapper, itemId)
                if (results.any { it.isboolean() && !it.toboolean() }) return@register net.minecraft.util.ActionResult.FAIL
            }
            net.minecraft.util.ActionResult.PASS
        }

        AttackEntityCallback.EVENT.register { player, world, hand, entity, hitResult ->
            if (storageManager != null && player is net.minecraft.server.network.ServerPlayerEntity && !world.isClient) {
                val luaPlayer = PlayerWrap.wrap(player)
                val luaEntity = EntityFactory.wrap(entity)
                val results = runtime.eventManager.fireWithResults("player_attack_entity", luaPlayer, luaEntity)
                if (results.any { it.isboolean() && !it.toboolean() }) return@register net.minecraft.util.ActionResult.FAIL
            }
            net.minecraft.util.ActionResult.PASS
        }

        UseEntityCallback.EVENT.register { player, world, hand, entity, hitResult ->
            if (storageManager != null && player is net.minecraft.server.network.ServerPlayerEntity && !world.isClient) {
                val luaPlayer = PlayerWrap.wrap(player)
                val luaEntity = EntityFactory.wrap(entity)
                val handStr = if (hand == Hand.MAIN_HAND) "main" else "off"
                val results = runtime.eventManager.fireWithResults("player_interact_entity", luaPlayer, luaEntity, LuaValue.valueOf(handStr))
                if (results.any { it.isboolean() && !it.toboolean() }) return@register net.minecraft.util.ActionResult.FAIL
            }
            net.minecraft.util.ActionResult.PASS
        }

        ServerLivingEntityEvents.ALLOW_DAMAGE.register { entity, source, amount ->
            if (storageManager != null) {
                if (entity is net.minecraft.server.network.ServerPlayerEntity) {
                    val luaPlayer = PlayerWrap.wrap(entity)
                    val results = runtime.eventManager.fireWithResults("player_hurt", luaPlayer, LuaValue.valueOf(source.name.substringAfterLast(".")), LuaValue.valueOf(amount.toDouble()))
                    if (results.any { it.isboolean() && !it.toboolean() }) return@register false
                } else {
                    val luaEntity = EntityFactory.wrap(entity)
                    val sourceEntity = source.attacker?.let { EntityFactory.wrap(it) } ?: LuaValue.NIL
                    val results = runtime.eventManager.fireWithResults("entity_hurt", luaEntity, LuaValue.valueOf(source.name.substringAfterLast(".")), LuaValue.valueOf(amount.toDouble()), sourceEntity)
                    if (results.any { it.isboolean() && !it.toboolean() }) return@register false
                }
            }
            true
        }

        ServerLivingEntityEvents.AFTER_DAMAGE.register { entity, source, _, damageTaken, blocked ->
            if (storageManager != null) {
                if (entity is net.minecraft.server.network.ServerPlayerEntity) {
                    val luaPlayer = PlayerWrap.wrap(entity)
                    runtime.eventManager.fire("player_damage", luaPlayer,
                        LuaValue.valueOf(source.name.substringAfterLast(".")),
                        LuaValue.valueOf(damageTaken.toDouble()),
                        LuaValue.valueOf(blocked))
                } else {
                    val luaEntity = EntityFactory.wrap(entity)
                    val sourceEntity = source.attacker?.let { EntityFactory.wrap(it) } ?: LuaValue.NIL
                    runtime.eventManager.fire("entity_damage", luaEntity,
                        LuaValue.valueOf(source.name.substringAfterLast(".")),
                        LuaValue.valueOf(damageTaken.toDouble()),
                        sourceEntity,
                        LuaValue.valueOf(blocked))
                }
            }
        }

        ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY.register { world, entity, killedEntity, damageSource ->
            if (storageManager != null && entity is net.minecraft.server.network.ServerPlayerEntity) {
                val luaPlayer = PlayerWrap.wrap(entity)
                val luaKilled = EntityFactory.wrap(killedEntity)
                runtime.eventManager.fire("player_kill", luaPlayer, luaKilled, LuaValue.valueOf(damageSource.name.substringAfterLast(".")))
            }
        }

        CommandRegistrationCallback.EVENT.register(fun(dispatcher, reg, env) {
            dispatcher.register(
                CommandManager.literal("ignis")
                    .requires(Permissions.require("px.ignis", 4))
                    .then(
                        CommandManager.literal("reload").executes { ctx ->
                            try {
                                runtime.reload()
                                ctx.source.sendFeedback({
                                    Text.literal("Перезагрузилось")
                                }, false)
                                return@executes 1
                            } catch (e: LuaError) {
                                logger.error("Ошибка при перезагрузке PxIgnis: ${e.message}")
                                ctx.source.sendFeedback({
                                    Text.literal("Ошибка при перезагрузке PxIgnis: ${e.message}")
                                }, false)
                            } catch (e: Throwable) {
                                logger.error("Ошибка при перезагрузке PxIgnis: ${e.message}", e)
                                ctx.source.sendFeedback({
                                    Text.literal("Ошибка при перезагрузке PxIgnis: ${e.message}")
                                }, false)
                            }
                            0
                        }
                    ))
        })
    }


}