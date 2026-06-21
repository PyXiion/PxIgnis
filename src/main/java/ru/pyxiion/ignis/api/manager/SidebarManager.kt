package ru.pyxiion.ignis.api.manager

import net.minecraft.server.network.ServerPlayerEntity
import ru.pyxiion.ignis.api.wrapper.SidebarWrapper

object SidebarManager {
    private val sidebars = mutableMapOf<ServerPlayerEntity, SidebarWrapper>()

    fun create(player: ServerPlayerEntity, title: String): SidebarWrapper {
        removeForPlayer(player)
        val wrapper = SidebarWrapper(player, title)
        sidebars[player] = wrapper
        return wrapper
    }

    fun get(player: ServerPlayerEntity): SidebarWrapper? = sidebars[player]

    fun removeForPlayer(player: ServerPlayerEntity) {
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
