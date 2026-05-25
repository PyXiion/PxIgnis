package ru.pyxiion.pxrp

import me.lucko.fabric.api.permissions.v0.Permissions
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.command.permission.PermissionLevel
import net.minecraft.item.BlockItem
import net.minecraft.registry.Registries
import net.minecraft.server.command.CommandManager
import net.minecraft.text.Text
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaFunction
import org.luaj.vm2.LuaValue
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import ru.pyxiion.pxrp.api.Player
import ru.pyxiion.pxrp.api.Vector
import ru.pyxiion.pxrp.storage.JsonBackend
import ru.pyxiion.pxrp.storage.StorageManager

class PxRp : ModInitializer {
    companion object {
        val logger: Logger = LoggerFactory.getLogger("PxRP")
        lateinit var instance: PxRp
        var storageManager: StorageManager? = null
    }

    lateinit var luaLoader: LuaCmdLoader


    override fun onInitialize() {
        instance = this
        ServerLifecycleEvents.SERVER_STARTED.register(fun(server) {
            try {
                val storagePath = FabricLoader.getInstance().configDir.resolve("pxrp/storage")
                storageManager = StorageManager(JsonBackend(storagePath))
                luaLoader = LuaCmdLoader(server, storageManager!!)
                luaLoader.reload()
                luaLoader.eventManager.fire("server_start")
            } catch (e: Throwable) {
                logger.error("Ошибка при запуске PxRP: ${e.message}", e)
            }
        })

        ServerLifecycleEvents.SERVER_STOPPING.register(fun(server) {
            try {
                if (storageManager != null) {
                    luaLoader.scheduler.clear()
                    luaLoader.eventManager.fire("server_stop")
                }
            } catch (_: UninitializedPropertyAccessException) { }
            storageManager?.close()
        })

        ServerTickEvents.END_SERVER_TICK.register(fun(server) {
            if (::luaLoader.isInitialized) {
                luaLoader.scheduler.tick()
            }
        })

        ServerPlayConnectionEvents.INIT.register(fun(handler, server) {
            if (storageManager != null) {
                val luaPlayer = Player(handler.player).toLuaValue()
                val results = luaLoader.eventManager.fireWithResults("player_join", luaPlayer)
                if (results.any { it.isboolean() && !it.toboolean() }) {
                    handler.disconnect(Text.literal("You are not allowed to join this server"))
                }
            }
            // Restore personal sidebar after initial scoreboard sync
            if (::luaLoader.isInitialized) {
                val player = handler.player
                luaLoader.scheduler.schedule(2, object : LuaFunction() {
                    override fun call(): LuaValue {
                        luaLoader.personalSidebarManager.restoreForPlayer(player)
                        return LuaValue.NIL
                    }
                })
            }
        })

        ServerPlayConnectionEvents.DISCONNECT.register(fun(handler, server) {
            luaLoader.api.invalidatePlayer(handler.player.uuid)
            if (storageManager != null) {
                val luaPlayer = Player(handler.player).toLuaValue()
                luaLoader.eventManager.fire("player_leave", luaPlayer)
            }
            storageManager?.removePlayerData(handler.player.uuid.toString())
            luaLoader.personalSidebarManager.removeForPlayer(handler.player)
        })

        ServerLivingEntityEvents.AFTER_DEATH.register(fun(entity, source) {
            if (entity is net.minecraft.server.network.ServerPlayerEntity && storageManager != null) {
                val luaPlayer = Player(entity).toLuaValue()
                val damageTypeName = source.name.substringAfterLast(".")
                luaLoader.eventManager.fire("player_death", luaPlayer, LuaValue.valueOf(damageTypeName))
            }
        })

        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register(fun(message, sender, networkHandler): Boolean {
            if (storageManager != null) {
                val luaPlayer = Player(sender).toLuaValue()
                val text = message.signedBody.content
                val results = luaLoader.eventManager.fireWithResults("player_chat", luaPlayer, LuaValue.valueOf(text))
                if (results.any { it.isboolean() && !it.toboolean() }) return false
            }
            return true
        })

        PlayerBlockBreakEvents.BEFORE.register { world, player, pos, state, _ ->
            if (storageManager != null && player is net.minecraft.server.network.ServerPlayerEntity) {
                val luaPlayer = Player(player).toLuaValue()
                val posTable = Vector(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble()).toLuaValue()
                val blockId = LuaValue.valueOf(Registries.BLOCK.getId(state.block).toString())
                val results = luaLoader.eventManager.fireWithResults("player_block_break", luaPlayer, posTable, blockId)
                if (results.any { it.isboolean() && !it.toboolean() }) return@register false
            }
            true
        }

        UseBlockCallback.EVENT.register { player, world, hand, hitResult ->
            if (storageManager != null && player is net.minecraft.server.network.ServerPlayerEntity && !world.isClient) {
                val stack = player.getStackInHand(hand)
                if (stack.item is BlockItem) {
                    val luaPlayer = Player(player).toLuaValue()
                    val pos = hitResult.blockPos
                    val posTable = Vector(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble()).toLuaValue()
                    val blockId = LuaValue.valueOf(Registries.BLOCK.getId((stack.item as BlockItem).block).toString())
                    val results = luaLoader.eventManager.fireWithResults("player_block_place", luaPlayer, posTable, blockId)
                    if (results.any { it.isboolean() && !it.toboolean() }) return@register net.minecraft.util.ActionResult.FAIL
                }
            }
            net.minecraft.util.ActionResult.PASS
        }

        CommandRegistrationCallback.EVENT.register(fun(dispatcher, reg, env) {
            dispatcher.register(
                CommandManager.literal("pxrp")
                    .requires(Permissions.require("pyxiion.pxrp", PermissionLevel.ADMINS))
                    .then(
                        CommandManager.literal("reload").executes { ctx ->
                            try {
                                luaLoader.reload()
                                ctx.source.sendFeedback({
                                    Text.literal("Перезагрузилось")
                                }, false)
                                return@executes 1
                            } catch (e: LuaError) {
                                logger.error("Ошибка при перезагрузке PxRP: ${e.message}")
                                ctx.source.sendFeedback({
                                    Text.literal("Ошибка при перезагрузке PxRP: ${e.message}")
                                }, false)
                            } catch (e: Throwable) {
                                logger.error("Ошибка при перезагрузке PxRP: ${e.message}", e)
                                ctx.source.sendFeedback({
                                    Text.literal("Ошибка при перезагрузке PxRP: ${e.message}")
                                }, false)
                            }
                            0
                        }
                    ))
        })
    }


}