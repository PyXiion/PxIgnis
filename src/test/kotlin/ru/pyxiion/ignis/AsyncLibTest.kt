package ru.pyxiion.ignis

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.luaj.vm2.LuaState
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaThread
import org.luaj.vm2.LuaValue
import org.luaj.vm2.lib.jse.JsePlatform
import ru.pyxiion.ignis.api.AsyncLib

class AsyncLibTest {

    private class Env(
        val state: LuaState,
        val executor: ExecutorService,
        val latch: CountDownLatch
    )

    private fun newEnv(): Env {
        val state = JsePlatform.standardState()
        val executor = Executors.newSingleThreadExecutor()
        val latch = CountDownLatch(1)
        val scheduler = Scheduler { state }
        val asyncLib = AsyncLib(executor, state, scheduler)
        state.globals.set("async", asyncLib.buildModule())
        state.globals.set("block", luaFunctionNil { _ ->
            try {
                latch.await()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        })
        state.globals.set("_result", LuaValue.NIL)
        LuaState.setCurrent(state)
        return Env(state, executor, latch)
    }

    private fun runScript(env: Env, script: String): LuaValue {
        val func = env.state.load(script, "test").checkfunction()
        val co = LuaThread(env.state, func)
        val result = co.resume(LuaValue.NONE)
        check(result.arg1().toboolean()) { "coroutine error: ${result.arg(2)}" }

        if (co.status != "dead") {
            env.latch.countDown()
            val deadline = System.currentTimeMillis() + 5000
            while (co.status != "dead") {
                if (System.currentTimeMillis() > deadline) {
                    error("coroutine did not complete: status=${co.status}")
                }
                Thread.sleep(5)
            }
        }
        return env.state.globals.get("_result")
    }

    private fun newEnvAndRun(script: String): LuaTable {
        val env = newEnv()
        try {
            return runScript(env, script).checktable()
        } finally {
            env.executor.shutdown()
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
                local t = async.task(function() block() return 42 end)
                _result = { t.done }
                """.trimIndent()
            ).checktable()
            assertFalse(pending.get(1).toboolean())
        } finally {
            env.executor.shutdown()
            env.latch.countDown()
        }
    }

    @Test
    fun `task done is true when complete`() {
        val done = newEnvAndRun(
            """
            local t = async.task(function() return 42 end)
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
            local t = async.task(function() return 42 end)
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
            local t = async.task(function() block() return 42 end)
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
            local t = async.task(function() error("boom") end)
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
            local t = async.task(function() block() error("boom") end)
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
            local t = async.task(function() return 42 end)
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
            local t = async.task(function() block() return 42 end)
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
            local t = async.task(function() error("boom") end)
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
            local t = async.task(function() block() error("boom") end)
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
            local ok, err = pcall(async.task, 42)
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
            local t1 = async.task(function() return 1 end)
            local t2 = async.task(function() return 2 end)
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
            local t1 = async.task(function() return 1 end)
            local t2 = async.task(function() error("boom") end)
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
            local t1 = async.task(function() return 1 end)
            local t2 = async.task(function() error("boom") end)
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
}
