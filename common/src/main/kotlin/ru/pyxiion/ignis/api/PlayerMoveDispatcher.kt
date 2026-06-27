package ru.pyxiion.ignis.api

import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.Vec3

object PlayerMoveDispatcher {
    var handler: ((ServerPlayer, Vec3, Vec3) -> Unit)? = null

    fun onPlayerMoved(player: ServerPlayer, from: Vec3, to: Vec3) {
        handler?.invoke(player, from, to)
    }
}
