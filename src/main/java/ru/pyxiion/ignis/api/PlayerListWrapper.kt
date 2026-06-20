package ru.pyxiion.ignis.api

import net.minecraft.server.network.ServerPlayerEntity
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import java.util.UUID

class PlayerListWrapper(
    private val source: () -> List<ServerPlayerEntity>,
    private val playerCache: MutableMap<UUID, LuaValue>,
    private val tickProvider: () -> Long,
) {
    private var cachedTick: Long = -1
    private var cached: LuaTable? = null

    fun toLuaValue(): LuaValue {
        val currentTick = tickProvider()
        val cached = this.cached
        if (cached != null && this.cachedTick == currentTick) return cached

        val fresh = LuaTable()
        source().forEachIndexed { i, p ->
            fresh.set(i + 1, playerCache.getOrPut(p.uuid) { PlayerWrap.wrap(p) })
        }
        this.cached = fresh
        this.cachedTick = currentTick
        return fresh
    }
}
