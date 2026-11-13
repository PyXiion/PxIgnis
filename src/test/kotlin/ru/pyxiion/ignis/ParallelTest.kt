package ru.pyxiion.ignis

import org.luaj.vm2.LuaState
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaThread
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import ru.pyxiion.ignis.api.AsyncLib
import ru.pyxiion.ignis.api.MetaTableRegistry
import ru.pyxiion.ignis.api.initVecMeta
import ru.pyxiion.ignis.runtime.installStdlib
import ru.pyxiion.ignis.sandbox.Vfs
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Parallel calls run on pool threads, so the results arrive asynchronously: [Env.pump] sleeps a millisecond per
 * round and the test thread plays the server thread.
 */
class ParallelTest {

    private class Env {
        val vfs = Vfs(listOf(Files.createTempDirectory("ignis")))
        val state = LuaState()
        val stdlib = installStdlib(state, vfs)
        val queue = ConcurrentLinkedQueue<Runnable>()
        val scheduler = Scheduler { state }
        val asyncLib = AsyncLib({ state }, scheduler, vfs)
        val failures = mutableListOf<String>()

        init {
            state.globals.set("async", asyncLib.buildModule(stdlib))
            state.globals.set("_result", LuaValue.NIL)
            LuaState.setCurrent(state)
        }

        fun resumeRoot(co: LuaThread, args: Varargs) {
            val r = co.resume(args)
            if (!r.arg1().toboolean()) failures.add(r.arg(2).optjstring("coroutine error"))
        }

        fun pump(maxRounds: Int = 5000, until: () -> Boolean = { false }) {
            repeat(maxRounds) {
                if (until()) return
                asyncLib.drain()
                while (true) (queue.poll() ?: break).run()
                scheduler.tick()
                Thread.sleep(1)
            }
        }

        fun root(script: String): LuaThread {
            val co = LuaThread(state, state.load(script, "test").checkfunction())
            co.resumeHandler = LuaThread.ResumeHandler { _, value -> queue.add { resumeRoot(co, value) } }
            resumeRoot(co, LuaValue.NONE)
            return co
        }
    }

    @BeforeTest
    fun vecMeta() {
        initVecMeta(MetaTableRegistry.VEC)
    }

    /** Runs [script] as a coroutine to completion and returns the global `_result` table. */
    private fun run(script: String): LuaTable {
        val env = Env()
        try {
            val co = env.root(script)
            env.pump(until = { co.status == "dead" })
            assertTrue(env.failures.isEmpty(), "coroutine failed: ${env.failures}")
            assertEquals("dead", co.status, "coroutine did not complete")
            return env.state.globals.get("_result").checktable()
        } finally {
            env.asyncLib.invalidate()
        }
    }

    private fun LuaTable.str(i: Int): String = get(i).tojstring()

    @Test
    fun `captured values and arguments reach the worker`() {
        val r = run(
            """
            local k = 3
            local f = async.parallel(function(a, b) return a * b + k end)
            _result = { f(4, 5) }
            """.trimIndent()
        )
        assertEquals(23, r.get(1).toint())
    }

    @Test
    fun `main globals are not visible to the worker`() {
        val r = run(
            """
            x = 1
            local ok, err = pcall(async.parallel, function() return x end)
            _result = { ok, err }
            """.trimIndent()
        )
        assertFalse(r.get(1).toboolean())
        assertTrue(r.str(2).contains("global 'x' is not available"), r.str(2))
    }

    @Test
    fun `a recursive local function can be captured`() {
        val r = run(
            """
            local function fib(n) if n < 2 then return n end return fib(n - 1) + fib(n - 2) end
            local f = async.parallel(function(n) return fib(n) end)
            _result = { f(20) }
            """.trimIndent()
        )
        assertEquals(6765, r.get(1).toint())
    }

    @Test
    fun `stdlib functions captured as locals are the workers own`() {
        val r = run(
            """
            local floor, ins = math.floor, table.insert
            local f = async.parallel(function(x) local t = {} ins(t, floor(x)) return t[1] end)
            _result = { f(3.7) }
            """.trimIndent()
        )
        assertEquals(3, r.get(1).toint())
    }

    @Test
    fun `plain tables are copied in both directions`() {
        val r = run(
            """
            local t = { 1, 2 }
            local f = async.parallel(function(a) a[#a + 1] = 3; a.x = "y"; return a end)
            local out = f(t)
            _result = { #t, #out, out.x, t.x }
            """.trimIndent()
        )
        assertEquals(2, r.get(1).toint())
        assertEquals(3, r.get(2).toint())
        assertEquals("y", r.str(3))
        assertTrue(r.get(4).isnil())
    }

    @Test
    fun `a frozen table crosses by reference and stays read-only`() {
        val r = run(
            """
            local t = table.freeze({ grid = { 1, 2, 3 } })
            local f = async.parallel(function() return t end)
            local out = f()
            local ok, err = pcall(function() t.grid[1] = 9 end)
            _result = { rawequal(out, t), ok, err, table.isfrozen(t.grid) }
            """.trimIndent()
        )
        assertTrue(r.get(1).toboolean())
        assertFalse(r.get(2).toboolean())
        assertTrue(r.str(3).contains("frozen table"), r.str(3))
        assertTrue(r.get(4).toboolean())
    }

    @Test
    fun `a userdata capture fails at definition`() {
        val r = run(
            """
            local p = async.promise()
            local ok, err = pcall(async.parallel, function() return p end)
            _result = { ok, err }
            """.trimIndent()
        )
        assertFalse(r.get(1).toboolean())
        assertTrue(r.str(2).contains("upvalue 'p' is a userdata and cannot cross threads"), r.str(2))
    }

    @Test
    fun `assigning a global fails at definition`() {
        val r = run(
            """
            local ok, err = pcall(async.parallel, function() y = 1 end)
            _result = { ok, err }
            """.trimIndent()
        )
        assertFalse(r.get(1).toboolean())
        assertTrue(r.str(2).contains("assignment to global 'y'"), r.str(2))
    }

    @Test
    fun `a computed global read fails in the worker`() {
        val r = run(
            """
            local f = async.parallel(function(name) return _G[name] end)
            local ok, err = pcall(f, "nope")
            _result = { ok, err }
            """.trimIndent()
        )
        assertFalse(r.get(1).toboolean())
        assertTrue(r.str(2).contains("is not available in a parallel function"), r.str(2))
    }

    @Test
    fun `an error in the worker reaches the caller`() {
        val r = run(
            """
            local f = async.parallel(function() error("boom") end)
            local ok, err = pcall(f)
            _result = { ok, err }
            """.trimIndent()
        )
        assertFalse(r.get(1).toboolean())
        assertTrue(r.str(2).contains("boom"), r.str(2))
    }

    @Test
    fun `table freeze rejects shared objects`() {
        val r = run(
            """
            local ok, err = pcall(table.freeze, { c = async.counter() })
            _result = { ok, err }
            """.trimIndent()
        )
        assertFalse(r.get(1).toboolean())
        assertTrue(r.str(2).contains("table.c is a userdata and cannot be frozen"), r.str(2))
    }

    @Test
    fun `captures are copied at every call`() {
        val r = run(
            """
            local n = 1
            local f = async.parallel(function() return n end)
            local a = f()
            n = 2
            local b = f()
            _result = { a, b }
            """.trimIndent()
        )
        assertEquals(1, r.get(1).toint())
        assertEquals(2, r.get(2).toint())
    }

    @Test
    fun `vectors are copied`() {
        val r = run(
            """
            local f = async.parallel(function(v) return v.x + v.y + v.z end)
            _result = { f(vec(1, 2, 3)) }
            """.trimIndent()
        )
        assertEquals(6, r.get(1).toint())
    }

    @Test
    fun `coroutines work inside a worker`() {
        val r = run(
            """
            local f = async.parallel(function()
                local gen = coroutine.wrap(function() for i = 1, 10 do coroutine.yield(i) end end)
                local s = 0
                for i = 1, 10 do s = s + gen() end
                return s
            end)
            _result = { f() }
            """.trimIndent()
        )
        assertEquals(55, r.get(1).toint())
    }

    @Test
    fun `a function cannot be returned`() {
        val r = run(
            """
            local f = async.parallel(function() return function() end end)
            local ok, err = pcall(f)
            _result = { ok, err }
            """.trimIndent()
        )
        assertFalse(r.get(1).toboolean())
        assertTrue(r.str(2).contains("result #1 is a function and cannot cross threads"), r.str(2))
    }

    @Test
    fun `table freeze names the offending value`() {
        val r = run(
            """
            local t = { ok = { 1, 2 }, bad = { f = function() end } }
            local ok, err = pcall(table.freeze, t)
            _result = { ok, err, table.isfrozen(t), table.isfrozen(t.ok) }
            """.trimIndent()
        )
        assertFalse(r.get(1).toboolean())
        assertTrue(r.str(2).contains("table.bad.f is a function and cannot be frozen"), r.str(2))
        assertFalse(r.get(3).toboolean())
        assertFalse(r.get(4).toboolean())
    }

    @Test
    fun `table freeze handles cycles`() {
        val r = run(
            """
            local a = { name = "a" }
            a.self = a
            table.freeze(a)
            _result = { table.isfrozen(a), a.self.self.name }
            """.trimIndent()
        )
        assertTrue(r.get(1).toboolean())
        assertEquals("a", r.str(2))
    }

    @Test
    fun `counter is atomic across workers`() {
        val r = run(
            """
            local c = async.counter()
            local f = async.parallel(function() for i = 1, 1000 do c:add(1) end end)
            local tasks = {}
            for i = 1, 8 do tasks[i] = async.task(function() f() end) end
            async.all(tasks)
            _result = { c:get() }
            """.trimIndent()
        )
        assertEquals(8000, r.get(1).toint())
    }

    @Test
    fun `map update is atomic across workers`() {
        val r = run(
            """
            local m = async.map()
            local f = async.parallel(function()
                for i = 1, 500 do m:update("k", function(v) return (v or 0) + 1 end) end
            end)
            local tasks = {}
            for i = 1, 4 do tasks[i] = async.task(function() f() end) end
            async.all(tasks)
            _result = { m:get("k"), m:size() }
            """.trimIndent()
        )
        assertEquals(2000, r.get(1).toint())
        assertEquals(1, r.get(2).toint())
    }

    @Test
    fun `channel moves values from a worker to the server coroutine`() {
        val r = run(
            """
            local ch = async.channel(4)
            local producer = async.parallel(function()
                for i = 1, 100 do ch:send(i) end
                ch:close()
            end)
            local t = async.task(function() producer() end)
            local sum = 0
            while true do
                local v = ch:receive()
                if v == nil then break end
                sum = sum + v
            end
            t:wait()
            _result = { sum }
            """.trimIndent()
        )
        assertEquals(5050, r.get(1).toint())
    }

    /**
     * With capacity 1 the producer often delivers right between the receiver's empty `tryReceive` and its
     * `onItem` registration. That wake-up must not resume the coroutine before it has yielded: a double resume
     * shows up as a coroutine failure. The window is a race, so this is a best-effort stress test.
     */
    @Test
    fun `a receive woken while registering resumes exactly once`() {
        val r = run(
            """
            local ch = async.channel(1)
            local producer = async.parallel(function()
                for i = 1, 300 do ch:send(i) end
                ch:close()
            end)
            local t = async.task(function() producer() end)
            local sum, n = 0, 0
            while true do
                local v = ch:receive()
                if v == nil then break end
                sum = sum + v
                n = n + 1
            end
            t:wait()
            _result = { sum, n }
            """.trimIndent()
        )
        assertEquals(45150, r.get(1).toint())
        assertEquals(300, r.get(2).toint())
    }

    @Test
    fun `a parallel call outside a coroutine fails before any work is submitted`() {
        val env = Env()
        try {
            val r = env.state.load(
                """
                local c = async.counter(0)
                local f = async.parallel(function() c:add(1) end)
                local ok, err = pcall(f)
                _result = c
                return ok, err
                """.trimIndent(),
                "main"
            ).invoke(LuaValue.NONE)
            assertTrue(!r.arg(1).toboolean())
            assertTrue(r.arg(2).tojstring().contains("must be called inside a coroutine"), r.arg(2).tojstring())
            env.pump(maxRounds = 50)
            val count = env.state.load("return _result:get()", "count").invoke(LuaValue.NONE)
            assertEquals(0, count.arg(1).toint(), "the worker ran")
        } finally {
            env.asyncLib.invalidate()
        }
    }

    @Test
    fun `a channel between two tasks is worked off within one drain`() {
        val env = Env()
        try {
            env.root(
                """
                local ch = async.channel(1)
                async.task(function() for i = 1, 20 do ch:send(i) end ch:close() end)
                async.task(function()
                    local n = 0
                    while ch:receive() ~= nil do n = n + 1 end
                    _result = n
                end)
                """.trimIndent()
            )
            assertTrue(env.state.globals.get("_result").isnil(), "a task ran inline")
            env.asyncLib.drain()
            assertEquals(20, env.state.globals.get("_result").toint())
        } finally {
            env.asyncLib.invalidate()
        }
    }

    @Test
    fun `a blocking send outside a coroutine fails without registering a waiter`() {
        val env = Env()
        try {
            val r = env.state.load(
                """
                local ch = async.channel(1)
                ch:send(1)
                return pcall(ch.send, ch, 2)
                """.trimIndent(),
                "main"
            ).invoke(LuaValue.NONE)
            assertTrue(!r.arg(1).toboolean())
            assertTrue(r.arg(2).tojstring().contains("must be called inside a coroutine"), r.arg(2).tojstring())
        } finally {
            env.asyncLib.invalidate()
        }
    }

    @Test
    fun `an error object from a worker arrives as a string`() {
        val r = run(
            """
            local f = async.parallel(function() error({ code = 1 }) end)
            local ok, err = pcall(f)
            _result = { ok, type(err) }
            """.trimIndent()
        )
        assertEquals(false, r.get(1).toboolean())
        assertEquals("string", r.get(2).tojstring())
    }

    @Test
    fun `invalidate cancels a running worker`() {
        val env = Env()
        try {
            env.root(
                """
                local f = async.parallel(function() while true do end end)
                async.task(function() f() end)
                """.trimIndent()
            )
            env.pump(maxRounds = 100)
            assertTrue(workerThreads().isNotEmpty(), "the worker did not start")
        } finally {
            env.asyncLib.invalidate()
        }
        val deadline = System.nanoTime() + 2_000_000_000L
        while (workerThreads().isNotEmpty() && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(emptyList(), workerThreads())
    }

    private fun workerThreads() =
        Thread.getAllStackTraces().keys.filter { it.isAlive && it.name.startsWith("PxIgnis-parallel-") }.map { it.name }

    @Test
    fun `the queue is bounded`() {
        val env = Env()
        try {
            env.root(
                """
                local ch = async.channel(1)
                local f = async.parallel(function() return ch:receive() end)
                for i = 1, 300 do
                    async.task(function()
                        local ok, err = pcall(f)
                        if not ok then _result = err end
                    end)
                end
                """.trimIndent()
            )
            env.pump(until = { !env.state.globals.get("_result").isnil() })
            val message = env.state.globals.get("_result")
            assertNull(env.failures.firstOrNull())
            assertTrue(message.tojstring().contains("too many pending calls"), message.tojstring())
        } finally {
            env.asyncLib.invalidate()
        }
    }
}
