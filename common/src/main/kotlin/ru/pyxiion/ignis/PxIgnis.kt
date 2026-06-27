package ru.pyxiion.ignis

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import ru.pyxiion.ignis.storage.StorageManager

class PxIgnis {
    companion object {
        const val MOD_ID = "pxignis"

        @JvmField
        val logger: Logger = LoggerFactory.getLogger(MOD_ID)

        @JvmField
        var instance: PxIgnis? = null
        var storageManager: StorageManager? = null
    }
    lateinit var runtime: IgnisRuntime
}
