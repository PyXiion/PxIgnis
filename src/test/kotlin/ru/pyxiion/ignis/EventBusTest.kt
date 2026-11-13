package ru.pyxiion.ignis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ru.pyxiion.luanova.vm2.LuaValue
import ru.pyxiion.luanova.vm2.Varargs
import ru.pyxiion.luanova.vm2.lib.VarArgFunction
import ru.pyxiion.ignis.events.EventCatalog

/** Registration, removal and throttling, on custom (`ns:name`) events of a bus without built-in ones. */
class EventBusTest {
    private val catalog = EventCatalog(emptyList())

    @Test
    fun `on and fire triggers handler`() {
        val bus = EventBus("", catalog)
        var called = false
        bus.on("t:test", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                called = true
                return LuaValue.NIL
            }
        })
        bus.emit("t:test", LuaValue.NONE)
        assertTrue(called)
    }

    @Test
    fun `fire passes arguments to handler`() {
        val bus = EventBus("", catalog)
        var captured: String? = null
        bus.on("t:chat", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                captured = args.arg(1).tojstring()
                return LuaValue.NIL
            }
        })
        bus.emit("t:chat", LuaValue.valueOf("hello"))
        assertEquals("hello", captured)
    }

    @Test
    fun `fire passes multiple arguments`() {
        val bus = EventBus("", catalog)
        val result = mutableListOf<String>()
        bus.on("t:multi", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                for (i in 1..args.narg()) {
                    result.add(args.arg(i).tojstring())
                }
                return LuaValue.NIL
            }
        })
        bus.emit("t:multi", LuaValue.varargsOf(LuaValue.valueOf("a"), LuaValue.valueOf("b"), LuaValue.valueOf("c")))
        assertEquals(listOf("a", "b", "c"), result)
    }

    @Test
    fun `emit with no event args passes nothing`() {
        val bus = EventBus("", catalog)
        var narg = -1
        bus.on("t:ping", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                narg = args.narg()
                return LuaValue.NIL
            }
        })
        bus.emit("t:ping", LuaValue.NONE)
        assertEquals(0, narg)
    }

    @Test
    fun `multiple handlers for same event are all called`() {
        val bus = EventBus("", catalog)
        val order = mutableListOf<Int>()
        bus.on("t:multi", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                order.add(1); return LuaValue.NIL
            }
        })
        bus.on("t:multi", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                order.add(2); return LuaValue.NIL
            }
        })
        bus.emit("t:multi", LuaValue.NONE)
        assertEquals(listOf(1, 2), order)
    }

    @Test
    fun `error in one handler does not prevent others from running`() {
        val bus = EventBus("", catalog)
        var called = false
        bus.on("t:err", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                throw RuntimeException("oops")
            }
        })
        bus.on("t:err", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                called = true; return LuaValue.NIL
            }
        })
        bus.emit("t:err", LuaValue.NONE)
        assertTrue(called)
    }

    @Test
    fun `on returns sequential integer IDs`() {
        val bus = EventBus("", catalog)
        val id1 = bus.on("t:a", object : VarArgFunction() {
            override fun invoke(args: Varargs) = LuaValue.NIL
        })
        val id2 = bus.on("t:b", object : VarArgFunction() {
            override fun invoke(args: Varargs) = LuaValue.NIL
        })
        assertEquals(1, id1)
        assertEquals(2, id2)
    }

    @Test
    fun `off removes handler and returns true`() {
        val bus = EventBus("", catalog)
        var called = 0
        val id = bus.on("t:test", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                called++; return LuaValue.NIL
            }
        })
        bus.emit("t:test", LuaValue.NONE)
        assertEquals(1, called)
        val removed = bus.off(id)
        assertTrue(removed)
        bus.emit("t:test", LuaValue.NONE)
        assertEquals(1, called)
    }

    @Test
    fun `off on unknown ID returns false`() {
        val bus = EventBus("", catalog)
        assertFalse(bus.off(999))
    }

    @Test
    fun `off on same ID twice returns false second time`() {
        val bus = EventBus("", catalog)
        val id = bus.on("t:test", object : VarArgFunction() {
            override fun invoke(args: Varargs) = LuaValue.NIL
        })
        assertTrue(bus.off(id))
        assertFalse(bus.off(id))
    }

    @Test
    fun `throttle prevents handler from firing every call`() {
        val bus = EventBus("", catalog)
        var called = 0
        bus.on("t:test", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                called++; return LuaValue.NIL
            }
        }, HandlerOptions(throttle = 2))
        bus.emit("t:test", LuaValue.NONE) // fires: throttle 2 → set remaining = 2, call
        assertEquals(1, called)
        bus.emit("t:test", LuaValue.NONE) // skipped: remaining = 2 > 0
        assertEquals(1, called)
        bus.emit("t:test", LuaValue.NONE) // skipped: remaining = 1 > 0
        assertEquals(1, called)
        bus.tick()        // remaining → 1
        bus.tick()        // remaining → 0
        bus.emit("t:test", LuaValue.NONE) // fires: remaining = 0, set remaining = 2, call
        assertEquals(2, called)
    }

    @Test
    fun `throttle zero fires every time`() {
        val bus = EventBus("", catalog)
        var called = 0
        bus.on("t:test", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                called++; return LuaValue.NIL
            }
        }, HandlerOptions(throttle = 0))
        bus.emit("t:test", LuaValue.NONE)
        bus.emit("t:test", LuaValue.NONE)
        bus.emit("t:test", LuaValue.NONE)
        assertEquals(3, called)
    }

    @Test
    fun `clear removes all handlers`() {
        val bus = EventBus("", catalog)
        var called = 0
        bus.on("t:test", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                called++; return LuaValue.NIL
            }
        })
        bus.emit("t:test", LuaValue.NONE)
        assertEquals(1, called)
        bus.clear()
        bus.emit("t:test", LuaValue.NONE)
        assertEquals(1, called)
    }

    @Test
    fun `clear resets nextId`() {
        val bus = EventBus("", catalog)
        bus.on("t:a", object : VarArgFunction() {
            override fun invoke(args: Varargs) = LuaValue.NIL
        })
        bus.clear()
        val id = bus.on("t:b", object : VarArgFunction() {
            override fun invoke(args: Varargs) = LuaValue.NIL
        })
        assertEquals(1, id)
    }

    @Test
    fun `hasHandlers returns true after on`() {
        val bus = EventBus("", catalog)
        assertFalse(bus.hasHandlers("t:test"))
        bus.on("t:test", object : VarArgFunction() {
            override fun invoke(args: Varargs) = LuaValue.NIL
        })
        assertTrue(bus.hasHandlers("t:test"))
    }

    @Test
    fun `hasHandlers returns false after off`() {
        val bus = EventBus("", catalog)
        val id = bus.on("t:test", object : VarArgFunction() {
            override fun invoke(args: Varargs) = LuaValue.NIL
        })
        bus.off(id)
        assertFalse(bus.hasHandlers("t:test"))
    }
    private fun handler(block: () -> LuaValue = { LuaValue.NIL }) = object : VarArgFunction() {
        override fun invoke(args: Varargs): Varargs = block()
    }

    @Test
    fun `handler can off itself during fire without skipping neighbours`() {
        val bus = EventBus("", catalog)
        var first = 0
        var second = 0
        var id = 0
        id = bus.on("t:test", handler { first++; bus.off(id); LuaValue.NIL })
        bus.on("t:test", handler { second++; LuaValue.NIL })
        bus.emit("t:test", LuaValue.NONE)
        bus.emit("t:test", LuaValue.NONE)
        assertEquals(1, first)
        assertEquals(2, second)
    }

    @Test
    fun `handler added during fire runs from the next fire`() {
        val bus = EventBus("", catalog)
        var added = 0
        var registered = false
        bus.on("t:test", handler {
            if (!registered) {
                registered = true
                bus.on("t:test", handler { added++; LuaValue.NIL })
            }
            LuaValue.NIL
        })
        bus.emit("t:test", LuaValue.NONE)
        assertEquals(0, added)
        bus.emit("t:test", LuaValue.NONE)
        assertEquals(1, added)
    }

    @Test
    fun `handler removed by a neighbour is not called in the same fire`() {
        val bus = EventBus("", catalog)
        var victimCalls = 0
        var victim = 0
        bus.on("t:test", handler { bus.off(victim); LuaValue.NIL })
        victim = bus.on("t:test", handler { victimCalls++; LuaValue.NIL })
        bus.emit("t:test", LuaValue.NONE)
        assertEquals(0, victimCalls)
    }

}
