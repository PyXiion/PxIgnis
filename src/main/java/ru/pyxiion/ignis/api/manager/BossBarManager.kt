package ru.pyxiion.ignis.api.manager

import net.minecraft.server.level.ServerPlayer
import ru.pyxiion.ignis.api.wrapper.BossBarWrapper

object BossBarManager {
    private val bars = mutableSetOf<BossBarWrapper>()

    fun register(wrapper: BossBarWrapper) {
        bars.add(wrapper)
    }

    fun unregister(wrapper: BossBarWrapper) {
        bars.remove(wrapper)
    }

    /** A boss event keeps its players in a set; a disconnected player must not stay in it. */
    fun onPlayerLeft(player: ServerPlayer) {
        bars.forEach { it.removePlayer(player) }
    }

    fun closeAll() {
        bars.toList().forEach { it.destroyInternals() }
        bars.clear()
    }
}
