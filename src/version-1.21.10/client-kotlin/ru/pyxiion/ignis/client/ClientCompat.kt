package ru.pyxiion.ignis.client

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext
import net.minecraft.util.math.Box

object ClientCompat {
    fun drawWireframeBoxes(context: WorldRenderContext, boxes: Collection<Box>) {
        throw UnsupportedOperationException(
            "Client-side region wireframe rendering is not yet implemented. " +
                "Use the server-installed mod on 1.21.11 for client-side visualization."
        )
    }
}
