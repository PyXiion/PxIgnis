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
import ru.pyxiion.ignis.events.DropBuffer
import ru.pyxiion.luanova.vm2.LuaTable
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    // --- the editable list e.drops: items are {name = ...} tables here, strings after unwrap ---------------------

    private val rejected = ArrayList<String>()

    private fun item(name: String) = LuaTable().apply { rawset("name", LuaValue.valueOf(name)) }

    private fun unwrap(v: LuaValue): String? =
        if (v.istable()) v.rawget("name").takeIf { it.isstring() }?.tojstring() else null

    private fun outcome(vararg vanilla: String, xp: Int = 5): DeathDrops.Outcome<String>? {
        val stacks = vanilla.toList()
        val r = bus.post("entity.drops") {
            it.rawset("xp", LuaValue.valueOf(xp.toDouble()))
            it.rawset("drops", LuaTable().also { t -> stacks.forEachIndexed { i, n -> t.set(i + 1, item(n)) } })
        }
        return DeathDrops.outcome(r, xp, stacks, ::unwrap) { rejected.add(it.tojstring()) }
    }

    private fun on(code: String) {
        state.load("""
            local function item(name) return { name = name } end
            on("entity.drops", function(e) $code end)
        """.trimIndent(), "t").call()
    }

    @Test
    fun `no handler spawns exactly the vanilla stacks`() {
        val o = outcome("a", "b", "c")!!
        assertEquals(listOf("a", "b", "c"), o.items)
        assertEquals(5, o.xp)
    }

    @Test
    fun `handler sees the vanilla stacks in order`() {
        on("""e.xp = #e.drops * 10 + (e.drops[2].name == "b" and 1 or 0)""")
        assertEquals(31, outcome("a", "b", "c")!!.xp)
    }

    @Test
    fun `entries can be removed`() {
        on("table.remove(e.drops, 2)")
        assertEquals(listOf("a", "c"), outcome("a", "b", "c")!!.items)
    }

    @Test
    fun `entries can be replaced`() {
        on("""e.drops[1] = item("x")""")
        assertEquals(listOf("x", "b"), outcome("a", "b")!!.items)
    }

    @Test
    fun `entries can be appended`() {
        on("""table.insert(e.drops, item("extra"))""")
        assertEquals(listOf("a", "extra"), outcome("a")!!.items)
        assertEquals(listOf("extra"), outcome()!!.items)
    }

    @Test
    fun `remove, replace and append together keep list order`() {
        on("""table.remove(e.drops, 1); e.drops[1] = item("y"); table.insert(e.drops, item("z"))""")
        assertEquals(listOf("y", "z"), outcome("a", "b")!!.items)
    }

    @Test
    fun `an empty list drops no items but keeps the xp`() {
        on("e.drops = {}")
        val o = outcome("a", "b")!!
        assertEquals(emptyList(), o.items)
        assertEquals(5, o.xp)
    }

    @Test
    fun `erasing the list drops no items`() {
        on("e.drops = nil")
        assertEquals(emptyList(), outcome("a")!!.items)
    }

    @Test
    fun `a hole left by nil keeps the other entries in order`() {
        on("e.drops[2] = nil")
        assertEquals(listOf("a", "c"), outcome("a", "b", "c")!!.items)
    }

    @Test
    fun `cancel drops nothing whatever the list says`() {
        on("""table.insert(e.drops, item("extra")); e:cancel()""")
        assertNull(outcome("a", "b"))
    }

    @Test
    fun `items and xp are edited together`() {
        on("""e.xp = 0; table.remove(e.drops)""")
        val o = outcome("a", "b")!!
        assertEquals(listOf("a"), o.items)
        assertEquals(0, o.xp)
    }

    @Test
    fun `an entry that is not an item is skipped and reported, the rest drops`() {
        rejected.clear()
        on("""e.drops[2] = "oops"; e.drops.name = item("stray")""")
        assertEquals(listOf("a", "c"), outcome("a", "b", "c")!!.items)
        assertEquals(2, rejected.size)
        assertTrue("oops" in rejected)
    }

    @Test
    fun `a list replaced by a non-table keeps vanilla and is reported`() {
        rejected.clear()
        on("""e.drops = "all of it"""")
        assertEquals(listOf("a", "b"), outcome("a", "b")!!.items)
        assertEquals(listOf("all of it"), rejected)
    }

    @Test
    fun `drop buffer takes stacks of its owner only`() {
        val owner = Any()
        val buffer = DropBuffer<String>(owner)
        assertTrue(buffer.offer(owner, "a"))
        assertFalse(buffer.offer(Any(), "other mob"))
        assertTrue(buffer.offer(owner, "b"))
        assertEquals(listOf("a", "b"), buffer.items)
    }

    @Test
    fun `drop buffer remembers the window it nested in`() {
        val outer = DropBuffer<String>(Any())
        val inner = DropBuffer<String>(Any(), outer)
        assertEquals(outer, inner.previous)
        assertFalse(inner.equipmentDeferred)
    }
}
