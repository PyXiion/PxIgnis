package ru.pyxiion.ignis

import org.luaj.vm2.LuaState
import org.luaj.vm2.Varargs
import ru.pyxiion.ignis.runtime.installStdlib
import ru.pyxiion.ignis.runtime.installStrictGlobals
import ru.pyxiion.ignis.sandbox.Vfs
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StrictGlobalsTest {

    private fun run(script: String): Varargs {
        val state = LuaState()
        installStdlib(state, Vfs(listOf(Files.createTempDirectory("ignis"))))
        installStrictGlobals(state.globals)
        LuaState.setCurrent(state)
        return state.load(script, "test").checkfunction().invoke()
    }

    @Test
    fun `reading an undefined global is an error`() {
        val r = run("return pcall(function() return undefined_name end)")
        assertFalse(r.arg(1).toboolean())
        assertTrue(r.arg(2).tojstring().contains("global 'undefined_name' is not defined"), r.arg(2).tojstring())
    }

    @Test
    fun `assigned globals can be read`() {
        val r = run("function handler() return 1 end; counter = 5; return handler() + counter")
        assertEquals(6, r.arg(1).toint())
    }

    @Test
    fun `rawget probes for an optional global`() {
        assertTrue(run("return rawget(_G, 'undefined_name')").arg(1).isnil())
    }

    @Test
    fun `stdlib globals are untouched`() {
        val r = run("return type(string.format), type(vec)")
        assertEquals("function", r.arg(1).tojstring())
        assertEquals("function", r.arg(2).tojstring())
    }
}
