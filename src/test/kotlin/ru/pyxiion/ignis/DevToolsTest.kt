package ru.pyxiion.ignis

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.luaj.vm2.LuaError
import ru.pyxiion.ignis.runtime.EditorSupport
import ru.pyxiion.ignis.runtime.ScriptErrors
import ru.pyxiion.ignis.runtime.ScriptWatcher

class DevToolsTest {
    private val tmp: Path = Files.createTempDirectory("pxignis-test")

    @AfterTest
    fun tearDown() {
        ScriptErrors.notifier = null
        ScriptErrors.clock = System::currentTimeMillis
        tmp.toFile().deleteRecursively()
    }

    @Test
    fun `repeated script errors reach chat once per window`() {
        val sent = mutableListOf<String>()
        var now = 0L
        ScriptErrors.resetRateLimit()
        ScriptErrors.clock = { now }
        ScriptErrors.notifier = { sent += it.text }

        repeat(5) { ScriptErrors.error("event 'tick'", LuaError("a.lua:1: boom")) }
        ScriptErrors.error("event 'tick'", LuaError("a.lua:2: other"))
        now += ScriptErrors.repeatWindowMillis
        ScriptErrors.error("event 'tick'", LuaError("a.lua:1: boom"))

        assertEquals(listOf("a.lua:1: boom", "a.lua:2: other", "a.lua:1: boom"), sent)
    }

    @Test
    fun `editor support unpacks types and keeps an edited luarc`() {
        val bundled = Files.createDirectories(tmp.resolve("bundled"))
        bundled.resolve("00-globals.lua").writeText("---@meta\n")
        val ignis = tmp.resolve("ignis")
        Files.createDirectories(ignis.resolve(".types"))
        ignis.resolve(".types/old.lua").writeText("stale")

        EditorSupport.install(ignis, bundled)
        assertTrue(Files.exists(ignis.resolve(".types/00-globals.lua")))
        assertFalse(Files.exists(ignis.resolve(".types/old.lua")))
        assertTrue(ignis.resolve(".luarc.json").readText().contains("\"workspace.library\": [\".types\"]"))

        ignis.resolve(".luarc.json").writeText("{}")
        EditorSupport.install(ignis, bundled)
        assertEquals("{}", ignis.resolve(".luarc.json").readText())
    }

    @Test
    fun `watcher reloads once per burst of lua edits and ignores other files`() {
        val calls = AtomicInteger()
        val first = CountDownLatch(1)
        Files.createDirectories(tmp.resolve("lib"))
        Files.createDirectories(tmp.resolve("storage"))
        val watcher = ScriptWatcher(tmp, debounceMillis = 200) {
            calls.incrementAndGet()
            first.countDown()
        }
        watcher.start()
        try {
            Thread.sleep(100)
            tmp.resolve("storage/data.lua").writeText("ignored")
            tmp.resolve("notes.txt").writeText("ignored")
            Thread.sleep(500)
            assertEquals(0, calls.get())

            repeat(3) {
                tmp.resolve("main.lua").writeText("print($it)")
                tmp.resolve("lib/util.lua").writeText("return $it")
                Thread.sleep(20)
            }
            assertTrue(first.await(5, TimeUnit.SECONDS))
            Thread.sleep(500)
            assertEquals(1, calls.get())
        } finally {
            watcher.stop()
        }
        assertFalse(watcher.running)
    }
}
