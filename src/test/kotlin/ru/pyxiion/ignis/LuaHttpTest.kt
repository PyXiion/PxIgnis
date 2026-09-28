package ru.pyxiion.ignis

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import ru.pyxiion.ignis.api.LuaHttp

class LuaHttpTest {

    @BeforeTest
    fun setUp() = LuaHttp.resetResponseMeta()

    private fun options(vararg entries: Pair<String, LuaValue>) = LuaTable().apply {
        for ((k, v) in entries) set(k, v)
    }

    @Test
    fun `string argument is a GET with default timeout`() {
        val request = LuaHttp.buildRequest(LuaHttp.parseRequest(LuaValue.valueOf("http://example.com")))
        assertEquals("GET", request.method())
        assertEquals(10L, request.timeout().get().seconds)
    }

    @Test
    fun `json option encodes body and sets content type`() {
        val config = LuaHttp.parseRequest(
            options(
                "url" to LuaValue.valueOf("http://example.com"),
                "method" to LuaValue.valueOf("post"),
                "json" to options("a" to LuaValue.valueOf(1)),
            )
        )
        assertEquals("""{"a":1}""", config.body)
        assertEquals("application/json", config.headers["content-type"])
        assertEquals("POST", LuaHttp.buildRequest(config).method())
    }

    @Test
    fun `explicit content type is kept`() {
        val config = LuaHttp.parseRequest(
            options(
                "url" to LuaValue.valueOf("http://example.com"),
                "headers" to options("Content-Type" to LuaValue.valueOf("text/plain")),
                "json" to options("a" to LuaValue.valueOf(1)),
            )
        )
        assertEquals("text/plain", config.headers["content-type"])
    }

    @Test
    fun `body and json are mutually exclusive`() {
        assertFailsWith<LuaError> {
            LuaHttp.parseRequest(
                options(
                    "url" to LuaValue.valueOf("http://example.com"),
                    "body" to LuaValue.valueOf("raw"),
                    "json" to LuaTable(),
                )
            )
        }
    }

    @Test
    fun `invalid url is a lua error`() {
        assertFailsWith<LuaError> { LuaHttp.parseRequest(LuaValue.valueOf("http://bad host")) }
    }

    @Test
    fun `response exposes status headers and lazily parsed json`() {
        val r = LuaHttp.buildResponse(200, """{"k":[1,2]}""", mapOf("x-h" to listOf("v")))
        assertTrue(r.get("ok").toboolean())
        assertEquals(200, r.get("status").toint())
        assertEquals("v", r.get("headers").get("x-h").tojstring())
        assertEquals(2, r.get("json").get("k").get(2).toint())
        assertSame(r.get("json"), r.get("json"))
    }

    @Test
    fun `invalid json body errors only when accessed`() {
        val r = LuaHttp.buildResponse(500, "not json", emptyMap())
        assertFalse(r.get("ok").toboolean())
        assertEquals("not json", r.get("text").tojstring())
        assertFailsWith<LuaError> { r.get("json") }
    }

    @Test
    fun `error response carries the message`() {
        val r = LuaHttp.buildError(RuntimeException("boom"))
        assertFalse(r.get("ok").toboolean())
        assertEquals("boom", r.get("error").tojstring())
    }
}
