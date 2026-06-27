package ru.pyxiion.ignis.api.manager

import net.minecraft.world.entity.Display
import net.minecraft.server.level.ServerLevel
import ru.pyxiion.ignis.api.wrapper.HologramWrapper
import java.util.UUID

object HologramManager {
    private val holograms = mutableMapOf<UUID, HologramWrapper>()

    fun create(entity: Display.TextDisplay, world: ServerLevel): HologramWrapper {
        val wrapper = HologramWrapper(entity, world)
        holograms[entity.uuid] = wrapper
        return wrapper
    }

    fun get(uuid: UUID): HologramWrapper? = holograms[uuid]

    fun all(): List<HologramWrapper> = holograms.values.toList()

    fun remove(uuid: UUID) {
        holograms.remove(uuid)?.destroyInternals()
    }

    fun closeAll() {
        holograms.values.toList().forEach { it.destroyInternals() }
        holograms.clear()
    }

    internal fun unregister(wrapper: HologramWrapper) {
        holograms.remove(wrapper.entity.uuid)
    }
}
