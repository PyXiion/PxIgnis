package ru.pyxiion.ignis.api.manager

import ru.pyxiion.ignis.api.wrapper.BossBarWrapper

object BossBarManager {
    private val bars = mutableSetOf<BossBarWrapper>()

    fun register(wrapper: BossBarWrapper) {
        bars.add(wrapper)
    }

    fun unregister(wrapper: BossBarWrapper) {
        bars.remove(wrapper)
    }

    fun closeAll() {
        bars.toList().forEach { it.destroyInternals() }
        bars.clear()
    }
}
