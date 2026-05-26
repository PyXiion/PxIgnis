package ru.pyxiion.pxrp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.VarArgFunction
import ru.pyxiion.pxrp.api.MetaTableRegistry

class MetaTableRegistryTest {

    @Test
    fun `get player returns LuaTable`() {
        val meta = MetaTableRegistry.get("player")
        assertTrue(meta.istable())
    }

    @Test
    fun `get entity returns LuaTable`() {
        val meta = MetaTableRegistry.get("entity")
        assertTrue(meta.istable())
    }

    @Test
    fun `get world returns LuaTable`() {
        val meta = MetaTableRegistry.get("world")
        assertTrue(meta.istable())
    }

    @Test
    fun `get structure returns LuaTable`() {
        val meta = MetaTableRegistry.get("structure")
        assertTrue(meta.istable())
    }

    @Test
    fun `get returns same instance on repeated calls`() {
        val a = MetaTableRegistry.get("player")
        val b = MetaTableRegistry.get("player")
        assertSame(a, b)
    }

    @Test
    fun `different type names return different tables`() {
        val player = MetaTableRegistry.get("player")
        val entity = MetaTableRegistry.get("entity")
        assertTrue(player !== entity)
    }

    @Test
    fun `get with unknown name throws`() {
        assertFailsWith<IllegalArgumentException> {
            MetaTableRegistry.get("unknown")
        }
    }

    @Test
    fun `can set and get string value`() {
        val meta = MetaTableRegistry.get("player")
        meta.set("mana", LuaValue.valueOf("foo"))
        assertEquals("foo", meta.get("mana").tojstring())
    }

    @Test
    fun `can set and get number value`() {
        val meta = MetaTableRegistry.get("player")
        meta.set("maxMana", LuaValue.valueOf(100.0))
        assertEquals(100.0, meta.get("maxMana").todouble())
    }

    @Test
    fun `unknown key returns nil`() {
        val meta = MetaTableRegistry.get("player")
        assertTrue(meta.get("nonexistent").isnil())
    }

    @Test
    fun `can set and call function`() {
        val meta = MetaTableRegistry.get("player")
        meta.set("greet", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                return LuaValue.valueOf("hello, ${args.arg(1).tojstring()}")
            }
        })

        val fn = meta.get("greet")
        assertTrue(fn.isfunction())
        val result = fn.call(LuaValue.valueOf("world"))
        assertEquals("hello, world", result.tojstring())
    }

    @Test
    fun `method with colon syntax receives self as first arg`() {
        val meta = MetaTableRegistry.get("player")
        meta.set("getName", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val self = args.arg(1)
                val key = self.get("key").tojstring()
                return LuaValue.valueOf("name:$key")
            }
        })

        val fakePlayer = LuaTable()
        fakePlayer.set("key", LuaValue.valueOf("Alex"))

        val wrapper = LuaTable()
        val wrapperMeta = LuaTable()
        wrapperMeta.set("__index", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val key = args.arg(2).tojstring()
                val builtin = fakePlayer.get(key)
                if (!builtin.isnil()) return builtin
                return MetaTableRegistry.get("player").get(key)
            }
        })
        wrapper.setmetatable(wrapperMeta)
        wrapper.set("data", LuaValue.NIL)

        val fn = MetaTableRegistry.get("player").get("getName")
        val result = fn.call(wrapper)
        assertEquals("name:Alex", result.tojstring())
    }

    @Test
    fun `entity metatable does not see player metatable keys`() {
        MetaTableRegistry.get("player").set("playerOnly", LuaValue.valueOf(42))
        val entityMeta = MetaTableRegistry.get("entity")

        assertTrue(entityMeta.get("playerOnly").isnil())
    }

    @Test
    fun `function set before getMetatable is still visible`() {
        val meta = MetaTableRegistry.get("entity")
        meta.set("isAlive", LuaValue.TRUE)

        val same = MetaTableRegistry.get("entity")
        assertTrue(same.get("isAlive").toboolean())
    }

    @Test
    fun `overwriting a metatable key replaces previous value`() {
        val meta = MetaTableRegistry.get("player")
        meta.set("version", LuaValue.valueOf(1))
        meta.set("version", LuaValue.valueOf(2))
        assertEquals(2, meta.get("version").toint())
    }

    @Test
    fun `clearing a metatable key makes it return nil`() {
        val meta = MetaTableRegistry.get("player")
        meta.set("temp", LuaValue.valueOf("abc"))
        assertFalse(meta.get("temp").isnil())

        meta.set("temp", LuaValue.NIL)
        assertTrue(meta.get("temp").isnil())
    }

    @Test
    fun `delegation chain works through fake wrapper`() {
        MetaTableRegistry.get("entity").set("baseHealth", LuaValue.valueOf(20))
        MetaTableRegistry.get("player").set("bonusHealth", LuaValue.valueOf(10))

        val entityMeta = LuaTable()
        entityMeta.set("__index", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val key = args.arg(2).tojstring()
                return when (key) {
                    "type" -> LuaValue.valueOf("entity")
                    else -> MetaTableRegistry.get("entity").get(key)
                }
            }
        })

        val playerMeta = LuaTable()
        playerMeta.set("__index", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val key = args.arg(2).tojstring()
                val playerVal = MetaTableRegistry.get("player").get(key)
                if (!playerVal.isnil()) return playerVal

                val entityVal = MetaTableRegistry.get("entity").get(key)
                if (!entityVal.isnil()) return entityVal

                return when (key) {
                    "name" -> LuaValue.valueOf("Steve")
                    else -> LuaValue.NIL
                }
            }
        })

        val player = LuaTable()
        player.setmetatable(playerMeta)

        assertEquals("Steve", player.get("name").tojstring())
        assertEquals(10, player.get("bonusHealth").toint())
        assertEquals(20, player.get("baseHealth").toint())
        assertTrue(player.get("nonexistent").isnil())
    }

    @Test
    fun `adding function to entity metatable makes it callable on entity-like wrapper`() {
        MetaTableRegistry.get("entity").set("getTypeName", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val self = args.arg(1)
                return self.get("type")
            }
        })

        val objMeta = LuaTable()
        objMeta.set("__index", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val key = args.arg(2).tojstring()
                return when (key) {
                    "type" -> LuaValue.valueOf("zombie")
                    else -> MetaTableRegistry.get("entity").get(key)
                }
            }
        })

        val obj = LuaTable()
        obj.setmetatable(objMeta)

        val fn = obj.get("getTypeName")
        assertTrue(fn.isfunction())
        assertEquals("zombie", fn.call(obj).tojstring())
    }

    @Test
    fun `function can be called with colon syntax on wrapper`() {
        MetaTableRegistry.get("player").set("getData", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val self = args.arg(1)
                val data = self.get("data")
                val key = if (args.narg() >= 2) args.arg(2).tojstring() else ""
                return data.get(key)
            }
        })

        val playerMeta = LuaTable()
        playerMeta.set("__index", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val key = args.arg(2).tojstring()
                val metaVal = MetaTableRegistry.get("player").get(key)
                if (!metaVal.isnil()) return metaVal
                return LuaValue.NIL
            }
        })

        val data = LuaTable()
        data.set("mana", LuaValue.valueOf(50))

        val player = LuaTable()
        player.setmetatable(playerMeta)
        player.set("data", data)

        val fn = player.get("getData")
        val result = fn.call(player, LuaValue.valueOf("mana"))
        assertEquals(50, result.toint())
    }
}
