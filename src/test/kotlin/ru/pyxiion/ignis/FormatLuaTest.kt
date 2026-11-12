package ru.pyxiion.ignis

import org.luaj.vm2.LuaState
import org.luaj.vm2.LuaValue
import org.luaj.vm2.lib.jse.JsePlatform
import kotlin.test.Test
import kotlin.test.assertEquals

class FormatLuaTest {
    private val state: LuaState = JsePlatform.standardState()

    private fun format(): LuaValue {
        val source = javaClass.getResource("/format.lua")!!.readText()
        return state.load(source, "format.lua").call()
    }

    private fun run(chunk: String): LuaValue = state.load(chunk, "t").call()

    @Test
    fun `dotted paths and plain text`() {
        state.globals.set("format", format())
        val r = run("""return format("Hi {p.name}, you have {n}!")({p = {name = "Ann"}, n = 3})""")
        assertEquals("Hi Ann, you have 3!", r.tojstring())
    }

    @Test
    fun `expressions see the arguments`() {
        state.globals.set("format", format())
        val r = run("""return format("{n * 2}/{n + 1}")({n = 5})""")
        assertEquals("10/6", r.tojstring())
    }

    @Test
    fun `each render uses its own arguments`() {
        state.globals.set("format", format())
        run("f = format('{n * 2}')")
        assertEquals("2", run("return f({n = 1})").tojstring())
        assertEquals("8", run("return f({n = 4})").tojstring())
    }

    @Test
    fun `rendering does not compile anything`() {
        state.globals.set("format", format())
        run("f = format('{n * 2} {n}')")
        run("loads = 0; local real = load; load = function(...) loads = loads + 1 return real(...) end")
        repeat(3) { run("return f({n = 1})") }
        assertEquals(0, run("return loads").toint())
    }

    @Test
    fun `template is cached by pattern`() {
        state.globals.set("format", format())
        assertEquals("true", run("return tostring(format('{n * 2}') == format('{n * 2}'))").tojstring())
    }
}
