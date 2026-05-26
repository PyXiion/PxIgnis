package ru.pyxiion.pxrp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.VarArgFunction
import ru.pyxiion.pxrp.api.MetaTableRegistry

class EventManagerTest {

    @Test
    fun `on and emit triggers handler`() {
        val em = LuaEventManager()
        var called = false
        em.on("test", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                called = true
                return LuaValue.NIL
            }
        })
        em.fire("test")
        assertTrue(called)
    }

    @Test
    fun `emit passes arguments to handler`() {
        val em = LuaEventManager()
        var captured: String? = null
        em.on("chat", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                captured = args.arg(1).tojstring()
                return LuaValue.NIL
            }
        })
        em.fire("chat", LuaValue.valueOf("hello"))
        assertEquals("hello", captured)
    }

    @Test
    fun `emit passes multiple arguments`() {
        val em = LuaEventManager()
        var result = mutableListOf<String>()
        em.on("multi", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                for (i in 1..args.narg()) {
                    result.add(args.arg(i).tojstring())
                }
                return LuaValue.NIL
            }
        })
        em.fire("multi", LuaValue.valueOf("a"), LuaValue.valueOf("b"), LuaValue.valueOf("c"))
        assertEquals(listOf("a", "b", "c"), result)
    }

    @Test
    fun `emit with no event args passes only event name`() {
        val em = LuaEventManager()
        var narg = -1
        em.on("ping", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                narg = args.narg()
                return LuaValue.NIL
            }
        })
        em.fire("ping")
        assertEquals(0, narg)
    }

    @Test
    fun `multiple handlers for same event are all called`() {
        val em = LuaEventManager()
        val order = mutableListOf<Int>()
        em.on("multi", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                order.add(1); return LuaValue.NIL
            }
        })
        em.on("multi", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                order.add(2); return LuaValue.NIL
            }
        })
        em.fire("multi")
        assertEquals(listOf(1, 2), order)
    }

    @Test
    fun `error in one handler does not prevent others from running`() {
        val em = LuaEventManager()
        var called = false
        em.on("err", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                throw RuntimeException("oops")
            }
        })
        em.on("err", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                called = true; return LuaValue.NIL
            }
        })
        em.fire("err")
        assertTrue(called)
    }

    @Test
    fun `players can communicate through global metatable`() {
        val playerMeta = MetaTableRegistry.get("player")
        playerMeta.set("mana", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val self = args.arg(1)
                return self.get("data").get("mana")
            }
        })

        val wrapperMeta = LuaTable()
        wrapperMeta.set("__index", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val key = args.arg(2).tojstring()
                val metaVal = MetaTableRegistry.get("player").get(key)
                if (!metaVal.isnil()) return metaVal
                return LuaValue.NIL
            }
        })

        val data = LuaTable()
        data.set("mana", LuaValue.valueOf(42))

        val player = LuaTable()
        player.setmetatable(wrapperMeta)
        player.set("data", data)

        assertEquals(42, player.get("mana").call(player).toint())
    }
}
