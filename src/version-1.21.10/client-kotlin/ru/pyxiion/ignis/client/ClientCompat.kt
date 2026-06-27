package ru.pyxiion.ignis.client

object ClientCompat {
    fun drawWireframeBoxes(boxes: Collection<Any>) {
        throw UnsupportedOperationException(
            "Client-side region wireframe rendering is not yet implemented. " +
                "Use the server-installed mod on 1.21.11 for client-side visualization."
        )
    }
}
