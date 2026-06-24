package ru.pyxiion.ignis.api

import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.util.math.Vec3d

object PlayerMoveDispatcher {
    var handler: ((ServerPlayerEntity, Vec3d, Vec3d) -> Unit)? = null

    fun onPlayerMoved(player: ServerPlayerEntity, from: Vec3d, to: Vec3d) {
        handler?.invoke(player, from, to)
    }
}
