package ru.pyxiion.ignis.api.manager

import net.minecraft.world.entity.Display
import net.minecraft.world.entity.Entity
import ru.pyxiion.ignis.Compat
import ru.pyxiion.ignis.api.wrapper.HologramWrapper
import java.util.UUID

/**
 * Holograms are text displays that belong to the running script state. Each carries [TAG], so a text display
 * saved in a chunk by an earlier run is recognised when the chunk loads: a hologram this runtime still tracks is
 * re-bound to the freshly loaded entity, anything else is discarded.
 */
object HologramManager {
    const val TAG = "pxignis_hologram"

    private val holograms = mutableMapOf<UUID, HologramWrapper>()

    /** Tagged displays that no hologram claims. Discarded on the next tick, not inside ENTITY_LOAD. */
    private val orphans = ArrayDeque<Entity>()

    /** Registers the hologram. Call before the entity is added to the level: its ENTITY_LOAD must find it. */
    fun create(entity: Display.TextDisplay): HologramWrapper {
        entity.addTag(TAG)
        val wrapper = HologramWrapper(entity)
        holograms[entity.uuid] = wrapper
        return wrapper
    }

    fun get(uuid: UUID): HologramWrapper? = holograms[uuid]

    fun all(): List<HologramWrapper> = holograms.values.toList()

    fun onEntityLoad(entity: Entity) {
        if (entity !is Display.TextDisplay || TAG !in Compat.entityTags(entity)) return
        val wrapper = holograms[entity.uuid]
        if (wrapper != null) wrapper.rebind(entity) else orphans.addLast(entity)
    }

    /** Server tick: removes the orphans collected by [onEntityLoad]. */
    fun tick() {
        while (true) {
            val entity = orphans.removeFirstOrNull() ?: return
            if (!entity.isRemoved && holograms[entity.uuid] == null) entity.discard()
        }
    }

    fun closeAll() {
        holograms.values.toList().forEach { it.destroyInternals() }
        holograms.clear()
    }

    internal fun unregister(wrapper: HologramWrapper) {
        holograms.remove(wrapper.entity.uuid)
    }
}
