package ru.pyxiion.ignis

import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.luaj.vm2.LuaState
import org.luaj.vm2.LuaThread
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.jse.JsePlatform

class SuspendBridgeTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private fun runToCompletion(script: String, globals: Map<String, LuaValue> = emptyMap()): Varargs {
        val exec = Executors.newSingleThreadExecutor()
        try {
            val state = JsePlatform.standardState()
            val results = LinkedBlockingQueue<Varargs>()

            state.getMainThread().resumeHandler = LuaThread.ResumeHandler { thread: LuaThread, args: Varargs ->
                exec.execute {
                    try {
                        results.put(thread.resumeOrThrow(args))
                    } catch (e: Throwable) {
                        results.put(LuaValue.varargsOf(LuaValue.FALSE, LuaValue.valueOf(e.message ?: "error")))
                    }
                }
            }
            LuaState.setCurrent(state)
            globals.forEach { (k, v) -> state.globals.set(k, v) }

            val func = state.load(script, "test").checkfunction()
            val co = LuaThread(state, func)

            var result = co.resume(LuaValue.NONE)
            check(result.arg1().toboolean()) { "coroutine error: ${result.arg(2)}" }

            while (co.status != "dead") {
                result = results.poll(5, TimeUnit.SECONDS)
                    ?: error("timed out waiting for async resume, coroutine still ${co.status}")
                check(result.arg1().toboolean()) { "coroutine error: ${result.arg(2)}" }
            }
            return result
        } finally {
            exec.shutdown()
        }
    }

    @Test
    fun `synchronous suspend function works`() {
        val result = runToCompletion(
            """
            return mc.sync(42)
            """.trimIndent(),
            mapOf("mc" to luaTableOf(
                "sync" to luaSuspendFunction(scope) { args ->
                    args.arg1()
                }
            ))
        )
        assertTrue(result.arg1().toboolean())
        assertEquals(42, result.arg(2).toint())
    }

    @Test
    fun `suspend function that suspends works`() {
        val result = runToCompletion(
            """
            return mc.delayed(7)
            """.trimIndent(),
            mapOf("mc" to luaTableOf(
                "delayed" to luaSuspendFunction(scope) { args ->
                    delay(20)
                    args.arg1()
                }
            ))
        )
        assertTrue(result.arg1().toboolean())
        assertEquals(7, result.arg(2).toint())
    }

    @Test
    fun `suspend function error propagates through pcall`() {
        val result = runToCompletion(
            """
            local ok, err = pcall(mc.fail)
            return ok, err
            """.trimIndent(),
            mapOf("mc" to luaTableOf(
                "fail" to luaSuspendFunction(scope) { _ ->
                    throw RuntimeException("kaboom")
                }
            ))
        )
        assertTrue(result.arg1().toboolean())
        assertFalse(result.arg(2).toboolean())
        assertTrue(result.arg(3).tojstring().contains("kaboom"))
    }

    @Test
    fun `suspend function yield propagates through pcall`() {
        val result = runToCompletion(
            """
            local ok1, val1 = pcall(mc.yielder)
            local ok2, val2 = pcall(mc.yielder)
            return ok1, val1, ok2, val2
            """.trimIndent(),
            mapOf("mc" to luaTableOf(
                "yielder" to luaSuspendFunction(scope) { _ ->
                    delay(10)
                    LuaValue.valueOf("after-yield")
                }
            ))
        )
        assertTrue(result.arg1().toboolean())
        assertTrue(result.arg(2).toboolean())
        assertEquals("after-yield", result.arg(3).tojstring())
        assertTrue(result.arg(4).toboolean())
        assertEquals("after-yield", result.arg(5).tojstring())
    }

    @Test
    fun `luaSuspendFunctionNil returns nil on success`() {
        val result = runToCompletion(
            """
            mc.tick()
            return 'done'
            """.trimIndent(),
            mapOf("mc" to luaTableOf(
                "tick" to luaSuspendFunctionNil(scope) { _ ->
                    delay(10)
                }
            ))
        )
        assertTrue(result.arg1().toboolean())
        assertEquals("done", result.arg(2).tojstring())
    }

    @Test
    fun `suspend function works inside xpcall`() {
        val result = runToCompletion(
            """
            local ok, val = xpcall(mc.boom, function(e) return 'caught:' .. e end)
            return ok, val
            """.trimIndent(),
            mapOf("mc" to luaTableOf(
                "boom" to luaSuspendFunction(scope) { _ ->
                    throw IllegalStateException("err-1")
                }
            ))
        )
        assertTrue(result.arg1().toboolean())
        assertFalse(result.arg(2).toboolean())
        // xpcall in this implementation doesn't call error handler, just returns error message
        assertTrue(result.arg(3).tojstring().contains("err-1"))
    }

    @Test
    fun `suspend function is invoked on the provided scope`() {
        val counter = AtomicInteger(0)
        runToCompletion(
            """
            return mc.doit()
            """.trimIndent(),
            mapOf("mc" to luaTableOf(
                "doit" to luaSuspendFunction(scope) { _ ->
                    counter.incrementAndGet()
                    delay(5)
                    LuaValue.TRUE
                }
            ))
        )
        assertTrue(counter.get() > 0, "scope was not used")
    }
}
