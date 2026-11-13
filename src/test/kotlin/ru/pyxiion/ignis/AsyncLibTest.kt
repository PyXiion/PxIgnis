package ru.pyxiion.ignis

import com.sun.net.httpserver.HttpServer
import ru.pyxiion.luanova.vm2.LuaError
import ru.pyxiion.luanova.vm2.LuaState
import ru.pyxiion.luanova.vm2.LuaTable
import ru.pyxiion.luanova.vm2.LuaThread
import ru.pyxiion.luanova.vm2.LuaValue
import ru.pyxiion.luanova.vm2.Varargs
import ru.pyxiion.luanova.vm2.lib.jse.JsePlatform
import ru.pyxiion.ignis.parallel.StdlibRegistry
import ru.pyxiion.ignis.sandbox.Vfs
import java.nio.file.Files
import ru.pyxiion.ignis.api.AsyncLib
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Lua runs on one thread. The "server thread" here is the test thread: [Env.pump] is one server tick, it drains
 * the async queue and ticks the scheduler.
 */
class AsyncLibTest {

    private class Env {
        val state: LuaState = JsePlatform.standardState()
        val queue = ConcurrentLinkedQueue<Runnable>()
        val scheduler = Scheduler { state }
        val asyncLib = AsyncLib({ state }, scheduler, Vfs(listOf(Files.createTempDirectory("ignis"))))
        val failures = mutableListOf<String>()

        init {
            state.globals.set("async", asyncLib.buildModule(StdlibRegistry(state.globals)))
            state.globals.set("_result", LuaValue.NIL)
            LuaState.setCurrent(state)
        }

        fun resumeRoot(co: LuaThread, args: Varargs) {
            val r = co.resume(args)
            if (!r.arg1().toboolean()) failures.add(r.arg(2).optjstring("coroutine error"))
        }

        /** Runs queued work and scheduler ticks until [until] holds or [maxRounds] pass. */
        fun pump(maxRounds: Int = 200, until: () -> Boolean = { false }) {
            repeat(maxRounds) {
                if (until()) return
                asyncLib.drain()
                while (true) (queue.poll() ?: break).run()
                scheduler.tick()
            }
        }
    }

    private fun root(env: Env, script: String): LuaThread {
        val co = LuaThread(env.state, env.state.load(script, "test").checkfunction())
        co.resumeHandler = LuaThread.ResumeHandler { _, value -> env.queue.add { env.resumeRoot(co, value) } }
        env.resumeRoot(co, LuaValue.NONE)
        return co
    }

    /** Runs [script] as a coroutine to completion and returns the global `_result` table. */
    private fun run(script: String): LuaTable {
        val env = Env()
        val co = root(env, script)
        env.pump(until = { co.status == "dead" })
        assertTrue(env.failures.isEmpty(), "coroutine failed: ${env.failures}")
        assertEquals("dead", co.status, "coroutine did not complete")
        return env.state.globals.get("_result").checktable()
    }

    private fun runExpectingFailure(script: String): String {
        val env = Env()
        val co = root(env, script)
        env.pump(until = { co.status == "dead" })
        return env.failures.single()
    }

    // ── tasks ──────────────────────────────────────────────────────

    @Test
    fun `task is pending until the server runs it`() {
        val r = run(
            """
            local t = async.task(function() return 42 end)
            _result = { t.done, t.state, t.type }
            """.trimIndent()
        )
        assertFalse(r.get(1).toboolean())
        assertEquals("pending", r.get(2).tojstring())
        assertEquals("task", r.get(3).tojstring())
    }

    @Test
    fun `task wait returns the result`() {
        val r = run(
            """
            local t = async.task(function(a, b) return a + b end, 40, 2)
            _result = { t:wait(), t.done }
            """.trimIndent()
        )
        assertEquals(42, r.get(1).toint())
        assertTrue(r.get(2).toboolean())
    }

    @Test
    fun `task wait returns every value`() {
        val r = run(
            """
            local t = async.task(function() return 1, 2, 3 end)
            _result = { t:wait() }
            """.trimIndent()
        )
        assertEquals(3, r.get(3).toint())
    }

    @Test
    fun `task wait raises the task error`() {
        val r = run(
            """
            local t = async.task(function() error("boom") end)
            local ok, err = pcall(t.wait, t)
            _result = { ok, err }
            """.trimIndent()
        )
        assertFalse(r.get(1).toboolean())
        assertTrue(r.get(2).tojstring().contains("boom"))
    }

    @Test
    fun `wait with protect returns ok and result`() {
        val r = run(
            """
            local t = async.task(function() return 7 end)
            local ok, v = t:wait(true)
            _result = { ok, v }
            """.trimIndent()
        )
        assertTrue(r.get(1).toboolean())
        assertEquals(7, r.get(2).toint())
    }

    @Test
    fun `wait with protect returns false and message on error`() {
        val r = run(
            """
            local t = async.task(function() error("bad") end)
            local ok, err = t:wait(true)
            _result = { ok, err }
            """.trimIndent()
        )
        assertFalse(r.get(1).toboolean())
        assertTrue(r.get(2).tojstring().contains("bad"))
    }

    @Test
    fun `wait on a finished task does not yield`() {
        val r = run(
            """
            local t = async.task(function() return 5 end)
            t:wait()
            _result = { t:wait(), select('#', t:wait(true)) }
            """.trimIndent()
        )
        assertEquals(5, r.get(1).toint())
        assertEquals(2, r.get(2).toint())
    }

    @Test
    fun `task rejects a non function`() {
        val msg = runExpectingFailure("async.task(42)")
        assertTrue(msg.contains("expected function"), msg)
    }

    @Test
    fun `wait rejects a non task self`() {
        val r = run(
            """
            local t = async.task(function() end)
            local ok, err = pcall(t.wait, 42)
            _result = { ok, err }
            """.trimIndent()
        )
        assertFalse(r.get(1).toboolean())
        assertTrue(r.get(2).tojstring().contains("expected task or promise"))
    }

    @Test
    fun `try is gone`() {
        val r = run(
            """
            local t = async.task(function() end)
            _result = { t.try == nil, async.allSettled == nil, async.mutex == nil }
            """.trimIndent()
        )
        assertTrue(r.get(1).toboolean())
        assertTrue(r.get(2).toboolean())
        assertTrue(r.get(3).toboolean())
    }

    // ── promises ───────────────────────────────────────────────────

    @Test
    fun `promise resolve and wait`() {
        val r = run(
            """
            local p = async.promise()
            async.task(function() p:resolve("hi") end)
            _result = { p:wait() }
            """.trimIndent()
        )
        assertEquals("hi", r.get(1).tojstring())
    }

    @Test
    fun `promise error is raised by wait and returned by protected wait`() {
        val r = run(
            """
            local p = async.promise()
            async.task(function() p:error("nope") end)
            local ok, err = p:wait(true)
            _result = { ok, err, p.state }
            """.trimIndent()
        )
        assertFalse(r.get(1).toboolean())
        assertTrue(r.get(2).tojstring().contains("nope"))
        assertEquals("rejected", r.get(3).tojstring())
    }

    @Test
    fun `promise settles once`() {
        val r = run(
            """
            local p = async.promise()
            local a = p:resolve(1)
            local b = p:resolve(2)
            local c = p:error("x")
            _result = { a, b, c, p:wait(), p.done }
            """.trimIndent()
        )
        assertTrue(r.get(1).toboolean())
        assertFalse(r.get(2).toboolean())
        assertFalse(r.get(3).toboolean())
        assertEquals(1, r.get(4).toint())
        assertTrue(r.get(5).toboolean())
    }

    @Test
    fun `promise state transitions`() {
        val r = run(
            """
            local p = async.promise()
            local before = p.state
            p:resolve()
            _result = { before, p.state }
            """.trimIndent()
        )
        assertEquals("pending", r.get(1).tojstring())
        assertEquals("resolved", r.get(2).tojstring())
    }

    // ── all ────────────────────────────────────────────────────────

    @Test
    fun `all returns an entry per task`() {
        val r = run(
            """
            local res = async.all({
                async.task(function() return "a" end),
                async.task(function() async.sleep(2) return "b" end),
            })
            _result = { res[1].ok, res[1].value, res[2].ok, res[2].value }
            """.trimIndent()
        )
        assertTrue(r.get(1).toboolean())
        assertEquals("a", r.get(2).tojstring())
        assertEquals("b", r.get(4).tojstring())
    }

    @Test
    fun `all raises when a task fails`() {
        val r = run(
            """
            local ok, err = pcall(async.all, {
                async.task(function() return 1 end),
                async.task(function() error("kaput") end),
            })
            _result = { ok, err }
            """.trimIndent()
        )
        assertFalse(r.get(1).toboolean())
        assertTrue(r.get(2).tojstring().contains("kaput"))
    }

    @Test
    fun `all with settled does not raise`() {
        val r = run(
            """
            local res = async.all({
                async.task(function() return 1 end),
                async.task(function() error("kaput") end),
            }, { settled = true })
            _result = { res[1].ok, res[2].ok, res[2].error }
            """.trimIndent()
        )
        assertTrue(r.get(1).toboolean())
        assertFalse(r.get(2).toboolean())
        assertTrue(r.get(3).tojstring().contains("kaput"))
    }

    @Test
    fun `all accepts tasks as arguments`() {
        val r = run(
            """
            local res = async.all(async.task(function() return 1 end), async.task(function() return 2 end))
            _result = { res[1].value, res[2].value }
            """.trimIndent()
        )
        assertEquals(2, r.get(2).toint())
    }

    @Test
    fun `all with empty input returns an empty table`() {
        val r = run(
            """
            local res = async.all({})
            _result = { #res }
            """.trimIndent()
        )
        assertEquals(0, r.get(1).toint())
    }

    // ── run / sleep ────────────────────────────────────────────────

    @Test
    fun `run returns the result`() {
        val r = run(
            """
            _result = { async.run(function(x) async.sleep(1) return x * 2 end, 21) }
            """.trimIndent()
        )
        assertEquals(42, r.get(1).toint())
    }

    @Test
    fun `run raises the error`() {
        val r = run(
            """
            local ok, err = pcall(async.run, function() error("oops") end)
            _result = { ok, err }
            """.trimIndent()
        )
        assertFalse(r.get(1).toboolean())
        assertTrue(r.get(2).tojstring().contains("oops"))
    }

    @Test
    fun `sleep resumes after the requested ticks`() {
        val env = Env()
        val co = root(env, "async.sleep(5) _result = { 'done' }")
        env.pump(maxRounds = 4)
        assertEquals("suspended", co.status)
        env.pump(maxRounds = 4, until = { co.status == "dead" })
        assertEquals("dead", co.status)
    }

    @Test
    fun `sleep rejects negative ticks`() {
        val msg = runExpectingFailure("async.sleep(-1)")
        assertTrue(msg.contains("non-negative"), msg)
    }

    // ── fetch ──────────────────────────────────────────────────────

    @Test
    fun `fetch rejects a url without scheme`() {
        val msg = runExpectingFailure("async.fetch('example.com/x')")
        assertTrue(msg.contains("scheme"), msg)
    }

    @Test
    fun `fetch rejects a non positive timeout`() {
        val msg = runExpectingFailure("async.fetch({ url = 'http://127.0.0.1:1/', timeout = 0 })")
        assertTrue(msg.contains("timeout"), msg)
    }

    // ── reload ─────────────────────────────────────────────────────

    @Test
    fun `reload drops tasks that have not started`() {
        val env = Env()
        root(env, "async.task(function() _result = { 'ran' } end)")
        env.asyncLib.invalidate()
        env.pump(maxRounds = 5)
        assertTrue(env.state.globals.get("_result").isnil())
    }

    @Test
    fun `reload does not resume a waiting coroutine`() {
        val env = Env()
        val co = root(env, "local p = async.promise() _G.p = p p:wait() _result = { 'resumed' }")
        env.pump(maxRounds = 3)
        assertEquals("suspended", co.status)
        env.asyncLib.invalidate()
        env.state.globals.get("p").get("resolve").invoke(env.state.globals.get("p"))
        env.pump(maxRounds = 5)
        assertEquals("suspended", co.status)
        assertTrue(env.state.globals.get("_result").isnil())
    }

    @Test
    fun `fetch finishing after reload does not resume the coroutine`() {
        val release = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            release.await(5, TimeUnit.SECONDS)
            val body = "{}".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val env = Env()
            val url = "http://127.0.0.1:${server.address.port}/"
            val co = root(env, "local r = async.fetch('$url') _result = { r.status }")
            assertEquals("suspended", co.status)
            env.asyncLib.invalidate()
            release.countDown()
            Thread.sleep(500)
            env.pump(maxRounds = 5)
            assertEquals("suspended", co.status)
            assertTrue(env.state.globals.get("_result").isnil())
        } finally {
            release.countDown()
            server.stop(0)
        }
    }

    @Test
    fun `fetch finishing in the same generation resumes the coroutine`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val body = "{\"n\": 5}".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val env = Env()
            val url = "http://127.0.0.1:${server.address.port}/"
            val co = root(env, "local r = async.fetch('$url') _result = { r.status, r.json.n }")
            val deadline = System.currentTimeMillis() + 5000
            while (co.status != "dead" && System.currentTimeMillis() < deadline) {
                env.pump(maxRounds = 1)
                Thread.sleep(5)
            }
            assertTrue(env.failures.isEmpty(), env.failures.toString())
            assertEquals("dead", co.status)
            val r = env.state.globals.get("_result").checktable()
            assertEquals(200, r.get(1).toint())
            assertEquals(5, r.get(2).toint())
        } finally {
            server.stop(0)
        }
    }

    // ── resume plumbing ────────────────────────────────────────────

    @Test
    fun `resumeThread without a handler fails clearly`() {
        val env = Env()
        val thread = LuaThread(env.state, env.state.load("return 1", "t").checkfunction())
        val e = assertFailsWith<LuaError> { env.asyncLib.resumeThread(thread, LuaValue.NONE, "ctx", 0) }
        assertTrue(e.message!!.contains("no resume handler"), e.message)
    }

    @Test
    fun `a stale generation is not resumed`() {
        val env = Env()
        val thread = LuaThread(env.state, env.state.load("return 1", "t").checkfunction())
        env.asyncLib.invalidate()
        env.asyncLib.resumeThread(thread, LuaValue.NONE, "ctx", 0)
    }
}
