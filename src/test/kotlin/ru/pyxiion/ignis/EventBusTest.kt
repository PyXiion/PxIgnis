package ru.pyxiion.ignis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.VarArgFunction
import org.slf4j.LoggerFactory
import ru.pyxiion.ignis.api.MetaTableRegistry

class EventBusTest {
    private val logger = LoggerFactory.getLogger("test")

    @Test
    fun `on and fire triggers handler`() {
        val bus = EventBus("", logger)
        var called = false
        bus.on("test", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                called = true
                return LuaValue.NIL
            }
        })
        bus.fire("test")
        assertTrue(called)
    }

    @Test
    fun `fire passes arguments to handler`() {
        val bus = EventBus("", logger)
        var captured: String? = null
        bus.on("chat", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                captured = args.arg(1).tojstring()
                return LuaValue.NIL
            }
        })
        bus.fire("chat", LuaValue.valueOf("hello"))
        assertEquals("hello", captured)
    }

    @Test
    fun `fire passes multiple arguments`() {
        val bus = EventBus("", logger)
        val result = mutableListOf<String>()
        bus.on("multi", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                for (i in 1..args.narg()) {
                    result.add(args.arg(i).tojstring())
                }
                return LuaValue.NIL
            }
        })
        bus.fire("multi", LuaValue.valueOf("a"), LuaValue.valueOf("b"), LuaValue.valueOf("c"))
        assertEquals(listOf("a", "b", "c"), result)
    }

    @Test
    fun `fire with no event args passes only event name`() {
        val bus = EventBus("", logger)
        var narg = -1
        bus.on("ping", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                narg = args.narg()
                return LuaValue.NIL
            }
        })
        bus.fire("ping")
        assertEquals(0, narg)
    }

    @Test
    fun `multiple handlers for same event are all called`() {
        val bus = EventBus("", logger)
        val order = mutableListOf<Int>()
        bus.on("multi", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                order.add(1); return LuaValue.NIL
            }
        })
        bus.on("multi", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                order.add(2); return LuaValue.NIL
            }
        })
        bus.fire("multi")
        assertEquals(listOf(1, 2), order)
    }

    @Test
    fun `error in one handler does not prevent others from running`() {
        val bus = EventBus("", logger)
        var called = false
        bus.on("err", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                throw RuntimeException("oops")
            }
        })
        bus.on("err", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                called = true; return LuaValue.NIL
            }
        })
        bus.fire("err")
        assertTrue(called)
    }

    @Test
    fun `on returns sequential integer IDs`() {
        val bus = EventBus("", logger)
        val id1 = bus.on("a", object : VarArgFunction() {
            override fun invoke(args: Varargs) = LuaValue.NIL
        })
        val id2 = bus.on("b", object : VarArgFunction() {
            override fun invoke(args: Varargs) = LuaValue.NIL
        })
        assertEquals(1, id1)
        assertEquals(2, id2)
    }

    @Test
    fun `off removes handler and returns true`() {
        val bus = EventBus("", logger)
        var called = 0
        val id = bus.on("test", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                called++; return LuaValue.NIL
            }
        })
        bus.fire("test")
        assertEquals(1, called)
        val removed = bus.off(id)
        assertTrue(removed)
        bus.fire("test")
        assertEquals(1, called)
    }

    @Test
    fun `off on unknown ID returns false`() {
        val bus = EventBus("", logger)
        assertFalse(bus.off(999))
    }

    @Test
    fun `off on same ID twice returns false second time`() {
        val bus = EventBus("", logger)
        val id = bus.on("test", object : VarArgFunction() {
            override fun invoke(args: Varargs) = LuaValue.NIL
        })
        assertTrue(bus.off(id))
        assertFalse(bus.off(id))
    }

    @Test
    fun `throttle prevents handler from firing every call`() {
        val bus = EventBus("", logger)
        var called = 0
        bus.on("test", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                called++; return LuaValue.NIL
            }
        }, throttle = 2)
        bus.fire("test") // fires: throttle 2 → set remaining = 2, call
        assertEquals(1, called)
        bus.fire("test") // skipped: remaining = 2 > 0
        assertEquals(1, called)
        bus.fire("test") // skipped: remaining = 1 > 0
        assertEquals(1, called)
        bus.tick()        // remaining → 1
        bus.tick()        // remaining → 0
        bus.fire("test") // fires: remaining = 0, set remaining = 2, call
        assertEquals(2, called)
    }

    @Test
    fun `throttle zero fires every time`() {
        val bus = EventBus("", logger)
        var called = 0
        bus.on("test", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                called++; return LuaValue.NIL
            }
        }, throttle = 0)
        bus.fire("test")
        bus.fire("test")
        bus.fire("test")
        assertEquals(3, called)
    }

    @Test
    fun `clear removes all handlers`() {
        val bus = EventBus("", logger)
        var called = 0
        bus.on("test", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                called++; return LuaValue.NIL
            }
        })
        bus.fire("test")
        assertEquals(1, called)
        bus.clear()
        bus.fire("test")
        assertEquals(1, called)
    }

    @Test
    fun `clear resets nextId`() {
        val bus = EventBus("", logger)
        bus.on("a", object : VarArgFunction() {
            override fun invoke(args: Varargs) = LuaValue.NIL
        })
        bus.clear()
        val id = bus.on("b", object : VarArgFunction() {
            override fun invoke(args: Varargs) = LuaValue.NIL
        })
        assertEquals(1, id)
    }

    @Test
    fun `hasHandlers returns true after on`() {
        val bus = EventBus("", logger)
        assertFalse(bus.hasHandlers("test"))
        bus.on("test", object : VarArgFunction() {
            override fun invoke(args: Varargs) = LuaValue.NIL
        })
        assertTrue(bus.hasHandlers("test"))
    }

    @Test
    fun `hasHandlers returns false after off`() {
        val bus = EventBus("", logger)
        val id = bus.on("test", object : VarArgFunction() {
            override fun invoke(args: Varargs) = LuaValue.NIL
        })
        bus.off(id)
        assertFalse(bus.hasHandlers("test"))
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
