package ru.pyxiion.ignis

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
        val mc = LuaTable()
        AsyncLib(executor, state, scheduler).install(mc)
        state.globals.set("mc", mc)
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

    // Runs a script in a coroutine. If the coroutine yields (async task pending),
    // releases the latch and waits for the executor to resume it. Returns the
    // `_result` global the script set before completing.
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

    private fun LuaTable.pair(): Pair<Boolean, LuaValue> = get(1).toboolean() to get(2)

    @Test
    fun `task done is false while pending and true when complete`() {
        val env = newEnv()
        try {
            val pending = runScript(
                env,
                """
                local t = mc.task(function() block() return 42 end)
                _result = { t.done }
                """.trimIndent()
            ).checktable()
            assertFalse(pending.get(1).toboolean())

            val done = runScript(
                env,
                """
                local t = mc.task(function() return 42 end)
                while not t.done do end
                _result = { t.done }
                """.trimIndent()
            ).checktable()
            assertTrue(done.get(1).toboolean())
        } finally {
            env.executor.shutdown()
            env.latch.countDown()
        }
    }

    @Test
    fun `pwait sync returns ok and result`() {
        val result = newEnvAndRun(
            """
            local t = mc.task(function() return 42 end)
            while not t.done do end
            local ok, r = t:pwait()
            _result = { ok, r }
            """.trimIndent()
        )
        assertTrue(result.get(1).toboolean())
        assertEquals(42, result.get(2).toint())
    }

    @Test
    fun `pwait async yields and returns ok and result`() {
        val result = newEnvAndRun(
            """
            local t = mc.task(function() block() return 42 end)
            local ok, r = t:pwait()
            _result = { ok, r }
            """.trimIndent()
        )
        assertTrue(result.get(1).toboolean())
        assertEquals(42, result.get(2).toint())
    }

    @Test
    fun `pwait sync returns false and error message`() {
        val result = newEnvAndRun(
            """
            local t = mc.task(function() error("boom") end)
            while not t.done do end
            local ok, r = t:pwait()
            _result = { ok, r }
            """.trimIndent()
        )
        assertFalse(result.get(1).toboolean())
        assertTrue(result.get(2).tojstring().contains("boom"))
    }

    @Test
    fun `pwait async returns false and error message`() {
        val result = newEnvAndRun(
            """
            local t = mc.task(function() block() error("boom") end)
            local ok, r = t:pwait()
            _result = { ok, r }
            """.trimIndent()
        )
        assertFalse(result.get(1).toboolean())
        assertTrue(result.get(2).tojstring().contains("boom"))
    }

    @Test
    fun `wait sync returns raw result`() {
        val result = newEnvAndRun(
            """
            local t = mc.task(function() return 42 end)
            while not t.done do end
            local r = t:wait()
            _result = { r }
            """.trimIndent()
        )
        assertEquals(42, result.get(1).toint())
    }

    @Test
    fun `wait async yields and returns raw result`() {
        val result = newEnvAndRun(
            """
            local t = mc.task(function() block() return 42 end)
            local r = t:wait()
            _result = { r }
            """.trimIndent()
        )
        assertEquals(42, result.get(1).toint())
    }

    @Test
    fun `wait sync throws on task error`() {
        val result = newEnvAndRun(
            """
            local t = mc.task(function() error("boom") end)
            while not t.done do end
            local ok, err = pcall(function() return t:wait() end)
            _result = { ok, err }
            """.trimIndent()
        )
        assertFalse(result.get(1).toboolean())
        assertTrue(result.get(2).tojstring().contains("boom"))
    }

    @Test
    fun `wait async throws on task error`() {
        val result = newEnvAndRun(
            """
            local t = mc.task(function() block() error("boom") end)
            local ok, err = pcall(function() return t:wait() end)
            _result = { ok, err }
            """.trimIndent()
        )
        assertFalse(result.get(1).toboolean())
        assertTrue(result.get(2).tojstring().contains("boom"))
    }

    @Test
    fun `task rejects non-function argument`() {
        val result = newEnvAndRun(
            """
            local ok, err = pcall(mc.task, 42)
            _result = { ok, err }
            """.trimIndent()
        )
        assertFalse(result.get(1).toboolean())
        assertTrue(result.get(2).tojstring().contains("expected function"))
    }

    @Test
    fun `task pwait rejects non-task self`() {
        val result = newEnvAndRun(
            """
            local ok, err = pcall(function() return ("nope"):pwait() end)
            _result = { ok, err }
            """.trimIndent()
        )
        assertFalse(result.get(1).toboolean())
    }

    @Test
    fun `prun yields and returns ok and result`() {
        val result = newEnvAndRun(
            """
            local ok, r = mc.prun(function() block() return 7 end)
            _result = { ok, r }
            """.trimIndent()
        )
        assertTrue(result.get(1).toboolean())
        assertEquals(7, result.get(2).toint())
    }

    @Test
    fun `run sync returns raw result`() {
        val result = newEnvAndRun(
            """
            local r = mc.run(function() return 42 end)
            _result = { r }
            """.trimIndent()
        )
        assertEquals(42, result.get(1).toint())
    }

    @Test
    fun `run async yields and returns raw result`() {
        val result = newEnvAndRun(
            """
            local r = mc.run(function() block() return 42 end)
            _result = { r }
            """.trimIndent()
        )
        assertEquals(42, result.get(1).toint())
    }

    @Test
    fun `run sync throws on error`() {
        val result = newEnvAndRun(
            """
            local ok, err = pcall(function() return mc.run(function() error("boom") end) end)
            _result = { ok, err }
            """.trimIndent()
        )
        assertFalse(result.get(1).toboolean())
        assertTrue(result.get(2).tojstring().contains("boom"))
    }

    @Test
    fun `run async throws on error`() {
        val result = newEnvAndRun(
            """
            local ok, err = pcall(function() return mc.run(function() block() error("boom") end) end)
            _result = { ok, err }
            """.trimIndent()
        )
        assertFalse(result.get(1).toboolean())
        assertTrue(result.get(2).tojstring().contains("boom"))
    }
}
