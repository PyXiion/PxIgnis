package ru.pyxiion.ignis

import kotlinx.coroutines.cancel
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
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.item.BlockItem
import net.minecraft.registry.Registries
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.util.ActionResult
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import ru.pyxiion.ignis.api.PlayerMoveDispatcher
import ru.pyxiion.ignis.api.manager.ContainerManager
import ru.pyxiion.ignis.api.manager.MobAIManager
import ru.pyxiion.ignis.api.manager.RegionManager
import ru.pyxiion.ignis.api.manager.SidebarManager
import ru.pyxiion.ignis.commands.IgnisCommand
import ru.pyxiion.ignis.events.GameEvents
import ru.pyxiion.ignis.network.RegionCapWarningPayload
import ru.pyxiion.ignis.network.RegionInterestPayload
import ru.pyxiion.ignis.network.RegionRemovePayload
import ru.pyxiion.ignis.network.RegionSyncPayload
import ru.pyxiion.ignis.network.RegionUpsertPayload
import ru.pyxiion.ignis.runtime.EditorSupport
import ru.pyxiion.ignis.runtime.ScriptWatchdog
import ru.pyxiion.ignis.storage.JsonBackend
import ru.pyxiion.ignis.storage.StorageManager

class PxIgnis : ModInitializer {
    companion object {
        const val MOD_ID = "pxignis"

        @JvmField
        val logger: Logger = LoggerFactory.getLogger(MOD_ID)
        lateinit var instance: PxIgnis
        var storageManager: StorageManager? = null
    }

    lateinit var runtime: IgnisRuntime

    fun hasRuntime(): Boolean = ::runtime.isInitialized


    override fun onInitialize() {
        instance = this

        PayloadTypeRegistry.playS2C().register(RegionSyncPayload.ID, RegionSyncPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(RegionUpsertPayload.ID, RegionUpsertPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(RegionRemovePayload.ID, RegionRemovePayload.CODEC)
        PayloadTypeRegistry.playS2C().register(RegionCapWarningPayload.ID, RegionCapWarningPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(RegionInterestPayload.ID, RegionInterestPayload.CODEC)

        ServerPlayNetworking.registerGlobalReceiver(RegionInterestPayload.ID) { payload, ctx ->
            val player = ctx.player()
            if (!Compat.isAdmin(player)) {
                logger.debug("rejected region-interest from non-admin {}", player.name.string)
                return@registerGlobalReceiver
            }
            RegionManager.setOptedIn(player.uuid, payload.enabled)
        }

        ServerLifecycleEvents.SERVER_STARTED.register(fun(server) {
            try {
                ScriptWatchdog.serverThread = Thread.currentThread()
                val ignisDir = FabricLoader.getInstance().configDir.resolve("ignis")
                EditorSupport.install(ignisDir, FabricLoader.getInstance().getModContainer(MOD_ID).flatMap { it.findPath("lua-types") }.orElse(null))
                val storagePath = ignisDir.resolve("storage")
                storageManager = StorageManager(JsonBackend(storagePath))
                runtime = IgnisRuntime(server, storageManager!!)
                runtime.reload()
                PlayerMoveDispatcher.handler = { player, from, to -> GameEvents.move(player, from, to) }
                // reload() has already fired "init"
                runtime.eventManager.fire("server_start")

            } catch (e: Throwable) {
                logger.error("Ошибка при запуске PxIgnis: ${e.message}", e)
            }
        })

        ServerLifecycleEvents.SERVER_STOPPING.register(fun(server) {
            try {
                runtime.modScope.cancel()
            } catch (_: UninitializedPropertyAccessException) {
            }
            try {
                if (storageManager != null) {
                    runtime.scheduler.clear()
                    runtime.eventManager.fire("uninit")
                    runtime.eventManager.fire("server_stop")
                }
            } catch (_: UninitializedPropertyAccessException) {
            }
            try {
                runtime.api.shutdownAsync()
                runtime.shutdown()
            } catch (_: UninitializedPropertyAccessException) {
            }
            storageManager?.close()
        })

        ServerLifecycleEvents.SERVER_STOPPED.register(fun(server) {
            RegionManager.closeAll(server)
        })

        ServerTickEvents.END_SERVER_TICK.register(fun(server) {
            if (::runtime.isInitialized) {
                runtime.scheduler.tick()
                val em = runtime.eventManager
                em.fire("tick")
                em.tick()
                RegionManager.tick()
                RegionManager.tickClientSync(server)
            }
        })

        ServerEntityEvents.ENTITY_LOAD.register { entity, world ->
            if (::runtime.isInitialized) {
                MobAIManager.onEntityLoad(entity, world)
                RegionManager.onEntityChunkLoad(entity)
                GameEvents.spawn(entity)
            }
        }

        ServerEntityEvents.ENTITY_UNLOAD.register { entity, world ->
            if (::runtime.isInitialized) {
                RegionManager.onEntityChunkUnload(entity)
                GameEvents.despawn(entity)
            }
        }

        ServerPlayConnectionEvents.INIT.register(fun(handler, server) {
            GameEvents.login(handler.player)?.let { handler.disconnect(Text.literal(it)) }
        })

        ServerPlayerEvents.JOIN.register { player -> GameEvents.join(player) }

        ServerPlayerEvents.AFTER_RESPAWN.register { oldPlayer, newPlayer, alive ->
            if (::runtime.isInitialized) {
                runtime.api.invalidatePlayer(newPlayer.uuid)
                GameEvents.respawn(newPlayer, alive)
            }
        }

        ServerPlayConnectionEvents.DISCONNECT.register(fun(handler, server) {
            runtime.api.invalidatePlayer(handler.player.uuid)
            ContainerManager.closeAll(handler.player)
            GameEvents.leave(handler.player)
            storageManager?.removePlayerData(handler.player.uuid.toString())
            SidebarManager.removeForPlayer(handler.player)
            MobAIManager.mobWrappers.remove(handler.player.uuid)
            RegionManager.onPlayerLeft(handler.player.uuid)
        })

        ServerLivingEntityEvents.ALLOW_DEATH.register { entity, source, amount ->
            if (!GameEvents.death(entity, source, amount)) return@register false
            if (::runtime.isInitialized) {
                RegionManager.onEntityDeath(entity, source.name.substringAfterLast("."), amount.toDouble())
            }
            true
        }

        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register(fun(message, sender, networkHandler): Boolean {
            return GameEvents.chat(sender, message.signedBody.content)
        })

        PlayerBlockBreakEvents.BEFORE.register { world, player, pos, state, _ ->
            if (player !is ServerPlayerEntity) return@register true
            GameEvents.breakBlock(player, pos, Registries.BLOCK.getId(state.block).toString())
        }

        UseBlockCallback.EVENT.register { player, world, hand, hitResult ->
            if (player is ServerPlayerEntity && !world.isClient) {
                val item = player.getStackInHand(hand).item
                if (item is BlockItem) {
                    val blockId = Registries.BLOCK.getId(item.block).toString()
                    if (!GameEvents.placeBlock(player, hitResult.blockPos, blockId)) return@register ActionResult.FAIL
                }
            }
            ActionResult.PASS
        }

        UseItemCallback.EVENT.register { player, world, hand ->
            if (player is ServerPlayerEntity && !world.isClient && !GameEvents.useItem(player, hand)) {
                return@register ActionResult.FAIL
            }
            ActionResult.PASS
        }

        AttackEntityCallback.EVENT.register { player, world, hand, entity, hitResult ->
            if (player is ServerPlayerEntity && !world.isClient && !GameEvents.attack(player, entity)) {
                return@register ActionResult.FAIL
            }
            ActionResult.PASS
        }

        UseEntityCallback.EVENT.register { player, world, hand, entity, hitResult ->
            if (player is ServerPlayerEntity && !world.isClient && !GameEvents.interact(player, entity, hand)) {
                return@register ActionResult.FAIL
            }
            ActionResult.PASS
        }

        ServerLivingEntityEvents.ALLOW_DAMAGE.register { entity, source, amount ->
            GameEvents.hurt(entity, source, amount)
        }

        ServerLivingEntityEvents.AFTER_DAMAGE.register { entity, source, _, damageTaken, blocked ->
            GameEvents.damaged(entity, source, damageTaken, blocked)
        }

        ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY.register { world, entity, killedEntity, damageSource ->
            if (entity is ServerPlayerEntity) GameEvents.kill(entity, killedEntity, damageSource)
        }

        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ -> IgnisCommand.register(dispatcher) }
    }


}