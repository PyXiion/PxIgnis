package ru.pyxiion.ignis.events

import net.minecraft.entity.Entity
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.damage.DamageSource
import net.minecraft.item.ItemStack
import net.minecraft.registry.Registries
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.util.Hand
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
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
        get() = if (PxIgnis.instance.hasRuntime()) PxIgnis.instance.runtime.eventManager else null

    private fun post(event: String, fill: (LuaTable) -> Unit): EventResult = bus?.post(event, fill) ?: EventResult.NONE

    private fun allowed(event: String, fill: (LuaTable) -> Unit): Boolean = !post(event, fill).cancelled

    private operator fun LuaTable.set(key: String, value: LuaValue) = rawset(key, value)

    private fun damageType(source: DamageSource): LuaValue = LuaValue.valueOf(source.name.substringAfterLast("."))
    private fun hand(hand: Hand): LuaValue = LuaValue.valueOf(if (hand == Hand.MAIN_HAND) "main" else "off")
    private fun item(stack: ItemStack): LuaValue = if (stack.isEmpty) LuaValue.NIL else ItemStackWrap.wrap(stack)
    private fun pos(pos: BlockPos): LuaValue = Vector.of(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble()).toLuaValue()
    private fun vec(v: Vec3d): LuaValue = Vector.fromMc(v).toLuaValue()

    /** Sets `e.entity`, plus `e.player` (the same object) when the entity is a player. */
    private fun LuaTable.entity(entity: Entity) {
        val w = EntityFactory.wrap(entity)
        this["entity"] = w
        if (entity is ServerPlayerEntity) this["player"] = w
    }

    // --- player ---------------------------------------------------------------------------------------------

    /** `player.login`: returns the kick message, or null when the player may join. */
    fun login(player: ServerPlayerEntity): String? {
        val r = post("player.login") { it["player"] = PlayerWrap.wrap(player) }
        return if (r.cancelled) r.reason ?: "You are not allowed to join this server" else null
    }

    fun join(player: ServerPlayerEntity) = post("player.join") { it["player"] = PlayerWrap.wrap(player) }

    fun leave(player: ServerPlayerEntity) = post("player.leave") { it["player"] = PlayerWrap.wrap(player) }

    fun respawn(player: ServerPlayerEntity, alive: Boolean) = post("player.respawn") {
        it["player"] = PlayerWrap.wrap(player)
        it["alive"] = LuaValue.valueOf(alive)
    }

    fun chat(player: ServerPlayerEntity, message: String) = allowed("player.chat") {
        it["player"] = PlayerWrap.wrap(player)
        it["message"] = LuaValue.valueOf(message)
    }

    fun move(player: ServerPlayerEntity, from: Vec3d, to: Vec3d) = post("player.move") {
        it["player"] = PlayerWrap.wrap(player)
        it["from"] = vec(from)
        it["to"] = vec(to)
    }

    fun useItem(player: ServerPlayerEntity, hand: Hand) = allowed("player.use_item") {
        val stack = player.getStackInHand(hand)
        it["player"] = PlayerWrap.wrap(player)
        it["hand"] = hand(hand)
        it["item"] = item(stack)
        it["itemId"] = LuaValue.valueOf(Registries.ITEM.getId(stack.item).toString())
    }

    fun attack(player: ServerPlayerEntity, target: Entity) = allowed("player.attack") {
        it["player"] = PlayerWrap.wrap(player)
        it["target"] = EntityFactory.wrap(target)
    }

    fun interact(player: ServerPlayerEntity, target: Entity, hand: Hand) = allowed("player.interact") {
        it["player"] = PlayerWrap.wrap(player)
        it["target"] = EntityFactory.wrap(target)
        it["hand"] = hand(hand)
    }

    fun kill(player: ServerPlayerEntity, target: Entity, source: DamageSource) = post("player.kill") {
        it["player"] = PlayerWrap.wrap(player)
        it["target"] = EntityFactory.wrap(target)
        it["source"] = damageType(source)
    }

    @JvmStatic
    fun consume(entity: LivingEntity, stack: ItemStack): Boolean {
        if (entity !is ServerPlayerEntity) return true
        return allowed("player.consume") {
            it["player"] = PlayerWrap.wrap(entity)
            it["item"] = ItemStackWrap.wrap(stack)
        }
    }

    @JvmStatic
    fun pickup(player: ServerPlayerEntity, stack: ItemStack) = allowed("player.pickup") {
        it["player"] = PlayerWrap.wrap(player)
        it["item"] = ItemStackWrap.wrap(stack)
        it["count"] = LuaValue.valueOf(stack.count)
    }

    @JvmStatic
    fun drop(player: ServerPlayerEntity, stack: ItemStack, count: Int) = allowed("player.drop") {
        it["player"] = PlayerWrap.wrap(player)
        it["item"] = ItemStackWrap.wrap(stack)
        it["count"] = LuaValue.valueOf(count)
    }

    // --- blocks ---------------------------------------------------------------------------------------------

    fun breakBlock(player: ServerPlayerEntity, pos: BlockPos, blockId: String) = allowed("block.break") {
        it["player"] = PlayerWrap.wrap(player)
        it["pos"] = pos(pos)
        it["block"] = LuaValue.valueOf(blockId)
    }

    fun placeBlock(player: ServerPlayerEntity, pos: BlockPos, blockId: String) = allowed("block.place") {
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
        it["attacker"] = source.attacker?.let(EntityFactory::wrap) ?: LuaValue.NIL
    }

    fun damaged(entity: LivingEntity, source: DamageSource, amount: Float, blocked: Boolean) = post("entity.damaged") {
        it.entity(entity)
        it["source"] = damageType(source)
        it["amount"] = LuaValue.valueOf(amount.toDouble())
        it["attacker"] = source.attacker?.let(EntityFactory::wrap) ?: LuaValue.NIL
        it["blocked"] = LuaValue.valueOf(blocked)
    }

    fun death(entity: LivingEntity, source: DamageSource, amount: Float) = allowed("entity.death") {
        it.entity(entity)
        it["source"] = damageType(source)
        it["amount"] = LuaValue.valueOf(amount.toDouble())
        it["attacker"] = source.attacker?.let(EntityFactory::wrap) ?: LuaValue.NIL
    }
}
