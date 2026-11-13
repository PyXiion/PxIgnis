package ru.pyxiion.ignis.mapcanvas

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.minecraft.server.MinecraftServer
import ru.pyxiion.ignis.plugin.PluginEnv
import java.util.concurrent.atomic.AtomicInteger

/** Everything allocated for one Lua environment. Released by [close] on reload and server stop. */
class MapCanvasEnv(val env: PluginEnv) {
    val server: MinecraftServer get() = env.server
    val canvases = LinkedHashSet<Canvas>()
    val walls = LinkedHashSet<Wall>()
    private var closed = false
    private var ticks = 0

    private val ticker: Job = env.scope.launch {
        while (isActive) {
            delay(TICK_MILLIS)
            env.runOnServer { if (!closed) tick() }
        }
    }

    fun newCanvas(): Canvas = Canvas(this, nextId.getAndIncrement()).also { canvases.add(it) }

    fun free(c: Canvas) {
        c.free()
        canvases.remove(c)
    }

    private fun tick() {
        if (ticks++ % WALL_PERIOD == 0) for (w in walls.toList()) guarded("mapcanvas wall") { w.tick() }
        for (c in canvases.toList()) guarded("mapcanvas flush") { c.flush() }
    }

    private inline fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            env.errors.error(what, t)
        }
    }

    fun close() {
        closed = true
        ticker.cancel()
        walls.toList().forEach { it.remove() }
        canvases.toList().forEach { it.free() }
        canvases.clear()
    }

    private companion object {
        const val TICK_MILLIS = 50L
        const val WALL_PERIOD = 10

        /** Never reset across reloads: a client may still hold a map under an old id. */
        val nextId = AtomicInteger(1_000_000_000)
    }
}
