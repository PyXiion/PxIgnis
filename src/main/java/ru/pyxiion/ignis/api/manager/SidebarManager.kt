package ru.pyxiion.ignis.api.manager

import net.minecraft.server.level.ServerPlayer
import ru.pyxiion.ignis.api.wrapper.SidebarWrapper
import java.util.UUID

/** Keyed by player UUID: a respawn creates a new [ServerPlayer] object for the same player. */
object SidebarManager {
    private val sidebars = mutableMapOf<UUID, SidebarWrapper>()

    fun create(player: ServerPlayer, title: String): SidebarWrapper {
        removeForPlayer(player)
        val wrapper = SidebarWrapper(player, title)
        sidebars[player.uuid] = wrapper
        return wrapper
    }

    fun get(player: ServerPlayer): SidebarWrapper? = sidebars[player.uuid]

    fun removeForPlayer(player: ServerPlayer) {
        sidebars.remove(player.uuid)?.destroyInternals()
    }

    fun closeAll() {
        sidebars.values.toList().forEach { it.destroyInternals() }
        sidebars.clear()
    }

    internal fun unregister(wrapper: SidebarWrapper) {
        sidebars.remove(wrapper.player.uuid)
    }
}
