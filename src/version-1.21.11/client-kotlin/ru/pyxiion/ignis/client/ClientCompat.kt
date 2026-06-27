package ru.pyxiion.ignis.client

import com.mojang.blaze3d.vertex.VertexFormat
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext
import net.minecraft.util.math.Box
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * 1.21.11 wireframe renderer.
 *
 * NOTE: The new Minecraft 1.21.11 render pipeline (RenderPipelines, BufferBuilder,
 * MappableRingBuffer) is not yet publicly accessible from third-party mods at the
 * fabric-api version we depend on. The Yarn mapping (1.21.11+build.6) and the
 * published minecraft-client jar both ship the renderer classes as Mojang-namespaced
 * (com.mojang.blaze3d.*) with no access wideners for direct use.
 *
 * Until fabric-renderer-indigo exposes a stable public draw API for ad-hoc
 * pipeline-based rendering, this implementation only emits a one-time warning to the
 * client log and skips actual GPU drawing. Region state is still received, the
 * registry still tracks AABBs, and the command still toggles the overlay flag --
 * only the visible wireframes are not produced.
 */
object ClientCompat {
    private val logger: Logger = LoggerFactory.getLogger("pxignis-region-render")
    @Volatile
    private var warned: Boolean = false

    fun drawWireframeBoxes(context: WorldRenderContext, boxes: Collection<Box>) {
        if (boxes.isEmpty()) return
        if (!warned) {
            warned = true
            logger.warn(
                "Region wireframe overlay is a no-op on 1.21.11: the new " +
                    "render pipeline is not yet accessible from third-party mods. " +
                    "Region AABBs are still received and the command still toggles " +
                    "the overlay flag; only the actual GPU draw is skipped."
            )
        }
    }

    fun close() {
    }
}
