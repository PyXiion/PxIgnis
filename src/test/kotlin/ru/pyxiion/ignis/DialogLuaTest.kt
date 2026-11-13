package ru.pyxiion.ignis

import ru.pyxiion.luanova.vm2.LuaState
import ru.pyxiion.luanova.vm2.LuaValue
import ru.pyxiion.luanova.vm2.lib.jse.JsePlatform
import kotlin.test.BeforeTest
import kotlin.test.Test

/** `core:dialog` against a fake `mc` and player: spec building, click routing and value validation. */
class DialogLuaTest {
    private val state: LuaState = JsePlatform.standardState()

    @BeforeTest
    fun setup() {
        run(
            """
            handlers, warnings = {}, {}
            mc = {
                on = function(name, fn) handlers[name] = fn end,
                warn = function(ctx, text) warnings[#warnings + 1] = text end,
            }
            function newPlayer(uuid)
                local p = { uuid = uuid, name = "P" .. uuid, shown = {}, closed = 0, commands = {} }
                function p:showDialog(spec) self.shown[#self.shown + 1] = spec end
                function p:closeDialog() self.closed = self.closed + 1 end
                function p:runCommand(c) self.commands[#self.commands + 1] = c; return true, 1 end
                return p
            end
            function click(p, spec, n, payload)
                handlers["player.custom_click"] { player = p, id = spec.actions[n].action.id, payload = payload }
            end
            function actionId(spec) return spec.actions[1].action.id end
            """.trimIndent()
        )
        val source = javaClass.getResource("/dialog.lua")!!.readText()
        state.globals.set("dialog", state.load(source, "dialog.lua").call())
    }

    private fun run(chunk: String): LuaValue = state.load(chunk, "t").call()

    @Test
    fun `button count and escape pick the vanilla type`() {
        run(
        """
        local p = newPlayer(1)
        dialog.show(p, { title = "t" })
        assert(p.shown[1].type == "minecraft:notice" and p.shown[1].after_action == "close")
        dialog.show(p, { title = "t", canEscape = false, buttons = { dialog.button("a", function() end) } })
        assert(p.shown[2].type == "minecraft:notice")
        dialog.show(p, { title = "t", buttons = { dialog.button("a", function() end) } })
        local s = p.shown[3]
        assert(s.type == "minecraft:multi_action" and s.exit_action.label and s.exit_action.action.id and s.pause == false)
        assert(s.after_action == "none")
        dialog.show(p, { title = "t", buttons = { dialog.button("a", function() end), dialog.link("l", "https://x.y") } })
        assert(p.shown[4].after_action == "none")
        dialog.show(p, { title = "t", buttons = { dialog.button("a", function() end, { after = "close" }) } })
        assert(p.shown[5].after_action == "close")
        """.trimIndent()
        )
    }

    @Test
    fun `nil entries are skipped and a handler gets validated values`() {
        run(
        """
        local p, got = newPlayer(1), nil
        dialog.show(p, {
            title = "t",
            inputs = {
                dialog.text("name", "Name", { max = 5 }),
                dialog.number("n", "N", 0, 10, { step = 2 }),
                dialog.option("o", "O", { { "a", "A" }, { "b", "B" } }),
                dialog.bool("b", "B"),
            },
            buttons = { false, dialog.button("go", function(pl, v) got = v end), nil },
        })
        local s = p.shown[1]
        assert(#s.actions == 1)
        click(p, s, 1, { name = "héllo", n = 4.0, o = "b", b = 1 })
        assert(got and got.name == "héllo" and got.n == 4 and got.o == "b" and got.b == true)
        assert(p.closed == 1, "wait: cleared when nothing replaced it")
        assert(#warnings == 0)
        """.trimIndent()
        )
    }

    @Test
    fun `tokens are one-shot and foreign ids are ignored`() {
        run(
        """
        local p, calls = newPlayer(1), 0
        dialog.show(p, { title = "t", buttons = { dialog.button("go", function() calls = calls + 1 end) } })
        local s = p.shown[1]
        click(p, s, 1)
        click(p, s, 1)
        assert(calls == 1)
        handlers["player.custom_click"] { player = p, id = "pxignis:menu" }
        handlers["player.custom_click"] { player = p, id = "other:thing" }
        dialog.show(p, { title = "t", buttons = { dialog.button("go", function() calls = calls + 10 end) } })
        click(p, s, 1) -- the old token
        assert(calls == 1 and #warnings == 0)
        """.trimIndent()
        )
    }

    @Test
    fun `invalid values drop the click and warn`() {
        run(
        """
        local calls = 0
        local function try(payload)
            local p = newPlayer(1)
            dialog.show(p, { title = "t",
                inputs = { dialog.text("t", "T", { max = 3 }), dialog.number("n", "N", 0, 10, { step = 5 }),
                           dialog.option("o", "O", { { "a", "A" } }) },
                buttons = { dialog.button("go", function() calls = calls + 1 end) } })
            click(p, p.shown[1], 1, payload)
            return p
        end
        try({ t = "abcd", n = 5, o = "a" })
        try({ t = "ab", n = 3, o = "a" })
        try({ t = "ab", n = 50, o = "a" })
        try({ t = "ab", n = 5, o = "zzz" })
        try({ t = "ab", n = 5 })
        try({ t = "ab", n = 5, o = "a", extra = 1 })
        assert(calls == 0, calls)
        assert(#warnings == 6, #warnings)
        local p = try({ t = "ab", n = 5, o = "a" })
        assert(calls == 1)
        """.trimIndent()
        )
    }

    @Test
    fun `keep leaves the dialog open and a handler can show the next one`() {
        run(
        """
        local p, calls, closes = newPlayer(1), 0, {}
        dialog.show(p, { title = "t", onClose = function(_, r) closes[#closes + 1] = r end, buttons = {
            dialog.button("k", function() calls = calls + 1 end, { after = "keep" }),
            dialog.button("n", function(pl) dialog.show(pl, { title = "next" }) end),
        } })
        local s = p.shown[1]
        assert(s.after_action == "none")
        click(p, s, 1); click(p, s, 1)
        assert(calls == 2 and p.closed == 0 and #closes == 0)
        click(p, s, 2)
        assert(p.shown[2].title == "next" and p.closed == 0 and #closes == 0, "consumed, not closed")
        """.trimIndent()
        )
    }

    @Test
    fun `onClose reports escape and replacement and leave and reload`() {
        run(
        """
        local p, reasons = newPlayer(1), {}
        local function open() dialog.show(p, { title = "t", onClose = function(_, r) reasons[#reasons + 1] = r end,
            buttons = { dialog.button("a", function() end) } }) end
        open()
        handlers["player.custom_click"] { player = p, id = p.shown[1].exit_action.action.id }
        open(); open()
        handlers["player.leave"] { player = p }
        open()
        handlers["uninit"]()
        assert(table.concat(reasons, ",") == "escape,replaced,leave,reload", table.concat(reasons, ","))
        """.trimIndent()
        )
    }

    @Test
    fun `a failing handler still cleans up and rethrows`() {
        run(
        """
        local p = newPlayer(1)
        dialog.show(p, { title = "t", buttons = { dialog.button("a", function() error("boom") end) } })
        local ok, err = pcall(click, p, p.shown[1], 1)
        assert(not ok and tostring(err):find("boom"))
        assert(p.closed == 1)
        """.trimIndent()
        )
    }

    @Test
    fun `command buttons run the command as the player`() {
        run(
        """
        local p = newPlayer(1)
        dialog.show(p, { title = "t", buttons = { dialog.command("go", "say hi") } })
        click(p, p.shown[1], 1)
        assert(p.commands[1] == "say hi" and p.closed == 1)
        """.trimIndent()
        )
    }

    @Test
    fun `bad specs raise errors`() {
        run(
        """
        local p = newPlayer(1)
        assert(not pcall(dialog.show, p, { }))
        assert(not pcall(dialog.show, p, { title = "t", inputs = { dialog.text("a b", "x") } }))
        assert(not pcall(dialog.show, p, { title = "t", inputs = { dialog.text("a", "x"), dialog.text("a", "y") } }))
        assert(not pcall(dialog.show, p, { title = "t", buttons = { dialog.button("x", function() end, { after = "later" }) } }))
        assert(not pcall(dialog.show, p, { title = "t", inputs = { dialog.number("n", "N", 5, 1) } }))
        """.trimIndent()
        )
    }
}
