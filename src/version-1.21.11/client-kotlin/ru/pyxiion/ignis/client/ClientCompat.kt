package ru.pyxiion.ignis.client

object ClientCompat {
    fun drawWireframeBoxes(boxes: Collection<Any>) {
        throw UnsupportedOperationException(
            "Client-side region wireframe rendering is not yet implemented."
        )
    }
}
