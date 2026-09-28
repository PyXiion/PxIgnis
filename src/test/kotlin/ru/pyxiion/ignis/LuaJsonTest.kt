package ru.pyxiion.ignis

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import ru.pyxiion.ignis.api.util.LuaJson

class LuaJsonTest {

    private fun table(vararg entries: Pair<Any, Any>): LuaTable = LuaTable().apply {
        for ((k, v) in entries) set(lua(k), lua(v))
    }

    private fun lua(v: Any): LuaValue = when (v) {
        is LuaValue -> v
        is Int -> LuaValue.valueOf(v)
        is Double -> LuaValue.valueOf(v)
        is Boolean -> LuaValue.valueOf(v)
        is String -> LuaValue.valueOf(v)
        else -> error("unsupported $v")
    }

    // Hash-part iteration order is unspecified, so compare parsed trees rather than strings.
    private fun assertJson(expected: String, value: LuaValue) =
        assertEquals(JsonParser.parseString(expected), JsonParser.parseString(LuaJson.encode(value)))

    @Test
    fun `sequence encodes as array`() {
        assertEquals("[1,2,3]", LuaJson.encode(table(1 to 1, 2 to 2, 3 to 3)))
    }

    @Test
    fun `empty table encodes as array`() {
        assertEquals("[]", LuaJson.encode(LuaTable()))
    }

    @Test
    fun `string keys encode as object`() {
        assertJson("""{"a":1,"b":true}""", table("a" to 1, "b" to true))
    }

    @Test
    fun `sparse table encodes as object instead of losing data`() {
        assertJson("""{"2":"x"}""", table(2 to "x"))
    }

    @Test
    fun `mixed table encodes as object`() {
        assertJson("""{"1":"a","k":"v"}""", table(1 to "a", "k" to "v"))
    }

    @Test
    fun `fractional numbers keep precision`() {
        assertEquals("1.5", LuaJson.encode(LuaValue.valueOf(1.5)))
    }

    @Test
    fun `nested decode`() {
        val v = LuaJson.decode("""{"list":[1,"two",null,false],"obj":{"x":2.5}}""")
        val list = v.get("list")
        assertEquals(1.0, list.get(1).todouble())
        assertEquals("two", list.get(2).tojstring())
        assertTrue(list.get(3).isnil())
        assertEquals(false, list.get(4).toboolean())
        assertEquals(2.5, v.get("obj").get("x").todouble())
    }

    @Test
    fun `decode empty string yields nil`() {
        assertTrue(LuaJson.decode("").isnil())
    }

    @Test
    fun `round trip`() {
        val json = """{"a":[1,2],"b":{"c":"d"}}"""
        assertJson(json, LuaJson.decode(json))
    }
}
