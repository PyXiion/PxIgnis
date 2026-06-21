package ru.pyxiion.ignis.api.manager

import net.minecraft.entity.decoration.DisplayEntity
import net.minecraft.server.world.ServerWorld
import ru.pyxiion.ignis.api.wrapper.HologramWrapper
import java.util.UUID

object HologramManager {
    private val holograms = mutableMapOf<UUID, HologramWrapper>()

    fun create(entity: DisplayEntity.TextDisplayEntity, world: ServerWorld): HologramWrapper {
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
