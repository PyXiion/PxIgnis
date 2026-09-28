package ru.pyxiion.ignis

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaState
import org.luaj.vm2.LuaValue
import org.luaj.vm2.lib.jse.JsePlatform
import org.slf4j.LoggerFactory
import ru.pyxiion.ignis.events.EventCatalog
import ru.pyxiion.ignis.events.EventDef
import ru.pyxiion.ignis.events.GlobalEvents
import ru.pyxiion.ignis.events.LegacyEvent
import ru.pyxiion.ignis.runtime.ScriptErrors

/** Event tables, priorities, cancellation, fail-closed errors and legacy names, driven from Lua. */
class EventModelTest {
    private val catalog = EventCatalog(
        listOf(EventDef("block.break", true), EventDef("player.join", false)),
        listOf(
            LegacyEvent("player_block_break", "block.break") { e -> arrayOf(e.rawget("player"), e.rawget("block")) },
            LegacyEvent("admin_block_break", "block.break", { !it.rawget("admin").isnil() }) { e -> arrayOf(e.rawget("player")) },
        ),
    )
    private val state: LuaState = JsePlatform.standardState()
    private val bus = EventBus("", LoggerFactory.getLogger("test"), { state }, catalog)
    private val notices = mutableListOf<ScriptErrors.Message>()

    init {
        ScriptErrors.resetRateLimit()
        ScriptErrors.notifier = { notices += it }
        state.globals.set("log", LuaValue.tableOf())
        state.globals.set("on", luaVarFunction { args ->
            val (fn, opts) = HandlerOptions.handlerAndOptions(args.arg(2), args.arg(3), "on")
            LuaValue.valueOf(bus.on(args.checkjstring(1), fn, opts))
        })
    }

    @AfterTest
    fun tearDown() {
        ScriptErrors.notifier = null
    }

    private fun lua(code: String) = state.load(code, "test").call()

    private fun log(): List<String> {
        val t = state.globals.get("log")
        return (1..t.length()).map { t.get(it).tojstring() }
    }

    private fun breakBlock(player: String = "steve", admin: Boolean = false) = bus.post("block.break") {
        it.rawset("player", LuaValue.valueOf(player))
        it.rawset("block", LuaValue.valueOf("minecraft:stone"))
        if (admin) it.rawset("admin", LuaValue.TRUE)
    }

    @Test
    fun `handlers get an event table with fields`() {
        lua("""on("block.break", function(e) table.insert(log, e.name .. " " .. e.player .. " " .. e.block .. " " .. tostring(e.cancelled)) end)""")
        val r = breakBlock()
        assertFalse(r.cancelled)
        assertEquals(listOf("block.break steve minecraft:stone false"), log())
    }

    @Test
    fun `cancel stops handlers that do not receive cancelled events`() {
        lua(
            """
            on("block.break", function(e) e:cancel("protected") table.insert(log, "protect") end)
            on("block.break", function(e) table.insert(log, "reward") end)
            on("block.break", function(e) table.insert(log, "audit " .. tostring(e.cancelled)) end, { receiveCancelled = true })
            """
        )
        val r = breakBlock()
        assertTrue(r.cancelled)
        assertEquals("protected", r.reason)
        assertEquals(listOf("protect", "audit true"), log())
    }

    @Test
    fun `a handler can uncancel`() {
        lua(
            """
            on("block.break", function(e) e:cancel() end, { priority = "high" })
            on("block.break", function(e) e.cancelled = false end, { receiveCancelled = true })
            on("block.break", function(e) table.insert(log, "ran") end, { priority = "low" })
            """
        )
        assertFalse(breakBlock().cancelled)
        assertEquals(listOf("ran"), log())
    }

    @Test
    fun `higher priority runs first, ties keep registration order`() {
        lua(
            """
            on("player.join", function() table.insert(log, "normal1") end)
            on("player.join", function() table.insert(log, "low") end, { priority = "low" })
            on("player.join", function() table.insert(log, "p50") end, { priority = 50 })
            on("player.join", function() table.insert(log, "normal2") end)
            on("player.join", function() table.insert(log, "highest") end, { priority = "highest" })
            """
        )
        bus.post("player.join")
        assertEquals(listOf("highest", "p50", "normal1", "normal2", "low"), log())
    }

    @Test
    fun `a failing handler cancels a cancellable event and is reported`() {
        lua("""on("block.break", function(e) error("boom") end)""")
        val r = breakBlock()
        assertTrue(r.cancelled)
        assertEquals(1, notices.size)
        assertTrue(notices[0].text.contains("boom"), notices[0].text)
        assertTrue(notices[0].context.contains("cancelled"), notices[0].context)
    }

    @Test
    fun `a failing handler does not stop a non-cancellable event`() {
        lua(
            """
            on("player.join", function(e) error("boom") end)
            on("player.join", function(e) table.insert(log, "second") end)
            """
        )
        assertFalse(bus.post("player.join").cancelled)
        assertEquals(listOf("second"), log())
    }

    @Test
    fun `cancelling a non-cancellable event is an error`() {
        lua("""on("player.join", function(e) e:cancel() end)""")
        bus.post("player.join")
        assertTrue(notices.single().text.contains("cannot be cancelled"), notices.single().text)
    }

    @Test
    fun `cancel after the event finished is an error`() {
        lua(
            """
            on("block.break", function(e)
                saved = e
                coroutine.yield()
            end)
            """
        )
        assertFalse(breakBlock().cancelled)
        val err = assertFailsWith<LuaError> { lua("saved:cancel()") }
        assertTrue(err.message!!.contains("already happened"), err.message)
    }

    @Test
    fun `legacy handlers get positional args and cancel by returning false`() {
        lua(
            """
            on("player_block_break", function(player, block)
                table.insert(log, player .. " " .. block)
                return false
            end)
            on("block.break", function(e) table.insert(log, "skipped") end)
            """
        )
        assertTrue(breakBlock().cancelled)
        assertEquals(listOf("steve minecraft:stone"), log())
    }

    @Test
    fun `legacy filters only see their subset`() {
        lua("""on("admin_block_break", function(player) table.insert(log, player) end)""")
        breakBlock("alex")
        breakBlock("root", admin = true)
        assertEquals(listOf("root"), log())
        assertTrue(bus.hasHandlers("admin_block_break"))
        assertTrue(bus.hasHandlers("block.break"))
    }

    @Test
    fun `nothing is built when nobody listens`() {
        var filled = false
        val r = bus.post("block.break") { filled = true }
        assertFalse(filled)
        assertNull(r.event)
    }

    @Test
    fun `custom events pass args through and can be cancelled`() {
        lua("""on("mymod:boss", function(name, hp) table.insert(log, name .. hp) return false end)""")
        val allowed = bus.emit("mymod:boss", LuaValue.varargsOf(LuaValue.valueOf("dragon"), LuaValue.valueOf(200)))
        assertFalse(allowed)
        assertEquals(listOf("dragon200"), log())
    }

    @Test
    fun `built-in events cannot be emitted`() {
        assertFailsWith<LuaError> { bus.emit("block.break", LuaValue.NONE) }
        assertFailsWith<LuaError> { bus.emit("player_block_break", LuaValue.NONE) }
    }

    @Test
    fun `unknown event names warn with a suggestion`() {
        lua("""on("block.brake", function() end)""")
        lua("""on("mymod:anything", function() end)""")
        assertEquals(1, notices.size)
        assertEquals(ScriptErrors.Severity.WARNING, notices[0].severity)
        assertTrue(notices[0].text.contains("Did you mean 'block.break'"), notices[0].text)
    }

    @Test
    fun `options may come before the handler`() {
        lua(
            """
            on("player.join", function() table.insert(log, "second") end)
            on("player.join", { priority = "high" }, function() table.insert(log, "first") end)
            """
        )
        bus.post("player.join")
        assertEquals(listOf("first", "second"), log())
    }

    @Test
    fun `bad options are rejected`() {
        assertFailsWith<LuaError> { lua("""on("block.break", function() end, { ignoreCancelled = true })""") }
        assertFailsWith<LuaError> { lua("""on("block.break", function() end, { priority = "urgent" })""") }
    }

    @Test
    fun `stats count handlers by the registered name`() {
        lua(
            """
            on("block.break", function() end)
            on("block.break", function() end)
            on("player_block_break", function() end)
            """
        )
        assertEquals(mapOf("block.break" to 2, "player_block_break" to 1), bus.stats())
    }

    @Test
    fun `global catalog suggests new names for typos of old ones`() {
        assertEquals("player.join", GlobalEvents.CATALOG.suggest("player_joim"))
        assertEquals("player.login", GlobalEvents.CATALOG.suggest("player_login"))
        assertEquals("entity.hurt", GlobalEvents.CATALOG.suggest("entity_hurt"))
        assertNull(GlobalEvents.CATALOG.suggest("something_else_entirely"))
    }
}
