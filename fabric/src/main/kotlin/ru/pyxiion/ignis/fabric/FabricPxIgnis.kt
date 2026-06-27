package ru.pyxiion.ignis.fabric

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
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import ru.pyxiion.ignis.CommonEntryPoint
import ru.pyxiion.ignis.IgnisPlatform

class FabricPxIgnis : ModInitializer {
    companion object {
        const val MOD_ID = "pxignis"
        val logger = org.slf4j.LoggerFactory.getLogger(MOD_ID)
    }

    private val entry = CommonEntryPoint()

    override fun onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            try {
                IgnisPlatform.instance = FabricPlatform(server)
                entry.onServerStart(server)
            } catch (e: Throwable) {
                logger.error("Ошибка при запуске PxIgnis: ${e.message}", e)
            }
        }

        ServerLifecycleEvents.SERVER_STOPPING.register {
            entry.onServerStopping()
        }

        ServerTickEvents.END_SERVER_TICK.register {
            entry.onServerTick()
        }

        ServerEntityEvents.ENTITY_LOAD.register { entity, world ->
            entry.onEntityLoad(entity, world)
        }

        ServerEntityEvents.ENTITY_UNLOAD.register { entity, _ ->
            entry.onEntityUnload(entity)
        }

        ServerPlayConnectionEvents.INIT.register { handler, server ->
            if (!entry.onPlayerJoinInit(handler, server)) {
                handler.disconnect(net.minecraft.network.chat.Component.literal("You are not allowed to join this server"))
            }
        }

        ServerPlayerEvents.JOIN.register { player ->
            entry.onPlayerJoin(player)
        }

        ServerPlayerEvents.AFTER_RESPAWN.register { oldPlayer, newPlayer, alive ->
            entry.onPlayerRespawn(oldPlayer, newPlayer, alive)
        }

        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            entry.onPlayerLeave(handler)
        }

        ServerLivingEntityEvents.ALLOW_DEATH.register { entity, source, amount ->
            entry.onLivingDeath(entity, source, amount)
        }

        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register { message, sender, _ ->
            entry.onPlayerChat(sender, message.signedBody.content)
        }

        PlayerBlockBreakEvents.BEFORE.register { world, player, pos, state, _ ->
            if (player is ServerPlayer) {
                entry.onPlayerBlockBreak(player, pos, state)
            } else true
        }

        UseBlockCallback.EVENT.register { player, world, hand, hitResult ->
            if (player is ServerPlayer) {
                entry.onPlayerBlockPlace(player, world, hitResult.blockPos, player.getItemInHand(hand))
            } else InteractionResult.PASS
        }

        UseItemCallback.EVENT.register { player, world, hand ->
            if (player is ServerPlayer) {
                entry.onPlayerUseItem(player, hand, world)
            } else InteractionResult.PASS
        }

        AttackEntityCallback.EVENT.register { player, world, hand, entity, hitResult ->
            if (player is ServerPlayer) {
                entry.onPlayerAttackEntity(player, world, entity)
            } else InteractionResult.PASS
        }

        UseEntityCallback.EVENT.register { player, world, hand, entity, hitResult ->
            if (player is ServerPlayer) {
                entry.onPlayerInteractEntity(player, world, hand, entity)
            } else InteractionResult.PASS
        }

        ServerLivingEntityEvents.ALLOW_DAMAGE.register { entity, source, amount ->
            entry.onLivingDamage(entity, source, amount)
        }

        ServerLivingEntityEvents.AFTER_DAMAGE.register { entity, source, _, damageTaken, blocked ->
            entry.onLivingDamagePost(entity, source, damageTaken, blocked)
        }

        ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY.register { world, entity, killedEntity, damageSource ->
            if (entity is ServerPlayer) {
                entry.onPlayerKill(entity, killedEntity, damageSource)
            }
        }

        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            entry.registerCommand(dispatcher)
        }
    }
}
