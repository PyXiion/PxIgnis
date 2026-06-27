package ru.pyxiion.ignis.api.manager

import net.minecraft.server.level.ServerPlayer
import ru.pyxiion.ignis.api.wrapper.SidebarWrapper

object SidebarManager {
    private val sidebars = mutableMapOf<ServerPlayer, SidebarWrapper>()

    fun create(player: ServerPlayer, title: String): SidebarWrapper {
        removeForPlayer(player)
        val wrapper = SidebarWrapper(player, title)
        sidebars[player] = wrapper
        return wrapper
    }

    fun get(player: ServerPlayer): SidebarWrapper? = sidebars[player]

    fun removeForPlayer(player: ServerPlayer) {
        sidebars.remove(player)?.destroyInternals()
    }

    fun closeAll() {
        sidebars.values.toList().forEach { it.destroyInternals() }
        sidebars.clear()
    }

    internal fun unregister(wrapper: SidebarWrapper) {
        sidebars.remove(wrapper.player)
    }
}
