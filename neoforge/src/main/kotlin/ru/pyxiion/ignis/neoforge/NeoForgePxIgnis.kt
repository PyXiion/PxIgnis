package ru.pyxiion.ignis.neoforge

import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.level.ServerLevel
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionResult
import net.neoforged.fml.common.Mod
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.event.ServerChatEvent
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent
import net.neoforged.neoforge.event.level.BlockEvent
import net.neoforged.neoforge.event.server.ServerStartedEvent
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import ru.pyxiion.ignis.CommonEntryPoint
import ru.pyxiion.ignis.IgnisPlatform

@Mod("pxignis")
class NeoForgePxIgnis {
    companion object {
        val logger = org.slf4j.LoggerFactory.getLogger("PxIgnis")
    }

    private val entry = CommonEntryPoint()

    init {
        val bus = NeoForge.EVENT_BUS

        bus.addListener(ServerStartedEvent::class.java) { event ->
            logger.info("ServerStartedEvent received, initializing PxIgnis...")
            try {
                IgnisPlatform.instance = NeoForgePlatform(event.server)
                entry.onServerStart(event.server)
            } catch (e: Throwable) {
                logger.error("Error starting PxIgnis: ${e.message}", e)
            }
        }

        bus.addListener(ServerStoppingEvent::class.java) {
            entry.onServerStopping()
        }

        bus.addListener(ServerTickEvent.Post::class.java) {
            entry.onServerTick()
        }

        bus.addListener(EntityJoinLevelEvent::class.java) { event ->
            if (event.level is ServerLevel) {
                entry.onEntityLoad(event.entity, event.level as ServerLevel)
            }
        }

        bus.addListener(EntityLeaveLevelEvent::class.java) { event ->
            entry.onEntityUnload(event.entity)
        }

        bus.addListener(PlayerEvent.PlayerLoggedInEvent::class.java) { event ->
            val player = event.entity as? ServerPlayer ?: return@addListener
            if (!entry.onPlayerJoinInit(player.connection, player.level().server ?: return@addListener)) {
                player.connection.disconnect(Component.literal("<disconnected by PxIgnis>"))
            }
        }

        bus.addListener(PlayerEvent.PlayerLoggedInEvent::class.java) { event ->
            entry.onPlayerJoin(event.entity as ServerPlayer)
        }

        bus.addListener(PlayerEvent.PlayerRespawnEvent::class.java) { event ->
            entry.onPlayerRespawn(event.entity as ServerPlayer, event.entity as ServerPlayer, true)
        }

        bus.addListener(PlayerEvent.PlayerLoggedOutEvent::class.java) { event ->
            val player = event.entity as ServerPlayer
            entry.onPlayerLeave(player.connection)
        }

        bus.addListener(ServerChatEvent::class.java) { event ->
            val player = event.player as? ServerPlayer ?: return@addListener
            if (!entry.onPlayerChat(player, event.message.string)) {
                event.setCanceled(true)
            }
        }

        bus.addListener(LivingDeathEvent::class.java) { event ->
            val source = event.source
            val entity = event.entity
            if (!entry.onLivingDeath(entity, source, entity.health)) {
                event.setCanceled(true)
            }
            if (source.entity is ServerPlayer) {
                entry.onPlayerKill(source.entity as ServerPlayer, entity, source)
            }
        }

        bus.addListener(LivingIncomingDamageEvent::class.java) { event ->
            if (!entry.onLivingDamage(event.entity, event.source, event.amount)) {
                event.setCanceled(true)
            }
        }

        bus.addListener(LivingDamageEvent.Post::class.java) { event ->
            val entity = event.entity
            val source = event.source
            val damageDealt = event.newDamage
            val blocked = entity.isBlocking
            entry.onLivingDamagePost(entity, source, damageDealt, blocked)
        }

        bus.addListener(BlockEvent.BreakEvent::class.java) { event ->
            val player = event.player
            if (player is ServerPlayer) {
                if (!entry.onPlayerBlockBreak(player, event.pos, event.state)) {
                    event.setCanceled(true)
                }
            }
        }

        bus.addListener(BlockEvent.EntityPlaceEvent::class.java) { event ->
            if (event.entity is ServerPlayer && event.level is ServerLevel) {
                val player = event.entity as ServerPlayer
                val result = entry.onPlayerBlockPlace(player, event.level as ServerLevel, event.pos, player.mainHandItem)
                if (result == InteractionResult.FAIL) {
                    event.setCanceled(true)
                }
            }
        }

        bus.addListener(PlayerInteractEvent.RightClickItem::class.java) { event ->
            if (event.entity is ServerPlayer) {
                val player = event.entity as ServerPlayer
                val result = entry.onPlayerUseItem(player, event.hand, event.level)
                if (result == InteractionResult.FAIL) {
                    event.setCanceled(true)
                }
            }
        }

        bus.addListener(AttackEntityEvent::class.java) { event ->
            if (event.entity is ServerPlayer) {
                val player = event.entity as ServerPlayer
                val result = entry.onPlayerAttackEntity(player, player.level(), event.target)
                if (result == InteractionResult.FAIL) {
                    event.setCanceled(true)
                }
            }
        }

        bus.addListener(PlayerInteractEvent.EntityInteract::class.java) { event ->
            if (event.entity is ServerPlayer) {
                val player = event.entity as ServerPlayer
                val result = entry.onPlayerInteractEntity(player, player.level(), event.hand, event.target)
                if (result == InteractionResult.FAIL) {
                    event.setCanceled(true)
                }
            }
        }

        bus.addListener(RegisterCommandsEvent::class.java) { event ->
            entry.registerCommand(event.dispatcher)
        }
    }
}
