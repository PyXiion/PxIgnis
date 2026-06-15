package ru.pyxiion.ignis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import ru.pyxiion.ignis.storage.DataBackend
import ru.pyxiion.ignis.storage.DataTable

private class MemoryBackend : DataBackend {
    private val store = mutableMapOf<String, MutableMap<String, Any?>>()

    override fun load(key: String): Map<String, Any?> {
        return store[key]?.toMap() ?: linkedMapOf()
    }

    override fun save(key: String, data: Map<String, Any?>) {
        store[key] = data.toMutableMap()
    }

    override fun close() {}
}

class DataTableTest {

    @Test
    fun `top level set and get survives save reload`() {
        val backend = MemoryBackend()
        val dt1 = DataTable(backend, "test")
        dt1.set(LuaValue.valueOf("name"), LuaValue.valueOf("alice"))
        dt1.set(LuaValue.valueOf("count"), LuaValue.valueOf(42))
        dt1.save()

        val dt2 = DataTable(backend, "test")
        assertEquals("alice", dt2.get(LuaValue.valueOf("name")).tojstring())
        assertEquals(42, dt2.get(LuaValue.valueOf("count")).toint())
    }

    @Test
    fun `nested assignment persists after save and reload`() {
        val backend = MemoryBackend()
        val dt1 = DataTable(backend, "test")
        // Create nested table
        val nested = LuaTable()
        nested.rawset("kills", LuaValue.valueOf(5))
        nested.rawset("deaths", LuaValue.valueOf(3))
        dt1.set(LuaValue.valueOf("stats"), nested)
        dt1.save()

        val dt2 = DataTable(backend, "test")
        val stats = dt2.get(LuaValue.valueOf("stats")).checktable()
        assertEquals(5, stats.get(LuaValue.valueOf("kills")).toint())
        assertEquals(3, stats.get(LuaValue.valueOf("deaths")).toint())
    }

    @Test
    fun `deep nested write via re-assignment survives reload`() {
        val backend = MemoryBackend()
        val dt1 = DataTable(backend, "test")
        val inner = LuaTable()
        inner.rawset("x", LuaValue.valueOf(10))
        inner.rawset("y", LuaValue.valueOf(20))
        val outer = LuaTable()
        outer.rawset("pos", inner)
        dt1.set(LuaValue.valueOf("location"), outer)
        dt1.save()

        val dt2 = DataTable(backend, "test")
        val location = dt2.get(LuaValue.valueOf("location")).checktable()
        val pos = location.get(LuaValue.valueOf("pos")).checktable()
        assertEquals(10, pos.get(LuaValue.valueOf("x")).toint())
        assertEquals(20, pos.get(LuaValue.valueOf("y")).toint())
    }

    @Test
    fun `nested table is a DataTable instance`() {
        val backend = MemoryBackend()
        val dt = DataTable(backend, "test")
        val nested = LuaTable()
        nested.rawset("a", LuaValue.valueOf(1))
        dt.set(LuaValue.valueOf("nested"), nested)

        val retrieved = dt.get(LuaValue.valueOf("nested"))
        assertTrue(retrieved.istable(), "retrieved value should be a table")
        assertTrue(retrieved is DataTable, "nested table should be a DataTable after wrapping")
    }

    @Test
    fun `validation rejects function in nested table`() {
        val backend = MemoryBackend()
        val dt = DataTable(backend, "test")
        val nested = LuaTable()
        // Attempt to store a function value
        val fn = LuaValue.FALSE // need a real function
        assertFailsWith<LuaError> {
            val t = LuaTable()
            t.rawset("fn", object : org.luaj.vm2.lib.ZeroArgFunction() {
                override fun call(): LuaValue = LuaValue.NIL
            })
            dt.set(LuaValue.valueOf("bad"), t)
        }
    }

    @Test
    fun `validation rejects function in deep nested table`() {
        val backend = MemoryBackend()
        val dt = DataTable(backend, "test")
        val inner = LuaTable()
        inner.rawset("fn", object : org.luaj.vm2.lib.ZeroArgFunction() {
            override fun call(): LuaValue = LuaValue.NIL
        })
        val outer = LuaTable()
        outer.rawset("inner", inner)
        assertFailsWith<LuaError> {
            dt.set(LuaValue.valueOf("outer"), outer)
        }
    }

    @Test
    fun `validation detects cyclic reference`() {
        val backend = MemoryBackend()
        val dt = DataTable(backend, "test")
        val t = LuaTable()
        t.rawset("self", t) // cyclic reference
        assertFailsWith<LuaError> {
            dt.set(LuaValue.valueOf("cycle"), t)
        }
    }

    @Test
    fun `read modify write pattern still works`() {
        val backend = MemoryBackend()
        val dt1 = DataTable(backend, "test")
        val stats = LuaTable()
        stats.rawset("kills", LuaValue.valueOf(0))
        dt1.set(LuaValue.valueOf("stats"), stats)
        dt1.save()

        // Load fresh, modify with workaround, save
        val dt2 = DataTable(backend, "test")
        val loaded = dt2.get(LuaValue.valueOf("stats")).checktable()
        loaded.rawset("kills", LuaValue.valueOf(10))
        dt2.set(LuaValue.valueOf("stats"), loaded)
        dt2.save()

        // Verify
        val dt3 = DataTable(backend, "test")
        assertEquals(10, dt3.get(LuaValue.valueOf("stats")).checktable()
            .get(LuaValue.valueOf("kills")).toint())
    }

    @Test
    fun `validation rejects userdata in nested table`() {
        val backend = MemoryBackend()
        val dt = DataTable(backend, "test")
        val nested = LuaTable()
        val userdata = LuaValue.userdataOf("not allowed")
        nested.rawset("data", userdata)
        assertFailsWith<LuaError> {
            dt.set(LuaValue.valueOf("bad"), nested)
        }
    }

    @Test
    fun `allows integer keys for list tables`() {
        val backend = MemoryBackend()
        val dt = DataTable(backend, "test")
        val nested = LuaTable()
        nested.rawset(1, LuaValue.valueOf("a"))
        nested.rawset(2, LuaValue.valueOf("b"))
        dt.set(LuaValue.valueOf("list"), nested)
        dt.save()

        val dt2 = DataTable(backend, "test")
        val retrieved = dt2.get(LuaValue.valueOf("list")).checktable()
        assertEquals("a", retrieved.get(1).tojstring())
        assertEquals("b", retrieved.get(2).tojstring())
    }

    @Test
    fun `list round-trip survives save reload`() {
        val backend = MemoryBackend()
        val dt = DataTable(backend, "test")
        val rooms = LuaTable()
        rooms.rawset(1, LuaValue.valueOf("kitchen"))
        rooms.rawset(2, LuaValue.valueOf("bedroom"))
        rooms.rawset(3, LuaValue.valueOf("bathroom"))
        dt.set(LuaValue.valueOf("rooms"), rooms)
        dt.save()

        val dt2 = DataTable(backend, "test")
        val loaded = dt2.get(LuaValue.valueOf("rooms")).checktable()
        assertEquals(3, loaded.length())
        assertEquals("kitchen", loaded.get(1).tojstring())
        assertEquals("bedroom", loaded.get(2).tojstring())
        assertEquals("bathroom", loaded.get(3).tojstring())
    }

    @Test
    fun `nested list in map survives save reload`() {
        val backend = MemoryBackend()
        val dt = DataTable(backend, "test")
        val floor1 = LuaTable()
        floor1.rawset(1, LuaValue.valueOf("room1"))
        floor1.rawset(2, LuaValue.valueOf("room2"))
        val floor2 = LuaTable()
        floor2.rawset(1, LuaValue.valueOf("room3"))
        val building = LuaTable()
        building.rawset("floor1", floor1)
        building.rawset("floor2", floor2)
        dt.set(LuaValue.valueOf("building"), building)
        dt.save()

        val dt2 = DataTable(backend, "test")
        val loaded = dt2.get(LuaValue.valueOf("building")).checktable()
        val f1 = loaded.get(LuaValue.valueOf("floor1")).checktable()
        val f2 = loaded.get(LuaValue.valueOf("floor2")).checktable()
        assertEquals(2, f1.length())
        assertEquals("room1", f1.get(1).tojstring())
        assertEquals("room2", f1.get(2).tojstring())
        assertEquals(1, f2.length())
        assertEquals("room3", f2.get(1).tojstring())
    }

    @Test
    fun `modifying list element after reload persists`() {
        val backend = MemoryBackend()
        val dt = DataTable(backend, "test")
        val rooms = LuaTable()
        rooms.rawset(1, LuaValue.valueOf("old"))
        dt.set(LuaValue.valueOf("rooms"), rooms)
        dt.save()

        val dt2 = DataTable(backend, "test")
        val loaded = dt2.get(LuaValue.valueOf("rooms")).checktable()
        loaded.rawset(1, LuaValue.valueOf("new"))
        dt2.set(LuaValue.valueOf("rooms"), loaded)
        dt2.save()

        val dt3 = DataTable(backend, "test")
        assertEquals("new", dt3.get(LuaValue.valueOf("rooms")).checktable()
            .get(1).tojstring())
    }
}
