package ru.pyxiion.ignis.client

import net.fabricmc.api.ClientModInitializer
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class PxIgnisClient : ClientModInitializer {
    companion object {
        private val logger: Logger = LoggerFactory.getLogger("pxignis-client")
    }

    override fun onInitializeClient() {
        logger.info("PxIgnis client loaded")
    }
}
