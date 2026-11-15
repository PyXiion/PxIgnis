package ru.pyxiion.ignis.events

import net.minecraft.world.entity.Entity
import net.minecraft.world.item.ItemStack

/**
 * The capture window of `entity.drops`: from the start of `dropAllDeathLoot` until vanilla has produced every
 * stack. Inside it `Entity.spawnAtLocation` of the dying entity puts the stack into a [DropBuffer] instead of
 * spawning it, `dropExperience` waits, and a player's inventory drop is held back. The server thread runs one death
 * at a time; a [ThreadLocal] keeps a worker thread's own deaths apart anyway.
 */
object DropCapture {
    private val current = ThreadLocal<DropBuffer<ItemStack>?>()

    @JvmStatic
    fun begin(owner: Entity): DropBuffer<ItemStack> {
        val buffer = DropBuffer<ItemStack>(owner, current.get())
        current.set(buffer)
        return buffer
    }

    @JvmStatic
    fun end(buffer: DropBuffer<ItemStack>) {
        if (buffer.previous == null) current.remove() else current.set(buffer.previous)
    }

    /** `spawnAtLocation` of [entity] with [stack]: true when the stack was taken and must not spawn. */
    @JvmStatic
    fun offer(entity: Entity, stack: ItemStack): Boolean {
        val buffer = current.get() ?: return false
        return !stack.isEmpty && buffer.offer(entity, stack)
    }

    /** Is [entity] in the middle of its capture window (XP must wait for the event)? */
    @JvmStatic
    fun active(entity: Any): Boolean = current.get()?.owner === entity

    /** A player's inventory drop asks to wait for the event: true when it must be skipped now and rerun later. */
    @JvmStatic
    fun deferEquipment(entity: Any): Boolean {
        val buffer = current.get() ?: return false
        if (buffer.owner !== entity) return false
        buffer.equipmentDeferred = true
        return true
    }
}
