package ru.pyxiion.ignis.api.util

import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket
import net.minecraft.server.level.ServerPlayer
import ru.pyxiion.luanova.vm2.LuaError
import ru.pyxiion.luanova.vm2.LuaValue

/** Sends [text] to [players]: a chat message, or the action bar when [actionBar] is true. Title timings are not touched. */
fun broadcastTo(players: Collection<ServerPlayer>, text: String, actionBar: Boolean) {
    val component = Component.literal(text)
    if (actionBar) {
        val packet = ClientboundSetActionBarTextPacket(component)
        players.forEach { it.connection.send(packet) }
    } else {
        players.forEach { it.sendSystemMessage(component) }
    }
}

/** The `overlay` argument of `mc.broadcast` / `world:broadcastInRange`: nil or a boolean, nothing else. */
fun checkOverlay(arg: LuaValue, fn: String): Boolean = when {
    arg.isnil() -> false
    arg.isboolean() -> arg.toboolean()
    else -> throw LuaError("$fn: overlay должен быть boolean (true — action bar), получено ${arg.typename()}")
}
