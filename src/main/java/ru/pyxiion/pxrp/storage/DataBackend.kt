package ru.pyxiion.pxrp.storage

interface DataBackend {
    fun load(key: String): Map<String, Any?>
    fun save(key: String, data: Map<String, Any?>)
    fun close()
}
