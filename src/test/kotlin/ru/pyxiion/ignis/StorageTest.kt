package ru.pyxiion.ignis

import com.google.gson.JsonParser
import ru.pyxiion.luanova.vm2.LuaError
import ru.pyxiion.luanova.vm2.LuaTable
import ru.pyxiion.luanova.vm2.LuaValue
import ru.pyxiion.luanova.vm2.lib.jse.JsePlatform
import ru.pyxiion.ignis.storage.JsonBackend
import ru.pyxiion.ignis.storage.LuaJson
import ru.pyxiion.ignis.storage.StorageManager
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StorageTest {
    private val state = JsePlatform.standardState()

    private fun lua(src: String): LuaTable = state.load("return $src", "t").call().checktable()

    private fun roundTrip(table: LuaTable): LuaTable =
        LuaJson.fromJson(JsonParser.parseString(LuaJson.toJson(table).toString())).checktable()

    private fun tempDir(): Path = Files.createTempDirectory("ignis-storage")

    // ── codec ──────────────────────────────────────────────────────

    @Test
    fun `numeric looking string stays a string`() {
        val back = roundTrip(lua("{s = '0042'}"))
        assertEquals(LuaValue.TSTRING, back.get("s").type())
        assertEquals("0042", back.get("s").tojstring())
    }

    @Test
    fun `large integral number keeps its value`() {
        val back = roundTrip(lua("{n = 2^40}"))
        assertEquals(1099511627776.0, back.get("n").todouble())
        assertEquals("1099511627776", LuaJson.toJson(lua("{n = 2^40}")).asJsonObject.get("n").toString())
    }

    @Test
    fun `int and fractional numbers`() {
        val json = LuaJson.toJson(lua("{i = 7, f = 0.5}")).asJsonObject
        assertEquals("7", json.get("i").toString())
        assertEquals("0.5", json.get("f").toString())
    }

    @Test
    fun `table with a single integer key is an object not a list`() {
        val json = LuaJson.toJson(lua("{[1001] = 1}"))
        assertTrue(json.isJsonObject)
        val back = roundTrip(lua("{[1001] = 1}"))
        assertEquals(1, back.get(1001).toint())
        assertTrue(back.get("1001").isnil())
    }

    @Test
    fun `sparse list is an object`() {
        assertTrue(LuaJson.toJson(lua("{[1] = 'a', [3] = 'c'}")).isJsonObject)
    }

    @Test
    fun `lists nest and keep order`() {
        val json = LuaJson.toJson(lua("{{1, 2}, {'a', {true}}}"))
        assertEquals("[[1,2],[\"a\",[true]]]", json.toString())
        val back = roundTrip(lua("{{1, 2}, {'a', {true}}}"))
        assertTrue(back.get(2).get(2).get(1).toboolean())
    }

    @Test
    fun `empty table is an object`() {
        assertTrue(LuaJson.toJson(LuaTable()).isJsonObject)
    }

    @Test
    fun `function error carries the path`() {
        val data = lua("{shop = {items = {1, 2}}}")
        data.get("shop").get("items").set(3, state.load("return 1", "f"))
        val e = assertFailsWith<LuaError> { LuaJson.toJson(data, "data") }
        assertEquals("data.shop.items[3]: function", e.message)
    }

    @Test
    fun `cycle is an error with path`() {
        val data = LuaTable()
        data.set("self", data)
        val e = assertFailsWith<LuaError> { LuaJson.toJson(data) }
        assertTrue(e.message!!.startsWith("data.self: cyclic"))
    }

    @Test
    fun `same table twice is not a cycle`() {
        val shared = lua("{1}")
        val data = LuaTable()
        data.set("a", shared)
        data.set("b", shared)
        assertEquals("{\"a\":[1],\"b\":[1]}", LuaJson.toJson(data).toString().replace(Regex("\\s"), ""))
    }

    @Test
    fun `non integer key is rejected`() {
        val data = LuaTable()
        data.set(LuaValue.valueOf(1.5), LuaValue.valueOf(1))
        assertFailsWith<LuaError> { LuaJson.toJson(data) }
    }

    @Test
    fun `nan is rejected`() {
        val e = assertFailsWith<LuaError> { LuaJson.toJson(lua("{x = 0/0}")) }
        assertTrue(e.message!!.startsWith("data.x"))
    }

    // ── manager ────────────────────────────────────────────────────

    @Test
    fun `data survives save and reload`() {
        val dir = tempDir()
        val a = StorageManager(JsonBackend(dir))
        a.getGlobalData().set("name", "alice")
        a.getGlobalData().set("stats", lua("{kills = 5, tags = {'x', 'y'}}"))
        a.saveAll()

        val b = StorageManager(JsonBackend(dir))
        assertEquals("alice", b.getGlobalData().get("name").tojstring())
        assertEquals(5, b.getGlobalData().get("stats").get("kills").toint())
        assertEquals("y", b.getGlobalData().get("stats").get("tags").get(2).tojstring())
    }

    @Test
    fun `failing table does not stop the others`() {
        val dir = tempDir()
        val m = StorageManager(JsonBackend(dir))
        val bad = m.getPlayerData("bad")
        bad.set("list", lua("{1}"))
        bad.get("list").set(2, state.load("return 1", "f"))
        m.getPlayerData("good").set("n", 1)
        m.saveAll()
        assertTrue(dir.resolve("players/good.json").exists())
        assertFalse(dir.resolve("players/bad.json").exists())
    }

    @Test
    fun `corrupt file is renamed and not overwritten`() {
        val dir = tempDir()
        Files.createDirectories(dir.resolve("players"))
        val file = dir.resolve("players/p.json")
        file.writeText("{ not json")
        val m = StorageManager(JsonBackend(dir))
        assertTrue(m.getPlayerData("p").next(LuaValue.NIL).arg1().isnil())
        assertFalse(file.exists())
        val corrupt = dir.resolve("players").listDirectoryEntries().single { it.name.startsWith("p.json.corrupt-") }
        assertEquals("{ not json", corrupt.readText())
    }

    @Test
    fun `unchanged table is not rewritten`() {
        val dir = tempDir()
        val m = StorageManager(JsonBackend(dir))
        m.getGlobalData().set("a", 1)
        m.saveAll()
        val file = dir.resolve("global.json")
        Files.delete(file)
        m.saveAll()
        assertFalse(file.exists())
        m.getGlobalData().set("a", 2)
        m.saveAll()
        assertTrue(file.exists())
    }

    @Test
    fun `removePlayerData saves and forgets`() {
        val dir = tempDir()
        val m = StorageManager(JsonBackend(dir))
        m.getPlayerData("u").set("k", "v")
        m.removePlayerData("u")
        assertEquals("v", StorageManager(JsonBackend(dir)).getPlayerData("u").get("k").tojstring())
    }

    @Test
    fun `top level table with list keys is saved as an object`() {
        val dir = tempDir()
        val m = StorageManager(JsonBackend(dir))
        m.getGlobalData().set(1, "a")
        m.getGlobalData().set(2, "b")
        m.saveAll()
        assertEquals("b", StorageManager(JsonBackend(dir)).getGlobalData().get(2).tojstring())
    }
}
