package ru.pyxiion.ignis

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.luaj.vm2.LoadState
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaState
import org.luaj.vm2.LuaThread
import org.luaj.vm2.LuaValue
import org.luaj.vm2.compiler.LuaC
import org.luaj.vm2.lib.jse.JseBaseLib
import org.luaj.vm2.lib.jse.JseOsLib
import ru.pyxiion.ignis.runtime.ScriptWatchdog

class ScriptWatchdogTest {
    private var savedTimeout = 0L
    private lateinit var state: LuaState

    @BeforeTest
    fun setUp() {
        savedTimeout = ScriptWatchdog.timeoutMillis
        ScriptWatchdog.timeoutMillis = 100
        ScriptWatchdog.serverThread = Thread.currentThread()
        state = LuaState.builder().checkpointHandler(ScriptWatchdog::checkpoint).build()
        state.globals.load(JseBaseLib())
        state.globals.load(JseOsLib())
        LoadState.install(state)
        LuaC.install(state)
    }

    @AfterTest
    fun tearDown() {
        ScriptWatchdog.serverThread = null
        ScriptWatchdog.timeoutMillis = savedTimeout
    }

    private fun run(code: String): LuaValue = ScriptWatchdog.guard { state.load(code, "test").call() }

    @Test
    fun `infinite loop is stopped`() {
        val e = assertFailsWith<LuaError> { run("while true do end") }
        assertTrue(e.message!!.contains("time limit"), e.message)
    }

    @Test
    fun `pcall cannot swallow the timeout`() {
        assertFailsWith<LuaError> { run("while true do pcall(function() while true do end end) end") }
    }

    @Test
    fun `coroutine resumed through resumeOrLog is stopped`() {
        val thread = LuaThread(state, state.load("while true do end", "test"))
        val r = thread.resumeOrLog(LuaValue.NONE, "test")
        assertEquals(false, r.arg1().toboolean())
    }

    @Test
    fun `next call gets a fresh budget after a timeout`() {
        assertFailsWith<LuaError> { run("while true do end") }
        assertEquals(500500, run("local x = 0 for i = 1, 1000 do x = x + i end return x").toint())
    }

    @Test
    fun `nested guard does not reset the budget`() {
        // Lua -> Kotlin -> Lua: the inner guard must not restart the timer of the outer call.
        state.globals.set("nested", luaVarFunction { args ->
            ScriptWatchdog.guard { args.arg1().call() }
        })
        assertFailsWith<LuaError> { run("while true do nested(function() end) end") }
    }

    @Test
    fun `code on other threads is not limited`() {
        ScriptWatchdog.serverThread = Thread()
        val result = ScriptWatchdog.guard {
            state.load("local t = os.clock() while os.clock() - t < 0.3 do end return 1", "test").call()
        }
        assertEquals(1, result.toint())
    }

    @Test
    fun `unguarded code is not limited`() {
        val r = state.load("local t = os.clock() while os.clock() - t < 0.3 do end return 1", "test").call()
        assertEquals(1, r.toint())
    }
}
