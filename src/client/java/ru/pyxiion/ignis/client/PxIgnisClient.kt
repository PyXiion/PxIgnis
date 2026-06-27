package ru.pyxiion.ignis.client

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents
import net.minecraft.text.Text
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import ru.pyxiion.ignis.network.RegionCapWarningPayload
import ru.pyxiion.ignis.network.RegionRemovePayload
import ru.pyxiion.ignis.network.RegionSyncPayload
import ru.pyxiion.ignis.network.RegionUpsertPayload

class PxIgnisClient : ClientModInitializer {
    companion object {
        private val logger: Logger = LoggerFactory.getLogger("pxignis-client")
    }

    override fun onInitializeClient() {
        logger.info("PxIgnis client loaded")

        ClientPlayNetworking.registerGlobalReceiver(RegionSyncPayload.ID) { payload, _ ->
            ClientRegionRegistry.replaceAll(payload.entries)
        }
        ClientPlayNetworking.registerGlobalReceiver(RegionUpsertPayload.ID) { payload, _ ->
            ClientRegionRegistry.upsert(payload.id, payload.region)
        }
        ClientPlayNetworking.registerGlobalReceiver(RegionRemovePayload.ID) { payload, _ ->
            ClientRegionRegistry.remove(payload.id)
        }
        ClientPlayNetworking.registerGlobalReceiver(RegionCapWarningPayload.ID) { payload, ctx ->
            val player = ctx.client().player ?: return@registerGlobalReceiver
            player.sendMessage(
                Text.literal("Region cap (${payload.cap}) reached; some regions are not rendered."),
                false
            )
        }

        WorldRenderEvents.BEFORE_DEBUG_RENDER.register { _ ->
            if (!ClientRegionRegistry.enabled) return@register
            runCatching {
                ClientCompat.drawWireframeBoxes(ClientRegionRegistry.values())
            }.onFailure { logger.warn("region wireframe render failed: ${it.message}") }
        }

        ClientCommands.register()
    }
}
