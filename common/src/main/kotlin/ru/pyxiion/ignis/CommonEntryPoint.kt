package ru.pyxiion.ignis

import net.minecraft.server.MinecraftServer
import net.minecraft.server.WorldStem
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.InteractionResult
import net.minecraft.world.InteractionHand
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.item.BlockItem
import net.minecraft.server.network.ServerGamePacketListenerImpl
import ru.pyxiion.ignis.api.PlayerMoveDispatcher
import ru.pyxiion.ignis.api.Vector
import ru.pyxiion.ignis.api.manager.ContainerManager
import ru.pyxiion.ignis.api.manager.MobAIManager
import ru.pyxiion.ignis.api.manager.RegionManager
import ru.pyxiion.ignis.api.manager.SidebarManager
import ru.pyxiion.ignis.api.wrapper.EntityFactory
import ru.pyxiion.ignis.api.wrapper.ItemStackWrap
import ru.pyxiion.ignis.api.wrapper.PlayerWrap
import ru.pyxiion.ignis.storage.JsonBackend
import ru.pyxiion.ignis.storage.StorageManager

private val logger = org.slf4j.LoggerFactory.getLogger("PxIgnis")

class CommonEntryPoint {
    lateinit var runtime: IgnisRuntime
    var storageManager: StorageManager? = null
        private set

    fun onServerStart(server: MinecraftServer) {
        val pxIgnis = PxIgnis()
        PxIgnis.instance = pxIgnis

        val storagePath = IgnisPlatform.instance.configDir.resolve("ignis/storage")
        val manager = StorageManager(JsonBackend(storagePath))
        storageManager = manager
        PxIgnis.storageManager = manager
        runtime = IgnisRuntime(server, manager)
        pxIgnis.runtime = runtime
        runtime.reload()
        PlayerMoveDispatcher.handler = { player, from, to ->
            runtime.eventManager.fire(
                "player_move",
                PlayerWrap.wrap(player),
                Vector.fromMc(from).toLuaValue(),
                Vector.fromMc(to).toLuaValue()
            )
        }
        runtime.eventManager.fire("server_start")
        runtime.eventManager.fire("init")
    }

    fun onServerStopping() {
        try {
            if (storageManager != null) {
                runtime.scheduler.clear()
                runtime.eventManager.fire("uninit")
                runtime.eventManager.fire("server_stop")
            }
        } catch (_: UninitializedPropertyAccessException) {
        }
        storageManager?.close()
    }

    fun onServerTick() {
        if (::runtime.isInitialized) {
            runtime.scheduler.tick()
            val em = runtime.eventManager
            if (em.hasHandlers("tick")) {
                em.fire("tick")
                em.tick()
            }
            RegionManager.tick()
        }
    }

    fun onEntityLoad(entity: Entity, world: ServerLevel): Boolean {
        if (::runtime.isInitialized) {
            MobAIManager.onEntityLoad(entity, world)
            RegionManager.onEntityChunkLoad(entity)
            runtime.eventManager.fire("entity_spawn", EntityFactory.wrap(entity))
        }
        return true
    }

    fun onEntityUnload(entity: Entity) {
        if (::runtime.isInitialized) {
            RegionManager.onEntityChunkUnload(entity)
            runtime.eventManager.fire("entity_despawn", EntityFactory.wrap(entity))
        }
    }

    fun onPlayerJoinInit(
        handler: ServerGamePacketListenerImpl,
        server: MinecraftServer
    ): Boolean {
        if (storageManager != null) {
            val luaPlayer = PlayerWrap.wrap(handler.player)
            val results = runtime.eventManager.fireWithResults("player_join_init", luaPlayer)
            if (results.any { it.isboolean() && !it.toboolean() }) return false
        }
        return true
    }

    fun onPlayerJoin(player: net.minecraft.server.level.ServerPlayer) {
        if (::runtime.isInitialized) {
            runtime.eventManager.fire("player_join", PlayerWrap.wrap(player))
        }
    }

    fun onPlayerRespawn(
        oldPlayer: net.minecraft.server.level.ServerPlayer,
        newPlayer: net.minecraft.server.level.ServerPlayer,
        alive: Boolean
    ) {
        if (::runtime.isInitialized) {
            runtime.api.invalidatePlayer(newPlayer.uuid)
            runtime.eventManager.fire(
                "player_respawn",
                PlayerWrap.wrap(newPlayer),
                org.luaj.vm2.LuaValue.valueOf(alive)
            )
        }
    }

    fun onPlayerLeave(handler: net.minecraft.server.network.ServerGamePacketListenerImpl) {
        runtime.api.invalidatePlayer(handler.player.uuid)
        ContainerManager.closeAll(handler.player)
        if (storageManager != null) {
            val luaPlayer = PlayerWrap.wrap(handler.player)
            runtime.eventManager.fire("player_leave", luaPlayer)
        }
        storageManager?.removePlayerData(handler.player.uuid.toString())
        SidebarManager.removeForPlayer(handler.player)
        MobAIManager.mobWrappers.remove(handler.player.uuid)
    }

    fun onLivingDeath(
        entity: net.minecraft.world.entity.LivingEntity,
        source: DamageSource,
        amount: Float
    ): Boolean {
        if (::runtime.isInitialized) {
            val entityW = EntityFactory.wrap(entity)
            val name = org.luaj.vm2.LuaValue.valueOf(source.msgId)
            val damage = org.luaj.vm2.LuaValue.valueOf(amount.toDouble())

            val results = runtime.eventManager.fireWithResults("entity_death", entityW, name, damage)
            if (results.any { it.isboolean() && !it.toboolean() }) return false

            if (entity is net.minecraft.server.level.ServerPlayer) {
                val playerResults = runtime.eventManager.fireWithResults("player_death", entityW, name, damage)
                if (playerResults.any { it.isboolean() && !it.toboolean() }) return false
            }
        }
        return true
    }

    fun onPlayerChat(sender: net.minecraft.server.level.ServerPlayer, message: String): Boolean {
        if (storageManager != null) {
            val luaPlayer = PlayerWrap.wrap(sender)
            val results =
                runtime.eventManager.fireWithResults("player_chat", luaPlayer, org.luaj.vm2.LuaValue.valueOf(message))
            if (results.any { it.isboolean() && !it.toboolean() }) return false
        }
        return true
    }

    fun onPlayerBlockBreak(
        player: net.minecraft.server.level.ServerPlayer,
        pos: net.minecraft.core.BlockPos,
        state: BlockState
    ): Boolean {
        if (storageManager != null) {
            val luaPlayer = PlayerWrap.wrap(player)
            val posTable = Vector.of(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble()).toLuaValue()
            val blockId =
                org.luaj.vm2.LuaValue.valueOf(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getId(state.block).toString())
            val results = runtime.eventManager.fireWithResults("player_block_break", luaPlayer, posTable, blockId)
            if (results.any { it.isboolean() && !it.toboolean() }) return false
        }
        return true
    }

    fun onPlayerBlockPlace(
        player: net.minecraft.server.level.ServerPlayer,
        world: net.minecraft.world.level.Level,
        pos: net.minecraft.core.BlockPos,
        stack: net.minecraft.world.item.ItemStack
    ): InteractionResult {
        if (storageManager != null && !world.isClientSide) {
            if (stack.item is BlockItem) {
                val luaPlayer = PlayerWrap.wrap(player)
                val posTable = Vector.of(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble()).toLuaValue()
                val blockId = org.luaj.vm2.LuaValue.valueOf(
                    net.minecraft.core.registries.BuiltInRegistries.BLOCK.getId((stack.item as BlockItem).block)
                        .toString()
                )
                val results = runtime.eventManager.fireWithResults("player_block_place", luaPlayer, posTable, blockId)
                if (results.any { it.isboolean() && !it.toboolean() }) return InteractionResult.FAIL
            }
        }
        return InteractionResult.PASS
    }

    fun onPlayerUseItem(
        player: net.minecraft.server.level.ServerPlayer,
        hand: InteractionHand,
        world: net.minecraft.world.level.Level
    ): InteractionResult {
        if (storageManager != null && !world.isClientSide) {
            val luaPlayer = PlayerWrap.wrap(player)
            val handStr = if (hand == InteractionHand.MAIN_HAND) "main" else "off"
            val itemStack = player.getItemInHand(hand)
            val itemWrapper = if (itemStack.isEmpty) org.luaj.vm2.LuaValue.NIL else ItemStackWrap.wrap(itemStack)
            val itemId =
                org.luaj.vm2.LuaValue.valueOf(net.minecraft.core.registries.BuiltInRegistries.ITEM.getId(itemStack.item).toString())
            val results = runtime.eventManager.fireWithResults(
                "player_use_item",
                luaPlayer,
                org.luaj.vm2.LuaValue.valueOf(handStr),
                itemWrapper,
                itemId
            )
            if (results.any { it.isboolean() && !it.toboolean() }) return InteractionResult.FAIL
        }
        return InteractionResult.PASS
    }

    fun onPlayerAttackEntity(
        player: net.minecraft.server.level.ServerPlayer,
        world: net.minecraft.world.level.Level,
        entity: net.minecraft.world.entity.Entity
    ): InteractionResult {
        if (storageManager != null && !world.isClientSide) {
            val luaPlayer = PlayerWrap.wrap(player)
            val luaEntity = EntityFactory.wrap(entity)
            val results = runtime.eventManager.fireWithResults("player_attack_entity", luaPlayer, luaEntity)
            if (results.any { it.isboolean() && !it.toboolean() }) return InteractionResult.FAIL
        }
        return InteractionResult.PASS
    }

    fun onPlayerInteractEntity(
        player: net.minecraft.server.level.ServerPlayer,
        world: net.minecraft.world.level.Level,
        hand: InteractionHand,
        entity: net.minecraft.world.entity.Entity
    ): InteractionResult {
        if (storageManager != null && !world.isClientSide) {
            val luaPlayer = PlayerWrap.wrap(player)
            val luaEntity = EntityFactory.wrap(entity)
            val handStr = if (hand == InteractionHand.MAIN_HAND) "main" else "off"
            val results = runtime.eventManager.fireWithResults(
                "player_interact_entity",
                luaPlayer,
                luaEntity,
                org.luaj.vm2.LuaValue.valueOf(handStr)
            )
            if (results.any { it.isboolean() && !it.toboolean() }) return InteractionResult.FAIL
        }
        return InteractionResult.PASS
    }

    fun onLivingDamage(
        entity: net.minecraft.world.entity.LivingEntity,
        source: DamageSource,
        amount: Float
    ): Boolean {
        if (storageManager != null) {
            if (entity is net.minecraft.server.level.ServerPlayer) {
                val luaPlayer = PlayerWrap.wrap(entity)
                val results = runtime.eventManager.fireWithResults(
                    "player_hurt",
                    luaPlayer,
                    org.luaj.vm2.LuaValue.valueOf(source.msgId.substringAfterLast(".")),
                    org.luaj.vm2.LuaValue.valueOf(amount.toDouble())
                )
                if (results.any { it.isboolean() && !it.toboolean() }) return false
            } else {
                val luaEntity = EntityFactory.wrap(entity)
                val sourceEntity = source.entity?.let { EntityFactory.wrap(it) } ?: org.luaj.vm2.LuaValue.NIL
                val results = runtime.eventManager.fireWithResults(
                    "entity_hurt",
                    luaEntity,
                    org.luaj.vm2.LuaValue.valueOf(source.msgId.substringAfterLast(".")),
                    org.luaj.vm2.LuaValue.valueOf(amount.toDouble()),
                    sourceEntity
                )
                if (results.any { it.isboolean() && !it.toboolean() }) return false
            }
        }
        return true
    }

    fun onLivingDamagePost(
        entity: net.minecraft.world.entity.LivingEntity,
        source: DamageSource,
        damageTaken: Float,
        blocked: Boolean
    ) {
        if (storageManager != null) {
            if (entity is net.minecraft.server.level.ServerPlayer) {
                val luaPlayer = PlayerWrap.wrap(entity)
                runtime.eventManager.fire(
                    "player_damage",
                    luaPlayer,
                    org.luaj.vm2.LuaValue.valueOf(source.msgId.substringAfterLast(".")),
                    org.luaj.vm2.LuaValue.valueOf(damageTaken.toDouble()),
                    org.luaj.vm2.LuaValue.valueOf(blocked)
                )
            } else {
                val luaEntity = EntityFactory.wrap(entity)
                val sourceEntity = source.entity?.let { EntityFactory.wrap(it) } ?: org.luaj.vm2.LuaValue.NIL
                runtime.eventManager.fire(
                    "entity_damage",
                    luaEntity,
                    org.luaj.vm2.LuaValue.valueOf(source.msgId.substringAfterLast(".")),
                    org.luaj.vm2.LuaValue.valueOf(damageTaken.toDouble()),
                    sourceEntity,
                    org.luaj.vm2.LuaValue.valueOf(blocked)
                )
            }
        }
    }

    fun onPlayerKill(
        entity: net.minecraft.server.level.ServerPlayer,
        killedEntity: net.minecraft.world.entity.Entity,
        damageSource: DamageSource
    ) {
        if (storageManager != null) {
            val luaPlayer = PlayerWrap.wrap(entity)
            val luaKilled = EntityFactory.wrap(killedEntity)
            runtime.eventManager.fire(
                "player_kill",
                luaPlayer,
                luaKilled,
                org.luaj.vm2.LuaValue.valueOf(damageSource.msgId.substringAfterLast("."))
            )
        }
    }

    fun registerCommand(dispatcher: com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack>) {
        dispatcher.register(
            net.minecraft.commands.Commands.literal("ignis")
                .requires { source ->
                    val player = source.getPlayer()
                    if (player != null) IgnisPlatform.instance.checkPermission(player.commandSource(), "px.ignis")
                    else true
                }
                .then(
                    net.minecraft.commands.Commands.literal("reload").executes { ctx ->
                        try {
                            runtime.reload()
                            ctx.source.sendSuccess({ net.minecraft.network.chat.Component.literal("Перезагрузилось") }, false)
                            return@executes 1
                        } catch (e: org.luaj.vm2.LuaError) {
                            logger.error("Ошибка при перезагрузке PxIgnis: ${e.message}")
                            ctx.source.sendSuccess(
                                { net.minecraft.network.chat.Component.literal("Ошибка при перезагрузке PxIgnis: ${e.message}") },
                                false
                            )
                        } catch (e: Throwable) {
                            logger.error("Ошибка при перезагрузке PxIgnis: ${e.message}", e)
                            ctx.source.sendSuccess(
                                { net.minecraft.network.chat.Component.literal("Ошибка при перезагрузке PxIgnis: ${e.message}") },
                                false
                            )
                        }
                        0
                    }
                )
        )
    }
}
