package ru.pyxiion.ignis.events

import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import ru.pyxiion.ignis.EventBus
import ru.pyxiion.ignis.EventResult
import ru.pyxiion.ignis.PxIgnis
import ru.pyxiion.ignis.api.Vector
import ru.pyxiion.ignis.api.wrapper.EntityFactory
import ru.pyxiion.ignis.api.wrapper.ItemStackWrap
import ru.pyxiion.ignis.api.wrapper.PlayerWrap

/**
 * Turns Minecraft callbacks into [GlobalEvents]. Each function returns true when the action may go ahead
 * (for cancellable events) and does nothing before the scripts are loaded.
 */
object GameEvents {
    private val bus: EventBus?
        get() = PxIgnis.runtimeOrNull?.eventManager

    private fun post(event: String, fill: (LuaTable) -> Unit): EventResult = bus?.post(event, fill) ?: EventResult.NONE

    private fun allowed(event: String, fill: (LuaTable) -> Unit): Boolean = !post(event, fill).cancelled

    private operator fun LuaTable.set(key: String, value: LuaValue) = rawset(key, value)

    private fun damageType(source: DamageSource): LuaValue = LuaValue.valueOf(source.msgId.substringAfterLast("."))
    private fun hand(hand: InteractionHand): LuaValue = LuaValue.valueOf(if (hand == InteractionHand.MAIN_HAND) "main" else "off")
    private fun item(stack: ItemStack): LuaValue = if (stack.isEmpty) LuaValue.NIL else ItemStackWrap.wrap(stack)
    private fun pos(pos: BlockPos): LuaValue = Vector.of(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble()).toLuaValue()
    private fun vec(v: Vec3): LuaValue = Vector.fromMc(v).toLuaValue()

    /** Sets `e.entity`, plus `e.player` (the same object) when the entity is a player. */
    private fun LuaTable.entity(entity: Entity) {
        val w = EntityFactory.wrap(entity)
        this["entity"] = w
        if (entity is ServerPlayer) this["player"] = w
    }

    // --- player ---------------------------------------------------------------------------------------------

    /** `player.login`: returns the kick message, or null when the player may join. */
    fun login(player: ServerPlayer): String? {
        val r = post("player.login") { it["player"] = PlayerWrap.wrap(player) }
        return if (r.cancelled) r.reason ?: "You are not allowed to join this server" else null
    }

    fun join(player: ServerPlayer) = post("player.join") { it["player"] = PlayerWrap.wrap(player) }

    fun leave(player: ServerPlayer) = post("player.leave") { it["player"] = PlayerWrap.wrap(player) }

    fun respawn(player: ServerPlayer, alive: Boolean) = post("player.respawn") {
        it["player"] = PlayerWrap.wrap(player)
        it["alive"] = LuaValue.valueOf(alive)
    }

    fun chat(player: ServerPlayer, message: String) = allowed("player.chat") {
        it["player"] = PlayerWrap.wrap(player)
        it["message"] = LuaValue.valueOf(message)
    }

    fun move(player: ServerPlayer, from: Vec3, to: Vec3) = post("player.move") {
        it["player"] = PlayerWrap.wrap(player)
        it["from"] = vec(from)
        it["to"] = vec(to)
    }

    fun useItem(player: ServerPlayer, hand: InteractionHand) = allowed("player.use_item") {
        val stack = player.getItemInHand(hand)
        it["player"] = PlayerWrap.wrap(player)
        it["hand"] = hand(hand)
        it["item"] = item(stack)
        it["itemId"] = LuaValue.valueOf(BuiltInRegistries.ITEM.getKey(stack.item).toString())
    }

    fun attack(player: ServerPlayer, target: Entity) = allowed("player.attack") {
        it["player"] = PlayerWrap.wrap(player)
        it["target"] = EntityFactory.wrap(target)
    }

    fun interact(player: ServerPlayer, target: Entity, hand: InteractionHand) = allowed("player.interact") {
        it["player"] = PlayerWrap.wrap(player)
        it["target"] = EntityFactory.wrap(target)
        it["hand"] = hand(hand)
    }

    fun kill(player: ServerPlayer, target: Entity, source: DamageSource) = post("player.kill") {
        it["player"] = PlayerWrap.wrap(player)
        it["target"] = EntityFactory.wrap(target)
        it["source"] = damageType(source)
    }

    @JvmStatic
    fun consume(entity: LivingEntity, stack: ItemStack): Boolean {
        if (entity !is ServerPlayer) return true
        return allowed("player.consume") {
            it["player"] = PlayerWrap.wrap(entity)
            it["item"] = ItemStackWrap.wrap(stack)
        }
    }

    @JvmStatic
    fun pickup(player: ServerPlayer, stack: ItemStack) = allowed("player.pickup") {
        it["player"] = PlayerWrap.wrap(player)
        it["item"] = ItemStackWrap.wrap(stack)
        it["count"] = LuaValue.valueOf(stack.count)
    }

    @JvmStatic
    fun drop(player: ServerPlayer, stack: ItemStack, count: Int) = allowed("player.drop") {
        it["player"] = PlayerWrap.wrap(player)
        it["item"] = ItemStackWrap.wrap(stack)
        it["count"] = LuaValue.valueOf(count)
    }

    // --- blocks ---------------------------------------------------------------------------------------------

    fun breakBlock(player: ServerPlayer, pos: BlockPos, blockId: String) = allowed("block.break") {
        it["player"] = PlayerWrap.wrap(player)
        it["pos"] = pos(pos)
        it["block"] = LuaValue.valueOf(blockId)
    }

    @JvmStatic
    fun placeBlock(player: ServerPlayer, pos: BlockPos, blockId: String) = allowed("block.place") {
        it["player"] = PlayerWrap.wrap(player)
        it["pos"] = pos(pos)
        it["block"] = LuaValue.valueOf(blockId)
    }

    // --- entities -------------------------------------------------------------------------------------------

    fun spawn(entity: Entity) = post("entity.spawn") { it["entity"] = EntityFactory.wrap(entity) }

    fun despawn(entity: Entity) = post("entity.despawn") { it["entity"] = EntityFactory.wrap(entity) }

    fun hurt(entity: LivingEntity, source: DamageSource, amount: Float) = allowed("entity.hurt") {
        it.entity(entity)
        it["source"] = damageType(source)
        it["amount"] = LuaValue.valueOf(amount.toDouble())
        it["attacker"] = source.entity?.let(EntityFactory::wrap) ?: LuaValue.NIL
    }

    fun damaged(entity: LivingEntity, source: DamageSource, amount: Float, blocked: Boolean) = post("entity.damaged") {
        it.entity(entity)
        it["source"] = damageType(source)
        it["amount"] = LuaValue.valueOf(amount.toDouble())
        it["attacker"] = source.entity?.let(EntityFactory::wrap) ?: LuaValue.NIL
        it["blocked"] = LuaValue.valueOf(blocked)
    }

    fun death(entity: LivingEntity, source: DamageSource, amount: Float) = allowed("entity.death") {
        it.entity(entity)
        it["source"] = damageType(source)
        it["amount"] = LuaValue.valueOf(amount.toDouble())
        it["attacker"] = source.entity?.let(EntityFactory::wrap) ?: LuaValue.NIL
    }
}
