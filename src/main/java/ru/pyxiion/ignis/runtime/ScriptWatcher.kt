package ru.pyxiion.ignis.runtime

import org.slf4j.LoggerFactory
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * Watches the scripts folder and calls [onChange] once edits to `.lua` files settle for [debounceMillis]
 * (editors often write a file in several steps). Runs on its own daemon thread; [onChange] is called there,
 * so it must hop to the server thread itself.
 *
 * Folders skipped: hidden ones (`.types` with the editor stubs) and [ignored] (`storage`, written by scripts).
 */
class ScriptWatcher(
    private val root: Path,
    private val debounceMillis: Long = 500,
    private val ignored: Set<String> = setOf("storage"),
    private val onChange: () -> Unit,
) {
    private val logger = LoggerFactory.getLogger("pxignis")
    private var service: WatchService? = null
    private var thread: Thread? = null
    private val dirs = ConcurrentHashMap<WatchKey, Path>()

    val running: Boolean get() = thread?.isAlive == true

    @Synchronized
    fun start() {
        if (running) return
        val ws = FileSystems.getDefault().newWatchService()
        service = ws
        registerTree(ws, root)
        thread = Thread({ loop(ws) }, "PxIgnis-script-watcher").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stop() {
        service?.close()
        service = null
        thread?.join(1000)
        thread = null
        dirs.clear()
    }

    private fun skip(dir: Path): Boolean = dir != root && (dir.name.startsWith(".") || dir.name in ignored)

    private fun registerTree(ws: WatchService, dir: Path) {
        if (!dir.isDirectory() || skip(dir)) return
        dirs[dir.register(ws, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)] = dir
        Files.list(dir).use { children -> children.filter { it.isDirectory() }.forEach { registerTree(ws, it) } }
    }

    private fun loop(ws: WatchService) {
        var pendingSince = 0L
        try {
            while (true) {
                val key = if (pendingSince == 0L) ws.take() else ws.poll(debounceMillis, TimeUnit.MILLISECONDS)
                if (key != null) {
                    val dir = dirs[key]
                    for (event in key.pollEvents()) {
                        val path = dir?.resolve(event.context() as? Path ?: continue) ?: continue
                        if (event.kind() == ENTRY_CREATE && path.isDirectory()) registerTree(ws, path)
                        if (path.name.endsWith(".lua")) pendingSince = System.currentTimeMillis()
                    }
                    if (!key.reset()) dirs.remove(key)
                }
                if (pendingSince != 0L && System.currentTimeMillis() - pendingSince >= debounceMillis) {
                    pendingSince = 0L
                    try {
                        onChange()
                    } catch (t: Throwable) {
                        logger.error("Script watcher callback failed", t)
                    }
                }
            }
        } catch (_: ClosedWatchServiceException) {
        } catch (_: InterruptedException) {
        }
    }
}
