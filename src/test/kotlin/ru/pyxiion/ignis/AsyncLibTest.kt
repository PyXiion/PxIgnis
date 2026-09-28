package ru.pyxiion.ignis

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaState
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaThread
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.jse.JsePlatform
import ru.pyxiion.ignis.api.AsyncExecutor
import ru.pyxiion.ignis.api.AsyncExecutorRegistry
import ru.pyxiion.ignis.api.AsyncLib

class AsyncLibTest {

    // Serialized resume gate for the root test coroutine. The initial resume
    // runs synchronously on the test thread (so busy loops don't block the
    // executor that runs the task); subsequent resumes are dispatched through
    // the main executor. Requests that arrive while a resume is in flight are
    // queued and drained after the initial resume returns.
    private class RootGate(
        private val thread: LuaThread,
        private val executor: AsyncExecutor,
    ) {
        private val lock = ReentrantLock()
        private var running = false
        private val pending = ArrayDeque<Varargs>()
        val failures = CopyOnWriteArrayList<Throwable>()

        fun start(initialArgs: Varargs): Varargs {
            lock.withLock {
                if (running) return LuaValue.NONE
                running = true
            }
            val result: Varargs
            try {
                result = thread.resume(initialArgs)
            } catch (e: Throwable) {
                failures.add(e)
                throw e
            } finally {
                val queued: MutableList<Varargs> = ArrayList()
                lock.withLock {
                    running = false
                    if (thread.status == "dead") {
                        pending.clear()
                    } else {
                        queued.addAll(pending)
                        pending.clear()
                    }
                }
                for (a in queued) dispatch(a)
            }
            return result
        }

        fun requestResume(args: Varargs) {
            var runNow = false
            lock.withLock {
                if (running) {
                    if (thread.status != "dead") pending.addLast(args)
                    return
                }
                if (thread.status == "dead") return
                runNow = true
            }
            if (runNow) dispatch(args)
        }

        private fun dispatch(args: Varargs) {
            executor.dispatch {
                try {
                    val r = thread.resume(args)
                    if (!r.arg1().toboolean()) {
                        failures.add(
                            thread.lastError ?: LuaError(r.arg(2).optjstring("test coroutine error"))
                        )
                    }
                } catch (e: Throwable) {
                    failures.add(e)
                }
            }
        }
    }

    private class Env(
        val state: LuaState,
        val asyncLib: AsyncLib,
        val mainExecutor: ExecutorService,
        val poolExecutor: ExecutorService,
        val latch: CountDownLatch,
        val registry: AsyncExecutorRegistry,
        val recordedExecutions: CopyOnWriteArrayList<String>,
        val scheduler: Scheduler,
    )

    private fun newEnv(): Env {
        val state = JsePlatform.standardState()
        val mainExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "pxrp-test-main") }
        val poolExecutor = Executors.newFixedThreadPool(2) { r -> Thread(r, "pxrp-test-pool") }
        val latch = CountDownLatch(1)
        val scheduler = Scheduler { state }
        val registry = AsyncExecutorRegistry()
        val recordedExecutions = CopyOnWriteArrayList<String>()

        registry.register(
            AsyncExecutor(
                name = "main",
                dispatch = { runnable ->
                    mainExecutor.execute {
                        recordedExecutions.add("main:${Thread.currentThread().name}")
                        runnable.run()
                    }
                },
                shutdown = { mainExecutor.shutdown() },
            )
        )
        registry.register(
            AsyncExecutor(
                name = "threadpool",
                dispatch = { runnable ->
                    poolExecutor.execute {
                        recordedExecutions.add("threadpool:${Thread.currentThread().name}")
                        runnable.run()
                    }
                },
                shutdown = { poolExecutor.shutdown() },
            )
        )

        val asyncLib = AsyncLib(registry, state, scheduler)
        state.globals.set("async", asyncLib.buildModule())
        state.globals.set("block", luaFunctionNil { _ ->
            try {
                latch.await()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        })
        state.globals.set("record", luaFunction { arg ->
            recordedExecutions.add("record:${arg.optjstring("")}:${Thread.currentThread().name}")
            LuaValue.NIL
        })
        state.globals.set("_result", LuaValue.NIL)
        LuaState.setCurrent(state)
        state.getMainThread().executionContext = registry.resolve("main")
        return Env(state, asyncLib, mainExecutor, poolExecutor, latch, registry, recordedExecutions, scheduler)
    }

    private fun runScript(env: Env, script: String, contextExecutor: AsyncExecutor? = null): LuaValue {
        val func = env.state.load(script, "test").checkfunction()
        val co = LuaThread(env.state, func)
        val mainExec = env.registry.resolve("main")
        co.executionContext = contextExecutor ?: mainExec

        val gate = RootGate(co, mainExec)
        co.resumeHandler = LuaThread.ResumeHandler { _, value ->
            gate.requestResume(value)
        }

        val result = gate.start(LuaValue.NONE)
        check(result.arg1().toboolean()) { "coroutine error: ${result.arg(2)}" }
        check(gate.failures.isEmpty()) { "coroutine failed: ${gate.failures.firstOrNull()?.message}" }

        if (co.status != "dead") {
            env.latch.countDown()
            val deadline = System.currentTimeMillis() + 5000
            while (co.status != "dead") {
                if (System.currentTimeMillis() > deadline) {
                    error("coroutine did not complete: status=${co.status}")
                }
                env.scheduler.tick()
                Thread.sleep(1)
            }
        }
        check(gate.failures.isEmpty()) { "coroutine failed: ${gate.failures.firstOrNull()?.message}" }
        return env.state.globals.get("_result")
    }

    private fun newEnvAndRun(script: String): LuaTable {
        val env = newEnv()
        try {
            return runScript(env, script).checktable()
        } finally {
            env.registry.shutdown()
            env.latch.countDown()
        }
    }

    // ── Task: done ─────────────────────────────────────────────────

    @Test
    fun `task done is false while pending`() {
        val env = newEnv()
        try {
            val pending = runScript(
                env,
                """
                local t = async.task("main", function() block() return 42 end)
                _result = { t.done }
                """.trimIndent()
            ).checktable()
            assertFalse(pending.get(1).toboolean())
        } finally {
            env.registry.shutdown()
            env.latch.countDown()
        }
    }

    @Test
    fun `task done is true when complete`() {
        val done = newEnvAndRun(
            """
            local t = async.task("main", function() return 42 end)
            while not t.done do end
            _result = { t.done }
            """.trimIndent()
        )
        assertTrue(done.get(1).toboolean())
    }

    // ── Task: wait ─────────────────────────────────────────────────

    @Test
    fun `task wait sync returns raw result`() {
        val result = newEnvAndRun(
            """
            local t = async.task("main", function() return 42 end)
            while not t.done do end
            local r = t:wait()
            _result = { r }
            """.trimIndent()
        )
        assertEquals(42, result.get(1).toint())
    }

    @Test
    fun `task wait async yields and returns raw result`() {
        val result = newEnvAndRun(
            """
            local t = async.task("main", function() block() return 42 end)
            local r = t:wait()
            _result = { r }
            """.trimIndent()
        )
        assertEquals(42, result.get(1).toint())
    }

    @Test
    fun `task wait sync throws on error`() {
        val result = newEnvAndRun(
            """
            local t = async.task("main", function() error("boom") end)
            while not t.done do end
            local ok, err = pcall(function() return t:wait() end)
            _result = { ok, err }
            """.trimIndent()
        )
        assertFalse(result.get(1).toboolean())
        assertTrue(result.get(2).tojstring().contains("boom"))
    }

    @Test
    fun `task wait async throws on error`() {
        val result = newEnvAndRun(
            """
            local t = async.task("main", function() block() error("boom") end)
            local ok, err = pcall(function() return t:wait() end)
            _result = { ok, err }
            """.trimIndent()
        )
        assertFalse(result.get(1).toboolean())
        assertTrue(result.get(2).tojstring().contains("boom"))
    }

    // ── Task: try ──────────────────────────────────────────────────

    @Test
    fun `task try sync returns ok and result`() {
        val result = newEnvAndRun(
            """
            local t = async.task("main", function() return 42 end)
            while not t.done do end
            local ok, r = t:try()
            _result = { ok, r }
            """.trimIndent()
        )
        assertTrue(result.get(1).toboolean())
        assertEquals(42, result.get(2).toint())
    }

    @Test
    fun `task try async yields and returns ok and result`() {
        val result = newEnvAndRun(
            """
            local t = async.task("main", function() block() return 42 end)
            local ok, r = t:try()
            _result = { ok, r }
            """.trimIndent()
        )
        assertTrue(result.get(1).toboolean())
        assertEquals(42, result.get(2).toint())
    }

    @Test
    fun `task try sync returns false and error message`() {
        val result = newEnvAndRun(
            """
            local t = async.task("main", function() error("boom") end)
            while not t.done do end
            local ok, r = t:try()
            _result = { ok, r }
            """.trimIndent()
        )
        assertFalse(result.get(1).toboolean())
        assertTrue(result.get(2).tojstring().contains("boom"))
    }

    @Test
    fun `task try async returns false and error message`() {
        val result = newEnvAndRun(
            """
            local t = async.task("main", function() block() error("boom") end)
            local ok, r = t:try()
            _result = { ok, r }
            """.trimIndent()
        )
        assertFalse(result.get(1).toboolean())
        assertTrue(result.get(2).tojstring().contains("boom"))
    }

    // ── Task: rejects ──────────────────────────────────────────────

    @Test
    fun `task rejects non-function argument`() {
        val result = newEnvAndRun(
            """
            local ok, err = pcall(async.task, "main", 42)
            _result = { ok, err }
            """.trimIndent()
        )
        assertFalse(result.get(1).toboolean())
        assertTrue(result.get(2).tojstring().contains("expected function"))
    }

    @Test
    fun `task wait rejects non-task self`() {
        val result = newEnvAndRun(
            """
            local ok, err = pcall(function() return ("nope"):wait() end)
            _result = { ok, err }
            """.trimIndent()
        )
        assertFalse(result.get(1).toboolean())
    }

    @Test
    fun `task try rejects non-task self`() {
        val result = newEnvAndRun(
            """
            local ok, err = pcall(function() return ("nope"):try() end)
            _result = { ok, err }
            """.trimIndent()
        )
        assertFalse(result.get(1).toboolean())
    }

    @Test
    fun `task rejects unknown executor`() {
        val result = newEnvAndRun(
            """
            local ok, err = pcall(async.task, "nonexistent", function() end)
            _result = { ok, err }
            """.trimIndent()
        )
        assertFalse(result.get(1).toboolean())
        assertTrue(result.get(2).tojstring().contains("unknown executor"))
    }

    @Test
    fun `task rejects missing executor`() {
        val result = newEnvAndRun(
            """
            local ok, err = pcall(async.task, function() end)
            _result = { ok, err }
            """.trimIndent()
        )
        assertFalse(result.get(1).toboolean())
    }

    // ── Executor dispatch ──────────────────────────────────────────

    @Test
    fun `main task runs on main executor`() {
        val env = newEnv()
        try {
            runScript(
                env,
                """
                async.task("main", function()
                    _result = { "ran" }
                end)
                """.trimIndent()
            )
            val deadline = System.currentTimeMillis() + 5000
            while (env.state.globals.get("_result").isnil()) {
                if (System.currentTimeMillis() > deadline) error("main task did not run")
                Thread.sleep(5)
            }
            assertTrue(env.recordedExecutions.any { it.startsWith("main:pxrp-test-main") })
        } finally {
            env.registry.shutdown()
            env.latch.countDown()
        }
    }

    @Test
    fun `threadpool task runs on threadpool executor`() {
        val env = newEnv()
        try {
            runScript(
                env,
                """
                async.task("threadpool", function()
                    _result = { "ran" }
                end)
                """.trimIndent()
            )
            val deadline = System.currentTimeMillis() + 5000
            while (env.state.globals.get("_result").isnil()) {
                if (System.currentTimeMillis() > deadline) error("threadpool task did not run")
                Thread.sleep(5)
            }
            assertTrue(env.recordedExecutions.any { it.startsWith("threadpool:pxrp-test-pool") })
        } finally {
            env.registry.shutdown()
            env.latch.countDown()
        }
    }

    @Test
    fun `executor selection preserved after sleep`() {
        val env = newEnv()
        try {
            runScript(
                env,
                """
                async.task("threadpool", function()
                    async.sleep(1)
                    _result = { "done" }
                end)
                """.trimIndent()
            )
            val deadline = System.currentTimeMillis() + 5000
            while (env.state.globals.get("_result").isnil()) {
                if (System.currentTimeMillis() > deadline) error("sleep task did not complete")
                env.scheduler.tick()
                Thread.sleep(1)
            }
            assertTrue(env.recordedExecutions.any { it.startsWith("threadpool:pxrp-test-pool") })
        } finally {
            env.registry.shutdown()
            env.latch.countDown()
        }
    }

    @Test
    fun `multiple pool tasks overlap`() {
        val env = newEnv()
        try {
            runScript(
                env,
                """
                for i = 1, 2 do
                    async.task("threadpool", function()
                        record("start")
                        block()
                        record("end")
                    end)
                end
                """.trimIndent()
            )
            val deadline = System.currentTimeMillis() + 5000
            while (env.recordedExecutions.count { it.startsWith("record:start") } < 2) {
                if (System.currentTimeMillis() > deadline) error("tasks did not both enter")
                Thread.sleep(5)
            }
            assertTrue(env.recordedExecutions.none { it.startsWith("record:end") })
            env.latch.countDown()
            while (env.recordedExecutions.count { it.startsWith("record:end") } < 2) {
                if (System.currentTimeMillis() > deadline) error("tasks did not both finish")
                Thread.sleep(5)
            }
            assertTrue(
                env.recordedExecutions.filter { it.startsWith("record:") }
                    .all { it.contains("pxrp-test-pool") }
            )
            assertTrue(env.recordedExecutions.none { it.contains("pxrp-test-main") })
        } finally {
            env.registry.shutdown()
            env.latch.countDown()
        }
    }

    // ── Promise ────────────────────────────────────────────────────

    @Test
    fun `promise resolve and wait`() {
        val result = newEnvAndRun(
            """
            local p = async.promise()
            p:resolve(42)
            local r = p:wait()
            _result = { r }
            """.trimIndent()
        )
        assertEquals(42, result.get(1).toint())
    }

    @Test
    fun `promise error and try`() {
        val result = newEnvAndRun(
            """
            local p = async.promise()
            p:error("boom")
            local ok, msg = p:try()
            _result = { ok, msg }
            """.trimIndent()
        )
        assertFalse(result.get(1).toboolean())
        assertTrue(result.get(2).tojstring().contains("boom"))
    }

    @Test
    fun `promise resolve returns true`() {
        val result = newEnvAndRun(
            """
            local p = async.promise()
            local ok = p:resolve(42)
            _result = { ok }
            """.trimIndent()
        )
        assertTrue(result.get(1).toboolean())
    }

    @Test
    fun `promise double resolve returns false`() {
        val result = newEnvAndRun(
            """
            local p = async.promise()
            p:resolve(42)
            local ok = p:resolve(99)
            _result = { ok }
            """.trimIndent()
        )
        assertFalse(result.get(1).toboolean())
    }

    @Test
    fun `promise state transitions`() {
        val result = newEnvAndRun(
            """
            local p = async.promise()
            local s1 = p.state
            p:resolve(42)
            local s2 = p.state
            _result = { s1, s2 }
            """.trimIndent()
        )
        assertEquals("pending", result.get(1).tojstring())
        assertEquals("resolved", result.get(2).tojstring())
    }

    @Test
    fun `promise done property`() {
        val result = newEnvAndRun(
            """
            local p = async.promise()
            local d1 = p.done
            p:resolve(42)
            local d2 = p.done
            _result = { d1, d2 }
            """.trimIndent()
        )
        assertFalse(result.get(1).toboolean())
        assertTrue(result.get(2).toboolean())
    }

    // ── all / allSettled ───────────────────────────────────────────

    @Test
    fun `all returns results for all tasks`() {
        val result = newEnvAndRun(
            """
            local t1 = async.task("main", function() return 1 end)
            local t2 = async.task("main", function() return 2 end)
            while not t1.done do end
            while not t2.done do end
            local results = async.all(t1, t2)
            _result = { results[1].ok, results[1].value, results[2].ok, results[2].value }
            """.trimIndent()
        )
        assertTrue(result.get(1).toboolean())
        assertEquals(1, result.get(2).toint())
        assertTrue(result.get(3).toboolean())
        assertEquals(2, result.get(4).toint())
    }

    @Test
    fun `all rejects when any task fails`() {
        val result = newEnvAndRun(
            """
            local t1 = async.task("main", function() return 1 end)
            local t2 = async.task("main", function() error("boom") end)
            while not t1.done do end
            while not t2.done do end
            local ok, err = pcall(function() return async.all(t1, t2) end)
            _result = { ok, err }
            """.trimIndent()
        )
        assertFalse(result.get(1).toboolean())
        assertTrue(result.get(2).tojstring().contains("boom"))
    }

    @Test
    fun `allSettled returns results for all tasks`() {
        val result = newEnvAndRun(
            """
            local t1 = async.task("main", function() return 1 end)
            local t2 = async.task("main", function() error("boom") end)
            while not t1.done do end
            while not t2.done do end
            local results = async.allSettled(t1, t2)
            _result = { results[1].ok, results[1].value, results[2].ok, results[2].error }
            """.trimIndent()
        )
        assertTrue(result.get(1).toboolean())
        assertEquals(1, result.get(2).toint())
        assertFalse(result.get(3).toboolean())
        assertTrue(result.get(4).tojstring().contains("boom"))
    }

    @Test
    fun `all with empty input returns empty table`() {
        val result = newEnvAndRun(
            """
            local results = async.all()
            _result = { results }
            """.trimIndent()
        )
        assertTrue(result.get(1).istable())
        assertEquals(0, result.get(1).checktable().length().toInt())
    }

    // ── async.run ──────────────────────────────────────────────────

    @Test
    fun `run returns result`() {
        val result = newEnvAndRun(
            """
            local a, b, c = async.run("main", function()
                return 1, 2, 3
            end)
            _result = { a, b, c }
            """.trimIndent()
        )
        assertEquals(1, result.get(1).toint())
        assertEquals(2, result.get(2).toint())
        assertEquals(3, result.get(3).toint())
    }

    @Test
    fun `run propagates error`() {
        val result = newEnvAndRun(
            """
            local ok, err = pcall(function()
                return async.run("main", function()
                    error("boom")
                end)
            end)
            _result = { ok, err }
            """.trimIndent()
        )
        assertFalse(result.get(1).toboolean())
        assertTrue(result.get(2).tojstring().contains("boom"))
    }

    @Test
    fun `run uses requested executor`() {
        val env = newEnv()
        try {
            runScript(
                env,
                """
                async.run("threadpool", function()
                    _result = { "done" }
                end)
                """.trimIndent()
            )
            val deadline = System.currentTimeMillis() + 5000
            while (env.state.globals.get("_result").isnil()) {
                if (System.currentTimeMillis() > deadline) error("run task did not complete")
                Thread.sleep(5)
            }
            assertTrue(env.recordedExecutions.any { it.startsWith("threadpool:pxrp-test-pool") })
        } finally {
            env.registry.shutdown()
            env.latch.countDown()
        }
    }

    // ── Mutex ──────────────────────────────────────────────────────

    @Test
    fun `uncontended mutex with executes immediately`() {
        val result = newEnvAndRun(
            """
            local m = async.mutex()
            local val = m:with(function()
                return 42
            end)
            _result = { val }
            """.trimIndent()
        )
        assertEquals(42, result.get(1).toint())
    }

    @Test
    fun `mutex with preserves return values`() {
        val result = newEnvAndRun(
            """
            local m = async.mutex()
            local a, b = m:with(function()
                return 10, 20
            end)
            _result = { a, b }
            """.trimIndent()
        )
        assertEquals(10, result.get(1).toint())
        assertEquals(20, result.get(2).toint())
    }

    @Test
    fun `mutex with passes arguments to callback`() {
        val result = newEnvAndRun(
            """
            local m = async.mutex()
            local val = m:with(function(x)
                return x * 2
            end, 21)
            _result = { val }
            """.trimIndent()
        )
        assertEquals(42, result.get(1).toint())
    }

    @Test
    fun `mutex with catches callback error`() {
        val result = newEnvAndRun(
            """
            local m = async.mutex()
            local ok, err = pcall(function()
                return m:with(function()
                    error("mutex boom")
                end)
            end)
            _result = { ok, err }
            """.trimIndent()
        )
        assertFalse(result.get(1).toboolean())
        assertTrue(result.get(2).tojstring().contains("mutex boom"))
    }

    @Test
    fun `mutex next waiter runs after error`() {
        val result = newEnvAndRun(
            """
            local m = async.mutex()
            local order = {}

            async.task("main", function()
                m:with(function()
                    table.insert(order, "error")
                    error("fail")
                end)
            end)

            async.task("main", function()
                async.sleep(1)
                m:with(function()
                    table.insert(order, "second")
                    return "ok"
                end)
            end)

            async.task("main", function()
                while #order < 2 do async.sleep(1) end
                _result = order
            end)

            while _result == nil do async.sleep(1) end
            """.trimIndent()
        )
        assertEquals("error", result.get(1).tojstring())
        assertEquals("second", result.get(2).tojstring())
    }

    @Test
    fun `mutex contended with waits`() {
        val result = newEnvAndRun(
            """
            local m = async.mutex()
            local order = {}

            async.task("main", function()
                m:with(function()
                    table.insert(order, "first")
                    async.sleep(5)
                end)
            end)

            async.task("main", function()
                async.sleep(1)
                m:with(function()
                    table.insert(order, "second")
                end)
            end)

            async.task("main", function()
                while #order < 2 do async.sleep(1) end
                _result = order
            end)

            while _result == nil do async.sleep(1) end
            """.trimIndent()
        )
        assertEquals("first", result.get(1).tojstring())
        assertEquals("second", result.get(2).tojstring())
    }

    @Test
    fun `mutex critical sections never overlap`() {
        val result = newEnvAndRun(
            """
            local m = async.mutex()
            local active = 0
            local max_active = 0

            for i = 1, 3 do
                async.task("main", function()
                    m:with(function()
                        active = active + 1
                        if active > max_active then max_active = active end
                        async.sleep(2)
                        active = active - 1
                    end)
                end)
            end

            async.task("main", function()
                async.sleep(20)
                _result = { max_active }
            end)

            while _result == nil do async.sleep(1) end
            """.trimIndent()
        )
        assertEquals(1, result.get(1).toint())
    }

    @Test
    fun `multiple mutexes remain independent`() {
        val result = newEnvAndRun(
            """
            local m1 = async.mutex()
            local m2 = async.mutex()
            local order = {}

            async.task("main", function()
                m1:with(function()
                    table.insert(order, "m1")
                    async.sleep(5)
                end)
            end)

            async.task("main", function()
                async.sleep(1)
                m2:with(function()
                    table.insert(order, "m2")
                end)
            end)

            async.task("main", function()
                while #order < 2 do async.sleep(1) end
                _result = order
            end)

            while _result == nil do async.sleep(1) end
            """.trimIndent()
        )
        assertEquals("m1", result.get(1).tojstring())
        assertEquals("m2", result.get(2).tojstring())
    }

    @Test
    fun `mutex rejects non-function argument`() {
        val result = newEnvAndRun(
            """
            local m = async.mutex()
            local ok, err = pcall(function()
                return m:with("not a function")
            end)
            _result = { ok, err }
            """.trimIndent()
        )
        assertFalse(result.get(1).toboolean())
        assertTrue(result.get(2).tojstring().contains("expected function"))
    }

    @Test
    fun `mutex rejects non-mutex self`() {
        val result = newEnvAndRun(
            """
            local ok, err = pcall(function()
                return ("nope"):with(function() end)
            end)
            _result = { ok, err }
            """.trimIndent()
        )
        assertFalse(result.get(1).toboolean())
    }

    // ── Resumer serialization ────────────────────────────────────────

    @Test
    fun `run repeated on threadpool does not race`() {
        val result = newEnvAndRun(
            """
            local sum = 0
            for i = 1, 50 do
                local v = async.run("threadpool", function()
                    return 1
                end)
                sum = sum + v
            end
            _result = { sum }
            """.trimIndent()
        )
        assertEquals(50, result.get(1).toint())
    }

    @Test
    fun `run repeated on main does not race`() {
        val result = newEnvAndRun(
            """
            local sum = 0
            for i = 1, 50 do
                sum = sum + async.run("main", function()
                    return 1
                end)
            end
            _result = { sum }
            """.trimIndent()
        )
        assertEquals(50, result.get(1).toint())
    }

    @Test
    fun `threadpool task yields and resumes`() {
        val result = newEnvAndRun(
            """
            local t = async.task("threadpool", function()
                async.sleep(1)
                return 5
            end)
            local v = t:wait()
            _result = { v }
            """.trimIndent()
        )
        assertEquals(5, result.get(1).toint())
    }

    @Test
    fun `multiple resume requests for one coroutine`() {
        val result = newEnvAndRun(
            """
            local t = async.task("threadpool", function()
                local p1 = async.promise()
                local p2 = async.promise()
                async.run("threadpool", function()
                    p1:resolve(1)
                    p2:resolve(2)
                end)
                local a = p1:wait()
                local b = p2:wait()
                return a + b
            end)
            local v = t:wait()
            _result = { v }
            """.trimIndent()
        )
        assertEquals(3, result.get(1).toint())
    }

    @Test
    fun `mutex callback yields and resumes`() {
        val result = newEnvAndRun(
            """
            local m = async.mutex()
            local val = m:with(function()
                async.sleep(1)
                return 7
            end)
            _result = { val }
            """.trimIndent()
        )
        assertEquals(7, result.get(1).toint())
    }

    @Test
    fun `contended mutex returns callback values`() {
        val result = newEnvAndRun(
            """
            local m = async.mutex()
            local results = {}

            async.task("main", function()
                local a, b = m:with(function()
                    async.sleep(3)
                    return 1, 2
                end)
                results[1] = a
                results[2] = b
            end)

            async.task("main", function()
                async.sleep(1)
                results[3] = m:with(function()
                    return 99
                end)
            end)

            async.task("main", function()
                while #results < 3 do async.sleep(1) end
                _result = results
            end)

            while _result == nil do async.sleep(1) end
            """.trimIndent()
        )
        assertEquals(1, result.get(1).toint())
        assertEquals(2, result.get(2).toint())
        assertEquals(99, result.get(3).toint())
    }

    @Test
    fun `queued mutex callback errors are propagated`() {
        val result = newEnvAndRun(
            """
            local m = async.mutex()
            local captured = {}

            async.task("main", function()
                m:with(function()
                    async.sleep(2)
                    return "first"
                end)
            end)

            async.task("main", function()
                async.sleep(1)
                local ok, err = pcall(function()
                    return m:with(function()
                        error("queued boom")
                    end)
                end)
                captured[1] = ok
                captured[2] = err
            end)

            async.task("main", function()
                while captured[1] == nil do async.sleep(1) end
                _result = captured
            end)

            while _result == nil do async.sleep(1) end
            """.trimIndent()
        )
        assertFalse(result.get(1).toboolean())
        assertTrue(result.get(2).tojstring().contains("queued boom"))
    }

    @Test
    fun `mutex releases after async callback error`() {
        val result = newEnvAndRun(
            """
            local m = async.mutex()
            local order = {}

            async.task("main", function()
                m:with(function()
                    async.sleep(2)
                    table.insert(order, "first")
                    error("async fail")
                end)
            end)

            async.task("main", function()
                async.sleep(1)
                m:with(function()
                    table.insert(order, "second")
                end)
            end)

            async.task("main", function()
                while #order < 2 do async.sleep(1) end
                _result = order
            end)

            while _result == nil do async.sleep(1) end
            """.trimIndent()
        )
        assertEquals("first", result.get(1).tojstring())
        assertEquals("second", result.get(2).tojstring())
    }

    @Test
    fun `mutex waiters complete in FIFO order`() {
        val result = newEnvAndRun(
            """
            local m = async.mutex()
            local order = {}

            async.task("main", function()
                m:with(function()
                    table.insert(order, 1)
                    async.sleep(3)
                end)
            end)

            async.task("main", function()
                m:with(function()
                    table.insert(order, 2)
                    async.sleep(3)
                end)
            end)

            async.task("main", function()
                m:with(function()
                    table.insert(order, 3)
                    async.sleep(3)
                end)
            end)

            async.task("main", function()
                while #order < 3 do async.sleep(1) end
                _result = order
            end)

            while _result == nil do async.sleep(1) end
            """.trimIndent()
        )
        assertEquals(1, result.get(1).toint())
        assertEquals(2, result.get(2).toint())
        assertEquals(3, result.get(3).toint())
    }

    // ── Resumer hardening ───────────────────────────────────────────

    @Test
    fun `resumer callback failure does not block later resumes`() {
        val state = JsePlatform.standardState()
        val func = state.load("return coroutine.yield(0)", "t").checkfunction()
        val co = LuaThread(state, func)
        val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "pxrp-test-resumer") }
        val registry = AsyncExecutorRegistry()
        registry.register(AsyncExecutor("main", dispatch = { exec.execute(it) }))
        try {
            val executor = registry.resolve("main")
            val failures = CopyOnWriteArrayList<Throwable>()
            val calls = CopyOnWriteArrayList<Int>()
            val throwOnFirst = AtomicBoolean(true)
            val resumer = AsyncLib.SerializedResumer(
                { co.status == "dead" },
                executor,
                resume = { args ->
                    if (throwOnFirst.getAndSet(false)) throw LuaError("callback boom")
                    calls.add(args.arg(1).toint())
                },
                onFailure = { failures.add(it) },
            )
            resumer.requestResume(LuaValue.valueOf(1))
            val deadline = System.currentTimeMillis() + 5000
            while (failures.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(1)
            assertEquals(1, failures.size)
            assertTrue(failures[0].message!!.contains("callback boom"))

            // A later resume request on the same coroutine must be dropped
            // cleanly (not stuck, not re-dispatching the failed callback).
            resumer.requestResume(LuaValue.valueOf(2))
            Thread.sleep(100)
            assertEquals(0, calls.size)
            assertEquals(1, failures.size)
        } finally {
            registry.shutdown()
        }
    }

    @Test
    fun `executor rejects dispatch and task is rejected not hung`() {
        val env = newEnv()
        try {
            env.registry.register(
                AsyncExecutor(
                    name = "closed",
                    dispatch = { throw RejectedExecutionException("executor shut down") },
                )
            )
            runScript(
                env,
                """
                local t = async.task("closed", function() return 1 end)
                _result = { t.state }
                """.trimIndent()
            )
            assertEquals("rejected", env.state.globals.get("_result").checktable().get(1).tojstring())
        } finally {
            env.registry.shutdown()
            env.latch.countDown()
        }
    }

    @Test
    fun `async run on rejected dispatch fails clearly`() {
        val env = newEnv()
        try {
            env.registry.register(
                AsyncExecutor(
                    name = "closed",
                    dispatch = { throw RejectedExecutionException("executor shut down") },
                )
            )
            runScript(
                env,
                """
                local ok, err = pcall(function()
                    return async.run("closed", function() return 1 end)
                end)
                _result = { ok, err }
                """.trimIndent()
            )
            val result = env.state.globals.get("_result").checktable()
            assertFalse(result.get(1).toboolean())
            assertTrue(result.get(2).tojstring().contains("executor shut down"))
        } finally {
            env.registry.shutdown()
            env.latch.countDown()
        }
    }

    @Test
    fun `resumeThread without resume handler fails clearly`() {
        val env = newEnv()
        try {
            val func = env.state.load("return 1", "t").checkfunction()
            val co = LuaThread(env.state, func)
            co.resumeHandler = null
            val err = assertFailsWith<LuaError> {
                env.asyncLib.resumeThread(co, LuaValue.NONE, "test")
            }
            assertTrue(err.message!!.contains("resume handler"))
        } finally {
            env.registry.shutdown()
            env.latch.countDown()
        }
    }

    @Test
    fun `mutex callback failure releases the mutex for a fresh waiter`() {
        val result = newEnvAndRun(
            """
            local m = async.mutex()
            local order = {}

            async.task("main", function()
                local ok = pcall(function()
                    m:with(function()
                        table.insert(order, "fail")
                        error("boom")
                    end)
                end)
                order[1] = not ok
            end)

            async.task("main", function()
                while order[1] == nil do async.sleep(1) end
                local val = m:with(function()
                    return 42
                end)
                order[2] = val
            end)

            async.task("main", function()
                while order[2] == nil do async.sleep(1) end
                _result = order
            end)

            while _result == nil do async.sleep(1) end
            """.trimIndent()
        )
        assertTrue(result.get(1).toboolean())
        assertEquals(42, result.get(2).toint())
    }

    @Test
    fun `mutex completion during parent unwinding does not re-enter parent`() {
        val env = newEnv()
        try {
            env.registry.register(AsyncExecutor("sync", dispatch = { it.run() }))
            val syncExec = env.registry.resolve("sync")
            runScript(
                env,
                """
                local m = async.mutex()
                local val = m:with(function()
                    return 42
                end)
                _result = { val }
                """.trimIndent(),
                contextExecutor = syncExec,
            )
            assertEquals(42, env.state.globals.get("_result").checktable().get(1).toint())
        } finally {
            env.registry.shutdown()
            env.latch.countDown()
        }
    }

    @Test
    fun `multiple queued resume requests remain ordered`() {
        val state = JsePlatform.standardState()
        val func = state.load(
            "local a = coroutine.yield(1); local b = coroutine.yield(2); local c = coroutine.yield(3); return a+b+c",
            "t",
        ).checkfunction()
        val co = LuaThread(state, func)
        val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "pxrp-test-order") }
        val registry = AsyncExecutorRegistry()
        registry.register(AsyncExecutor("main", dispatch = { exec.execute(it) }))
        try {
            val executor = registry.resolve("main")
            val resumed = CopyOnWriteArrayList<Int>()
            val firstEntered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val failures = CopyOnWriteArrayList<Throwable>()
            val resumer = AsyncLib.SerializedResumer(
                { co.status == "dead" },
                executor,
                resume = { args ->
                    resumed.add(args.arg(1).toint())
                    if (resumed.size == 1) {
                        firstEntered.countDown()
                        release.await()
                    }
                    co.resume(args)
                },
                onFailure = { failures.add(it) },
            )
            resumer.requestResume(LuaValue.valueOf(1))
            firstEntered.await()
            resumer.requestResume(LuaValue.valueOf(2))
            resumer.requestResume(LuaValue.valueOf(3))
            release.countDown()

            val deadline = System.currentTimeMillis() + 5000
            while (resumed.size < 3 && System.currentTimeMillis() < deadline) Thread.sleep(1)
            assertEquals(listOf(1, 2, 3), resumed.toList())
            assertTrue(failures.isEmpty())
        } finally {
            registry.shutdown()
        }
    }

    @Test
    fun `registry rejects register and resolve after shutdown`() {
        val registry = AsyncExecutorRegistry()
        val exec = Executors.newSingleThreadExecutor()
        registry.register(AsyncExecutor("main", dispatch = { exec.execute(it) }))
        registry.shutdown()
        try {
            registry.register(AsyncExecutor("late", dispatch = { }))
            error("expected register to fail after shutdown")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("shutdown"))
        }
        val err = assertFailsWith<LuaError> { registry.resolve("main") }
        assertTrue(err.message!!.contains("shut down"))
    }
}
