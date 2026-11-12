package ru.pyxiion.ignis

import org.luaj.vm2.LuaState
import org.luaj.vm2.lib.jse.JsePlatform
import ru.pyxiion.ignis.runtime.restrictGlobals
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SandboxGlobalsTest {
    private fun sandbox(): LuaState = JsePlatform.standardState().also { restrictGlobals(it.globals) }

    private fun run(state: LuaState, src: String) = state.load(src, "@test").call()

    @Test
    fun `string dump is not available`() {
        val state = sandbox()
        assertTrue(run(state, "return string.dump == nil").toboolean())
        assertFalse(run(state, "return pcall(function() return load(string.dump(function() end)) end)").toboolean())
    }

    @Test
    fun `binary chunks are rejected whatever the mode`() {
        val state = sandbox()
        // Без явного режима базовая библиотека разрешает "bt": обёртка обязана свести его к "t".
        val noMode = run(state, "local f = load('\\27Lua\\0', 'x'); return f == nil")
        assertTrue(noMode.toboolean())
        assertFalse(run(state, "return (pcall(load, 'return 1', 'x', 'b'))").toboolean())
        assertFalse(run(state, "return (pcall(load, 'return 1', 'x', 'bt'))").toboolean())
    }

    @Test
    fun `text chunks load with an explicit environment`() {
        val state = sandbox()
        assertEquals(7, run(state, "return load('return x', 'x', 't', {x = 7})()").toint())
        assertEquals(3, run(state, "return load('return 1 + 2')()").toint())
    }
}
