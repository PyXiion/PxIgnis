package ru.pyxiion.ignis

import kotlin.test.Test
import kotlin.test.assertEquals
import ru.pyxiion.luanova.vm2.LuaState
import ru.pyxiion.luanova.vm2.LuaValue
import ru.pyxiion.luanova.vm2.lib.jse.JsePlatform
import ru.pyxiion.ignis.events.DeathDrops
import ru.pyxiion.ignis.events.EventCatalog
import ru.pyxiion.ignis.events.EventDef
import ru.pyxiion.ignis.events.GlobalEvents
import ru.pyxiion.ignis.runtime.ScriptErrors

/** `entity.drops`: a cancel covers the loot path, `e.xp` covers the XP path; driven from Lua through a real bus. */
class DeathDropsTest {
    private val state: LuaState = JsePlatform.standardState()
    private val bus = EventBus("", EventCatalog(listOf(EventDef("entity.drops", true))), { state })

    init {
        ScriptErrors.resetRateLimit()
        state.globals.set("on", luaVarFunction { args ->
            val (fn, opts) = HandlerOptions.handlerAndOptions(args.arg(2), args.arg(3), "on")
            LuaValue.valueOf(bus.on(args.checkjstring(1), fn, opts))
        })
    }

    private fun drops(vanilla: Int = 5): Int {
        val r = bus.post("entity.drops") { it.rawset("xp", LuaValue.valueOf(vanilla.toDouble())) }
        return DeathDrops.xp(r, vanilla)
    }

    @Test
    fun `entity drops is a cancellable built-in event`() {
        assertEquals(true, GlobalEvents.CATALOG.def("entity.drops")?.cancellable)
    }

    @Test
    fun `no handler leaves vanilla loot and xp`() {
        assertEquals(5, drops())
    }

    @Test
    fun `loot path - cancel drops items equipment and xp`() {
        state.load("""on("entity.drops", function(e) e:cancel() end)""", "t").call()
        assertEquals(DeathDrops.CANCELLED, drops())
    }

    @Test
    fun `xp path - xp zero keeps loot but drops no xp`() {
        state.load("""on("entity.drops", function(e) e.xp = 0 end)""", "t").call()
        assertEquals(0, drops())
    }

    @Test
    fun `xp can be replaced`() {
        state.load("""on("entity.drops", function(e) e.xp = 12.7 end)""", "t").call()
        assertEquals(12, drops())
    }

    @Test
    fun `non-number xp keeps vanilla`() {
        state.load("""on("entity.drops", function(e) e.xp = "lots" end)""", "t").call()
        assertEquals(5, drops())
    }
}
