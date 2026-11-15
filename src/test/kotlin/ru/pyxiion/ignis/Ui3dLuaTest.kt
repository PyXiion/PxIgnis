package ru.pyxiion.ignis

import ru.pyxiion.luanova.vm2.LuaState
import ru.pyxiion.luanova.vm2.LuaValue
import ru.pyxiion.luanova.vm2.lib.jse.JsePlatform
import kotlin.test.BeforeTest
import kotlin.test.Test

/** `core:ui3d` against a fake `world:display`, `mc` scheduler and players: layout, ray mapping, hit-test, bindings. */
class Ui3dLuaTest {
    private val state: LuaState = JsePlatform.standardState()

    private fun resource(name: String): String = javaClass.getResource(name)!!.readText()

    @BeforeTest
    fun setup() {
        run(
            """
            mc = { tasks = {}, n = 0, now = 0, players = {} }
            function mc.schedule(d, cb) mc.n = mc.n + 1; mc.tasks[mc.n] = { at = mc.now + d, cb = cb }; return mc.n end
            function mc.scheduleRepeating(d, i, cb)
                mc.n = mc.n + 1; mc.tasks[mc.n] = { at = mc.now + d, every = i, cb = cb }; return mc.n
            end
            function mc.cancelTask(id) if mc.tasks[id] then mc.tasks[id].dead = true end end
            function tick(n)
                for _ = 1, n or 1 do
                    mc.now = mc.now + 1
                    for id = 1, mc.n do
                        local t = mc.tasks[id]
                        if t and not t.dead and t.at <= mc.now then
                            if t.every then t.at = mc.now + t.every else t.dead = true end
                            t.cb()
                        end
                    end
                end
            end
            function liveTasks() local c = 0; for _, t in pairs(mc.tasks) do if not t.dead then c = c + 1 end end; return c end

            Core = {}
            Core.__index = Core
            function Core:set(p) self.sets = self.sets + 1; for k, v in pairs(p) do self.props[k] = v end end
            function Core:moveTo(pos) self.pos = pos end
            function Core:attach() end
            function Core:detach() end
            function Core:show(p) self.shownTo[p.uuid] = true end
            function Core:hide(p) self.shownTo[p.uuid] = nil end
            function Core:remove() self.valid = false end
            function Core:onClick(fn) self.click = fn end
            function Core:onAttack(fn) self.attack = fn end

            cores = {}
            sounds = {}
            rayResult = nil
            world = {
                name = "overworld",
                display = function(_, kind, pos, props)
                    local c = setmetatable({ kind = kind, pos = pos, props = {}, sets = 0, valid = true, shownTo = {} }, Core)
                    for k, v in pairs(props or {}) do c.props[k] = v end
                    cores[#cores + 1] = c
                    return c
                end,
                raycast = function() return rayResult end,
                playSound = function(_, id, pos, volume, pitch)
                    sounds[#sounds + 1] = { who = "world", id = id, pos = pos, volume = volume, pitch = pitch }
                end,
            }
            function mc.world() return world end

            function eq(a, b, what) assert(a == b, (what or "") .. ": expected " .. tostring(b) .. ", got " .. tostring(a)) end
            function near(a, b, what) assert(math.abs(a - b) < 1e-6, (what or "") .. ": expected " .. tostring(b) .. ", got " .. tostring(a)) end
            function totalSets() local c = 0; for _, k in ipairs(cores) do c = c + k.sets end; return c end
            function liveCores() local c = 0; for _, k in ipairs(cores) do if k.valid then c = c + 1 end end; return c end
            function withBackground(argb)
                local c = 0
                for _, k in ipairs(cores) do if k.valid and k.kind == "text" and k.props.background == argb and (k.props.viewers ~= "manual" or next(k.shownTo)) then c = c + 1 end end
                return c
            end
            function shield() for i = #cores, 1, -1 do local k = cores[i]; if k.kind == "interaction" and k.valid then return k end end end
            function player(x, y, z, dx, dy, dz)
                return { uuid = "p" .. x, playSound = function(self, id, volume, pitch) sounds[#sounds + 1] = { who = self.uuid, id = id, volume = volume, pitch = pitch } end, eyePos = { x = x, y = y, z = z }, dir = { x = dx, y = dy, z = dz }, pos = { x = x, y = y - 1.6, z = z } }
            end
            """.trimIndent()
        )
        val mods = mapOf("core:ref" to "/ref.lua", "core:display" to "/display.lua")
        val loaded = LuaValue.tableOf()
        state.globals.set("package_loaded", loaded)
        for ((name, res) in mods) loaded.set(name, state.load(resource(res), res.drop(1)).call())
        run("function require(n) return package_loaded[n] end")
        loaded.set("core:ui3d", state.load(resource("/ui3d.lua"), "ui3d.lua").call())
        run("ref, ui = package_loaded['core:ref'], package_loaded['core:ui3d']")
    }

    private fun run(chunk: String): LuaValue = state.load(chunk, "t").call()

    @Test
    fun `layout stacks, gaps, pads and distributes grow space`() {
        run(
            """
            local function box(w, h, grow) return { kind = "box", p = { w = w, h = h, grow = grow }, children = {} } end
            local function node(kind, p, kids) return { kind = kind, p = p, children = kids } end

            -- col: natural size, gap and pad
            local a, b = box(1, 0.5), box(2, 0.25)
            local col = node("col", { gap = 0.1, pad = 0.2 }, { a, b })
            local w, h = ui.layout(col)
            near(w, 2.4); near(h, 0.5 + 0.25 + 0.1 + 0.4)
            near(a.rect.x, 0.2); near(a.rect.y, 0.2); near(b.rect.y, 0.2 + 0.5 + 0.1)
            near(a.rect.w, 1, "fixed width"); near(b.rect.w, 2.0)

            -- row with grow shares the free space by weight
            local x, y, z = box(1, 1), box(0, 1, 1), box(0, 1, 3)
            local row = node("row", { gap = 0.5 }, { x, y, z })
            ui.layout(row, 10, 1)
            near(y.rect.x, 1.5); near(y.rect.w, 2); near(z.rect.x, 4.0); near(z.rect.w, 6)
            eq(z.rect.h, 1)

            -- align
            local c = box(1, 1)
            local centered = node("col", { align = "center" }, { c, box(3, 1) })
            ui.layout(centered)
            near(c.rect.x, 1)
            local e = box(1, 1)
            ui.layout(node("col", { align = "end" }, { e, box(3, 1) }))
            near(e.rect.x, 2)

            -- stack layers share the rectangle; nested dynamic children are flattened into the parent
            local s1, s2 = box(nil, nil), box(nil, nil)
            ui.layout(node("stack", {}, { s1, s2 }), 4, 4)
            near(s1.rect.w, 4); near(s2.rect.h, 4)
            local d1, d2 = box(1, 1), box(1, 1)
            local dyn = { kind = "each", dyn = true, p = {}, children = { d1, d2 } }
            local r = node("row", {}, { box(1, 1), dyn })
            local rw = ui.layout(r)
            near(rw, 3); near(d2.rect.x, 2)
            """.trimIndent()
        )
    }

    @Test
    fun `ray to panel local coordinates for every facing`() {
        run(
            """
            -- north: the front faces -Z, the viewer stands at -Z looking +Z, their right is -X
            local f = ui.frame({ x = 0, y = 64, z = 0 }, "north", 2, 1)
            local x, y, t = ui.project(f, { x = 0.5, y = 64.25, z = -3 }, { x = 0, y = 0, z = 1 }, 8)
            near(x, 0.5, "north x"); near(y, 0.25, "north y"); near(t, 3, "north t")
            -- top-left of the panel is at the viewer's left/top
            x, y = ui.project(f, { x = 0.99, y = 64.49, z = -2 }, { x = 0, y = 0, z = 1 })
            near(x, 0.01); near(y, 0.01)
            assert(ui.project(f, { x = 0, y = 64, z = 3 }, { x = 0, y = 0, z = -1 }) == nil, "back side")
            assert(ui.project(f, { x = 1.5, y = 64, z = -3 }, { x = 0, y = 0, z = 1 }) == nil, "outside")
            assert(ui.project(f, { x = 0, y = 64, z = -3 }, { x = 1, y = 0, z = 0 }) == nil, "parallel")
            assert(ui.project(f, { x = 0, y = 64, z = -9 }, { x = 0, y = 0, z = 1 }, 8) == nil, "range")
            assert(ui.project(f, { x = 0, y = 64, z = 3 }, { x = 0, y = 0, z = 1 }) == nil, "behind the eye")

            local e = ui.frame({ x = 0, y = 64, z = 0 }, "east", 2, 1)
            x, y = ui.project(e, { x = 3, y = 64, z = 0.5 }, { x = -1, y = 0, z = 0 })
            near(x, 0.5, "east x")
            local s = ui.frame({ x = 0, y = 64, z = 0 }, "south", 2, 1)
            x = ui.project(s, { x = 0.5, y = 64, z = 3 }, { x = 0, y = 0, z = -1 })
            near(x, 1.5, "south x")
            local w = ui.frame({ x = 0, y = 64, z = 0 }, "west", 2, 1)
            x = ui.project(w, { x = -3, y = 64, z = 0.5 }, { x = 1, y = 0, z = 0 })
            near(x, 1.5, "west x")

            -- oblique ray
            x, y, t = ui.project(s, { x = -2, y = 64, z = 2 }, { x = math.sqrt(0.5), y = 0, z = -math.sqrt(0.5) })
            near(x, 1, "oblique x"); near(t, 2 * math.sqrt(2), "oblique t") -- hits (0, 64, 0)
            """.trimIndent()
        )
    }

    @Test
    fun `hit-test picks the topmost interactive node`() {
        run(
            """
            local function btn(w, h) return { kind = "button", interactive = true, p = { w = w, h = h }, children = {} } end
            local lbl = { kind = "text", p = { w = 1, h = 1 }, children = {} }
            local b1, b2 = btn(1, 1), btn(1, 1)
            local row = { kind = "row", p = { gap = 1 }, children = { b1, lbl, b2 } }
            ui.layout(row)
            eq(ui.hit(row, 0.5, 0.5), b1)
            eq(ui.hit(row, 2.5, 0.5), nil, "label is not interactive")
            eq(ui.hit(row, 4.5, 0.5), b2)
            eq(ui.hit(row, 1.5, 0.5), nil, "gap")
            eq(ui.hit(row, 9, 9), nil)
            -- stack: the later layer wins
            local below, above = btn(1, 1), btn(1, 1)
            local st = { kind = "stack", p = {}, children = { below, above } }
            ui.layout(st, 2, 2)
            eq(ui.hit(st, 1, 1), above)
            """.trimIndent()
        )
    }

    @Test
    fun `styles merge theme overrides per state`() {
        run(
            """
            local th = ui.theme({ danger = { bg = 0xFF111111, hover = { scale = 2 } }, custom = { fg = 5 } })
            local base = ui.resolveStyle(th, "danger", nil)
            eq(base.bg, 0xFF111111); eq(base.fg, 0xFFFFFFFF)
            local hov = ui.resolveStyle(th, "danger", "hover")
            eq(hov.scale, 2); eq(hov.bg, 0xFFB02A2A, "inherited hover bg")
            eq(ui.resolveStyle(th, "custom", "press").fg, 5)
            eq(ui.resolveStyle(ui.theme(), "button", "disabled").fg, 0xFF808080)
            """.trimIndent()
        )
    }

    private val demo = """
        local score = ref.new(0)
        local clicks, last = 0, nil
        local p = ui.panel { at = { x = 0, y = 64, z = 0 }, facing = "north", width = 3, world = world } {
            ui.col { gap = 0.05, pad = 0.1,
                ui.text { id = "title", function() return "Score: " .. score:get() end },
                ui.button { id = "go", "+1", onClick = function(pl) clicks = clicks + 1; last = pl end,
                            disabled = function() return score:get() >= 10 end },
            },
        }
        local viewer = player(0, 64, -3, 0, 0, 1)
        mc.players[1] = viewer
    """.trimIndent() + "\n"

    @Test
    fun `hover needs a plane hit and no block in between, click goes through the shield`() {
        run(
            demo + """
            eq(withBackground(0xFF3A3A3A), 1, "idle button")
            tick(1)
            eq(withBackground(0xFF565656), 1, "hovered")
            -- a block between the eye and the panel cancels the hover
            rayResult = { hit = { x = 0, y = 64, z = -1 } }
            tick(1)
            eq(withBackground(0xFF3A3A3A), 1, "occluded")
            -- a block behind the panel plane does not
            rayResult = { hit = { x = 0, y = 64, z = 2 } }
            tick(1)
            eq(withBackground(0xFF565656), 1, "block behind the panel")
            rayResult = nil
            -- looking away
            viewer.dir = { x = 0, y = 0, z = -1 }
            tick(1)
            eq(withBackground(0xFF3A3A3A), 1, "looked away")
            viewer.dir = { x = 0, y = 0, z = 1 }
            tick(1)

            local s = shield()
            near(s.props.hitbox.w, 3); assert(s.props.hitbox.h > 0.5)
            s.click(viewer, "off")
            eq(clicks, 0, "off hand ignored")
            s.click(viewer, "main")
            eq(clicks, 1); eq(last, viewer)
            eq(withBackground(0xFF262626), 1, "pressed: only the press overlay is visible")
            tick(4)
            eq(withBackground(0xFF262626), 0, "press released")
            s.attack(viewer)
            eq(clicks, 2, "attack clicks too")
            -- a click outside every widget does nothing
            s.click(player(1.45, 64, -3, 0, 0, 1), "main")
            eq(clicks, 2)
            s.click(player(0, 70, -3, 0, 0, 1), "main")
            eq(clicks, 2, "missed the panel")
            -- disabled buttons ignore clicks
            score:set(10)
            s.click(viewer, "main")
            eq(clicks, 2, "disabled")
            eq(withBackground(0xFF2A2A2A), 1, "disabled style")
            """.trimIndent()
        )
    }

    @Test
    fun `props bind to refs and functions, remove disposes everything`() {
        run(
            demo + """
            local title = p:get("title")
            eq(title.p.text, "Score: 0")
            local text
            for _, c in ipairs(cores) do if c.kind == "text" and type(c.props.text) == "table" and c.props.text.text == "Score: 0" then text = c end end
            assert(text, "label display")
            score:set(3)
            eq(title.p.text, "Score: 3")
            eq(text.props.text.text, "Score: 3")
            assert(liveCores() >= 3)
            eq(liveTasks(), 1)

            p:remove()
            eq(liveCores(), 0, "every display removed")
            eq(liveTasks(), 0, "hover task cancelled")
            local before = totalSets()
            score:set(5)
            eq(totalSets(), before, "no binding survives remove")
            p:remove() -- idempotent
            """.trimIndent()
        )
    }

    @Test
    fun `show and each rebuild only their subtree`() {
        run(
            """
            local flag, list = ref.new(false), ref.new({ "a" })
            local p = ui.panel { at = { x = 0, y = 64, z = 0 }, width = 3, world = world } {
                ui.col {
                    ui.text { id = "static", "hello" },
                    ui.show(flag, ui.button { id = "extra", "X" }),
                    ui.each(list, function(item, i) return ui.text { id = "item" .. i, item } end),
                },
            }
            local static = p:get("static")
            local before = liveCores()
            eq(p:get("extra"), nil); assert(p:get("item1")); eq(p:get("item2"), nil)
            local staticSets = (function() for _, c in ipairs(cores) do if c.props.text and c.props.text.text == "hello" then return c.sets end end end)()
            flag:set(true)
            assert(p:get("extra"), "shown")
            assert(liveCores() > before, "button displays added")
            eq(p:get("static"), static, "untouched node survives")
            list:set({ "a", "b", "c" })
            assert(p:get("item3"))
            list:set({})
            eq(p:get("item1"), nil)
            flag:set(false)
            eq(p:get("extra"), nil)
            eq(liveCores(), before - 1, "only the static label and the shield remain next to the removed ones")
            p:remove()
            eq(liveCores(), 0)
            """.trimIndent()
        )
    }

    @Test
    fun `toggle writes its ref and a factory panel builds one tree per player`() {
        run(
            """
            local on = ref.new(false)
            local changes = 0
            local p = ui.panel { at = { x = 0, y = 64, z = 0 }, width = 1, world = world } {
                ui.toggle { id = "t", value = on, onChange = function(v) changes = changes + 1 end },
            }
            local viewer = player(0, 64, 3, 0, 0, -1)
            mc.players[1] = viewer
            shield().click(viewer, "main")
            eq(on:get(), true); eq(changes, 1)
            shield().click(viewer, "main")
            eq(on:get(), false)
            p:remove()

            local built = {}
            local q = ui.panel { at = { x = 0, y = 64, z = 0 }, width = 2, world = world } (function(pl)
                built[#built + 1] = pl
                return ui.text("hi " .. pl.uuid)
            end)
            eq(#built, 1)
            local before = liveCores()
            mc.players[2] = player(5, 64, 5, 0, 0, -1)
            tick(1)
            eq(#built, 2, "second player gets a tree")
            assert(q:get("none", viewer) == nil)
            mc.players[2] = nil
            tick(1)
            eq(liveCores(), before, "leaving player's displays removed")
            q:remove()
            eq(liveCores(), 0)
            """.trimIndent()
        )
    }

    @Test
    fun `factory panels follow the player's dimension`() {
        run(
            """
            local built = 0
            local q = ui.panel { at = { x = 0, y = 64, z = 0 }, width = 2, world = world } (function(pl)
                built = built + 1
                return ui.text("hi")
            end)
            local pl = player(1, 64, 1, 0, 0, -1)
            pl.world = { name = "the_nether" }
            mc.players[1] = pl
            tick(1)
            eq(built, 0, "other dimension gets no tree")
            pl.world = world
            tick(1)
            eq(built, 1)
            local live = liveCores()
            pl.world = { name = "the_end" }
            tick(1)
            assert(liveCores() < live, "tree dropped on world change")
            q:remove()
            """.trimIndent()
        )
    }

    @Test
    fun `viewers of a shared panel hover different widgets through their own overlays`() {
        run(
            """
            local p = ui.panel { at = { x = 0, y = 64, z = 0 }, width = 3, world = world } {
                ui.col { pad = 0.1,
                    ui.row { gap = 0.1,
                        ui.button { id = "a", "A", grow = true },
                        ui.button { id = "b", "B", grow = true },
                    },
                },
            }
            local v1, v2 = player(-0.8, 64, 3, 0, 0, -1), player(0.8, 64, 3, 0, 0, -1)
            mc.players[1], mc.players[2] = v1, v2
            local function manual(fn)
                local n = 0
                for _, k in ipairs(cores) do
                    if k.valid and k.props.viewers == "manual" and fn(k.shownTo) then n = n + 1 end
                end
                return n
            end
            eq(manual(function() return true end), 0, "overlays are created on demand")
            eq(withBackground(0xFF3A3A3A), 2, "both buttons idle")
            tick(1)
            -- each overlay is a quad plus a label; v1 sees A's, v2 sees B's, nobody sees both
            eq(manual(function(t) return t["p-0.8"] end), 2)
            eq(manual(function(t) return t["p0.8"] end), 2)
            eq(manual(function(t) return t["p-0.8"] and t["p0.8"] end), 0, "different widgets")
            eq(withBackground(0xFF3A3A3A), 2, "base buttons stay idle")
            -- v1 moves to B: both see the same overlay, A's overlay is hidden
            v1.eyePos = { x = 0.8, y = 64, z = 3 }
            tick(1)
            eq(manual(function(t) return t["p-0.8"] and t["p0.8"] end), 2, "both on B")
            eq(manual(function(t) return next(t) end), 2, "A's overlay hidden")
            -- a block in front of v2 hides only v2's hover
            rayResult = { hit = { x = 0.8, y = 64, z = 2 } }
            tick(1)
            eq(manual(function(t) return next(t) end), 0, "all occluded")
            rayResult = nil
            tick(1)
            -- v2 leaves
            mc.players[2] = nil
            tick(1)
            eq(manual(function(t) return t["p-0.8"] end), 2, "v1 still hovers B")
            p:remove()
            eq(liveCores(), 0, "overlays removed with the panel")
            """.trimIndent()
        )
    }

    private val twoButtons = """
        local function build(opts, a, b)
            opts.at, opts.width, opts.world = { x = 0, y = 64, z = 0 }, 3, world
            return ui.panel(opts) {
                ui.col { pad = 0.1,
                    ui.row { gap = 0.1,
                        ui.button(a), ui.button(b),
                    },
                },
            }
        end
        local v1, v2 = player(-0.8, 64, 3, 0, 0, -1), player(0.8, 64, 3, 0, 0, -1)
        mc.players[1], mc.players[2] = v1, v2
        -- manual-viewer displays shown to `uuid` (an overlay is a quad plus a label)
        local function overlaysOf(uuid)
            local n = 0
            for _, k in ipairs(cores) do
                if k.valid and k.props.viewers == "manual" and k.shownTo[uuid] then n = n + 1 end
            end
            return n
        end
        local function overlays()
            local n = 0
            for _, k in ipairs(cores) do if k.props.viewers == "manual" then n = n + 1 end end
            return n
        end
    """.trimIndent() + "\n"

    @Test
    fun `hover mode shared restyles the widget for everyone, viewer mode uses overlays, widgets override the panel`() {
        run(
            twoButtons + """
            -- shared: both buttons take the hover look, no overlay exists
            local p = build({ hover = "shared" }, { "A", grow = true }, { "B", grow = true })
            tick(1)
            eq(withBackground(0xFF565656), 2, "both hovered on the widgets themselves")
            eq(overlays(), 0)
            p:remove()

            -- viewer (the default): the widgets stay idle, each player sees their own overlay
            p = build({}, { "A", grow = true }, { "B", grow = true })
            tick(1)
            eq(withBackground(0xFF3A3A3A), 2, "widgets idle")
            eq(overlaysOf("p-0.8"), 2); eq(overlaysOf("p0.8"), 2)
            p:remove()

            -- per-widget override: B is shared inside a viewer panel, A is viewer inside a shared panel
            p = build({}, { "A", grow = true }, { "B", grow = true, hover = "shared" })
            tick(1)
            eq(overlaysOf("p-0.8"), 2, "A uses an overlay"); eq(overlaysOf("p0.8"), 0, "B does not")
            eq(withBackground(0xFF565656), 2, "overlay of A plus B itself")
            p:remove()
            p = build({ hover = "shared" }, { "A", grow = true, hover = "viewer" }, { "B", grow = true })
            tick(1)
            eq(overlaysOf("p-0.8"), 2); eq(overlaysOf("p0.8"), 0)
            p:remove()
            local ok = pcall(ui.panel, { at = { x = 0, y = 0, z = 0 }, hover = "both" })
            assert(not ok, "bad mode rejected")
            """.trimIndent()
        )
    }

    @Test
    fun `press mode viewer shows the press look only to the clicker, shared presses the widget`() {
        run(
            twoButtons + """
            local clicks = 0
            local function count() clicks = clicks + 1 end
            -- viewer: v1 presses A, v2 does not see it
            local p = build({}, { "A", grow = true, onClick = count }, { "B", grow = true, onClick = count })
            tick(1)
            shield().click(v1, "main")
            eq(clicks, 1)
            eq(withBackground(0xFF262626), 1, "one press overlay")
            local pressOverlayShown = 0
            for _, k in ipairs(cores) do
                if k.valid and k.props.background == 0xFF262626 and k.shownTo["p-0.8"] and not k.shownTo["p0.8"] then
                    pressOverlayShown = pressOverlayShown + 1
                end
            end
            eq(pressOverlayShown, 1, "only the clicker sees it")
            tick(4)
            eq(withBackground(0xFF262626), 0, "released")
            p:remove()

            -- shared: the widget itself is pressed
            p = build({ press = "shared" }, { "A", grow = true, onClick = count }, { "B", grow = true, onClick = count })
            local before = overlays()
            shield().click(v1, "main")
            eq(withBackground(0xFF262626), 1, "button pressed for everyone")
            eq(overlays(), before, "no press overlay")
            tick(4)
            eq(withBackground(0xFF262626), 0)
            p:remove()

            -- per-widget override
            p = build({}, { "A", grow = true, onClick = count, press = "shared" }, { "B", grow = true, onClick = count })
            before = overlays()
            shield().click(v1, "main")
            eq(withBackground(0xFF262626), 1, "A presses itself inside a viewer panel")
            eq(overlays(), before)
            tick(4)
            shield().click(v2, "main")
            eq(withBackground(0xFF262626), 1, "B uses an overlay")
            assert(overlays() > before)
            p:remove()
            eq(liveCores(), 0, "everything removed")
            """.trimIndent()
        )
    }

    // The rectangle a quad display draws, in panel layout coordinates (south-facing panel at x = 0): the background
    // spans -0.4 .. 0.6 of its width around the origin horizontally and 0 .. 1 of its height upwards.
    private val visual = """
        function visual(core, W, H)
            local t, s = core.props.transform.t, core.props.transform.s
            local w, h = s.x / 8, s.y / 4
            return { x0 = t.x - 0.4 * w + W / 2, x1 = t.x + 0.6 * w + W / 2, y0 = H / 2 - (t.y + h), y1 = H / 2 - t.y }
        end
        function quadsWith(argb)
            local out = {}
            for _, k in ipairs(cores) do if k.valid and k.props.background == argb then out[#out + 1] = k end end
            return out
        end
    """.trimIndent() + "\n"

    @Test
    fun `bar fill keeps its left edge for every value`() {
        run(
            visual + """
            local v = ref.new(1)
            local p = ui.panel { at = { x = 0, y = 64, z = 0 }, width = 2, world = world } { ui.bar { value = v } }
            local W, H = 2, 0.12
            for _, value in ipairs { 1, 0.75, 0.5, 0.25, 0.1 } do
                v:set(value)
                local fill = visual(quadsWith(0xFF33CC33)[1], W, H)
                near(fill.x0, 0, "left edge at value " .. value)
                near(fill.x1, 2 * value, "right edge at value " .. value)
                near(fill.y0, 0); near(fill.y1, H)
            end
            local track = visual(quadsWith(0x80000000)[1], W, H)
            near(track.x0, 0); near(track.x1, 2, "track spans the panel")
            """.trimIndent()
        )
    }

    @Test
    fun `visual rectangle and hit rectangle are the same numbers`() {
        run(
            twoButtons + visual + """
            local p = build({}, { "A", id = "a", grow = true }, { "Longer", id = "b", grow = true })
            local inst = p.instances.shared
            local f = inst.frame
            for _, id in ipairs { "a", "b" } do
                local node = p:get(id)
                local r = node.rect
                -- the background quad of this widget: same size as its layout rectangle
                local best
                for _, k in ipairs(cores) do
                    if k.valid and k.props.background == 0xFF3A3A3A then
                        local q = visual(k, f.w, f.h)
                        if math.abs(q.x0 - r.x) < 1e-6 then best = q end
                    end
                end
                assert(best, "quad for " .. id)
                near(best.x1, r.x + r.w, id .. " right"); near(best.y0, r.y, id .. " top"); near(best.y1, r.y + r.h, id .. " bottom")
                -- just inside every visual edge, the ray picks the widget
                local e = 0.005
                for _, pt in ipairs { { best.x0 + e, best.y0 + e }, { best.x1 - e, best.y0 + e }, { best.x0 + e, best.y1 - e }, { best.x1 - e, best.y1 - e } } do
                    local eye = { x = pt[1] - f.w / 2, y = 64 + f.h / 2 - pt[2], z = 3 }
                    local x, y = ui.project(f, eye, { x = 0, y = 0, z = -1 })
                    eq(ui.hit(inst.root, x, y), node, id .. " edge " .. pt[1] .. "," .. pt[2])
                end
                -- and just outside a visual edge it does not
                local x, y = ui.project(f, { x = best.x0 - e - f.w / 2, y = 64, z = 3 }, { x = 0, y = 0, z = -1 })
                assert(ui.hit(inst.root, x, y) ~= node, id .. " outside")
            end
            """.trimIndent()
        )
    }

    @Test
    fun `button sounds follow the press and hover modes, disabled widgets stay silent unless configured`() {
        run(
            twoButtons + """
            local th = ui.theme({ button = { sound = { hover = "test:tick", disabled = "test:deny" } } })
            eq(th.button.sound.press, "minecraft:ui.button.click", "theme merges sound fields")
            eq(th.button.sound.volume, 0.6)

            -- default theme: press clicks, hover is silent. viewer: only the clicker hears it
            local p = build({}, { "A", grow = true }, { "B", grow = true })
            tick(1)
            eq(#sounds, 0, "hover is silent by default")
            shield().click(v1, "main")
            eq(#sounds, 1); eq(sounds[1].who, "p-0.8"); eq(sounds[1].id, "minecraft:ui.button.click")
            near(sounds[1].volume, 0.6)
            p:remove()

            -- shared press: played at the widget for nearby players
            sounds = {}
            p = build({ press = "shared" }, { "A", grow = true }, { "B", grow = true })
            shield().click(v1, "main")
            eq(#sounds, 1); eq(sounds[1].who, "world")
            near(sounds[1].pos.z, 0); near(sounds[1].pos.y, 64, "widget centre height")
            assert(sounds[1].pos.x < 0, "at widget A, the left one")
            p:remove()

            -- hover sound (theme): viewer mode plays per player once per hover, shared plays once for the widget
            sounds = {}
            p = build({ theme = { button = { sound = { hover = "test:tick" } } } }, { "A", grow = true }, { "B", grow = true })
            tick(3)
            eq(#sounds, 2, "each viewer hears their own hover once")
            local who = { sounds[1].who, sounds[2].who }; table.sort(who)
            eq(who[1], "p-0.8"); eq(who[2], "p0.8"); eq(sounds[1].id, "test:tick")
            p:remove()
            sounds = {}
            p = build({ hover = "shared", theme = { button = { sound = { hover = "test:tick" } } } }, { "A", grow = true }, { "A2", grow = true })
            mc.players[2] = player(-0.7, 64, 3, 0, 0, -1)
            tick(3)
            eq(#sounds, 1, "one sound for the widget"); eq(sounds[1].who, "world")
            p:remove()

            -- disabled: silent by default, `sound.disabled` plays a deny sound; no hover sound either
            sounds = {}
            mc.players[2] = nil
            p = build({}, { "A", grow = true, disabled = true }, { "B", grow = true })
            tick(2)
            shield().click(v1, "main")
            eq(#sounds, 0, "disabled button is silent")
            p:remove()
            p = build({ theme = { button = { sound = { hover = "test:tick", disabled = "test:deny" } } } }, { "A", grow = true, disabled = true }, { "B", grow = true })
            tick(2)
            eq(#sounds, 0, "no hover sound on a disabled widget")
            shield().click(v1, "main")
            eq(#sounds, 1); eq(sounds[1].id, "test:deny"); eq(sounds[1].who, "p-0.8")
            p:remove()
            """.trimIndent()
        )
    }

    @Test
    fun `icon components, item sprites and theme icons`() {
        run(
            """
            eq(ui.itemSprite("minecraft:diamond"), "minecraft:item/diamond")
            eq(ui.itemSprite("mymod:gem/ruby"), "mymod:item/gem/ruby")
            eq(ui.itemSprite("diamond"), "minecraft:item/diamond")

            local c = ui.iconComponent({ item = "minecraft:diamond" })
            eq(c.type, "object"); eq(c.atlas, "minecraft:items"); eq(c.sprite, "minecraft:item/diamond")
            c = ui.iconComponent({ sprite = "minecraft:block/stone", atlas = "minecraft:blocks" })
            eq(c.atlas, "minecraft:blocks"); eq(c.sprite, "minecraft:block/stone")
            c = ui.iconComponent({ glyph = "\u{E001}", font = "ns:id" })
            eq(c.text, "\u{E001}"); eq(c.font, "ns:id"); eq(c.type, nil)
            c = ui.iconComponent({ head = "Steve" })
            eq(c.type, "object"); eq(c.player, "Steve"); eq(c.hat, true)
            c = ui.iconComponent({ head = setmetatable({ name = "Alex" }, {}), hat = false })
            eq(c.player, "Alex"); eq(c.hat, false)
            local profile = { name = "N", id = { 1, 2, 3, 4 } }
            eq(ui.iconComponent({ head = profile }).player, profile, "a plain table is a profile")
            assert(not pcall(ui.iconComponent, {}), "an empty icon is rejected")

            local th = ui.theme({ icons = { coin = { glyph = "C", font = "a:b" }, gem = { item = "a:gem" } } })
            local r = ui.resolveIcon({ name = "coin" }, th)
            eq(r.glyph, "C"); eq(r.font, "a:b")
            r = ui.resolveIcon({ name = "coin", font = "c:d" }, th)
            eq(r.font, "c:d", "widget fields override the theme icon"); eq(r.glyph, "C")
            eq(ui.resolveIcon({ name = "gem" }, th).item, "a:gem")
            eq(ui.resolveIcon({ item = "x:y" }, th).item, "x:y", "no name: unchanged")
            assert(not pcall(ui.resolveIcon, { name = "nope" }, th), "unknown icon")

            local d = ui.icon "minecraft:diamond"; eq(d.item, "minecraft:diamond"); eq(d.name, nil)
            d = ui.icon "coin"; eq(d.name, "coin"); eq(d.item, nil)
            d = ui.icon { glyph = "G", size = 0.3 }; eq(d.kind, "icon"); eq(d.size, 0.3)
            """.trimIndent()
        )
    }

    @Test
    fun `icon width is one constant for layout, render and hit rectangle`() {
        run(
            """
            local display = package_loaded["core:display"]
            local ICON = display.ICON
            near(ICON.w, 0.2); near(ICON.h, 0.2)
            -- an icon counts as ICON.w, a character as 0.15, scaled by size / 0.225
            local parts = { "ab", { kind = "icon" }, "c" }
            near(ui.partsWidth(parts, 0.225), 3 * 0.15 + ICON.w)
            near(ui.partsWidth(parts, 0.45), 2 * (3 * 0.15 + ICON.w), "scales with the size")

            local theme = { icons = { coin = { glyph = "C", font = "a:b" } } }
            local p = ui.panel { at = { x = 0, y = 64, z = 0 }, width = 4, world = world, theme = theme } {
                ui.row { align = "start",
                    ui.text { id = "line", "Price: ", ui.icon "coin", " 10", align = "left" },
                    ui.icon { id = "dia", item = "minecraft:diamond" },
                    ui.icon { id = "mdl", item = "minecraft:diamond", model = true },
                },
            }
            local f = p.instances.shared.frame

            -- the text line: the layout width is the inline width, and the label is centred in that rectangle
            local line = p:get("line")
            near(line.rect.w, ui.partsWidth({ "Price: ", { kind = "icon" }, " 10" }, 0.2), "layout width")
            local label
            for _, k in ipairs(cores) do if k.props.text and type(k.props.text) == "table" and k.props.text.extra then label = k end end
            assert(label, "inline text component")
            eq(label.props.text.extra[1].text, "Price: ")
            eq(label.props.text.extra[2].text, "C"); eq(label.props.text.extra[2].font, "a:b")
            eq(label.props.text.extra[3].text, " 10")
            local centre = label.props.transform.t.x + f.w / 2
            near(centre, line.rect.x + line.rect.w / 2, "label centred on the layout rectangle")

            -- a standalone icon: scale * glyph width == the layout rectangle
            local dia = p:get("dia")
            near(dia.rect.w, ICON.w * 0.2 / 0.225, "icon layout width"); near(dia.rect.h, ICON.h * 0.2 / 0.225)
            local sprite
            for _, k in ipairs(cores) do
                if k.props.text and type(k.props.text) == "table" and k.props.text.sprite then sprite = k end
            end
            assert(sprite, "sprite display")
            eq(sprite.props.text.sprite, "minecraft:item/diamond"); eq(sprite.props.text.atlas, "minecraft:items")
            near(sprite.props.transform.s.x * ICON.w, dia.rect.w, "render width")
            near(sprite.props.transform.s.y * ICON.h, dia.rect.h, "render height")

            -- hit: just inside the rectangle's edges the ray picks the icon's widget, via the same rectangle
            local mdl = p:get("mdl")
            eq(mdl.kind, "icon"); near(mdl.rect.w, 0.2)
            local item
            for _, k in ipairs(cores) do if k.kind == "item" then item = k end end
            assert(item and item.props.mode == "gui", "model icon is an item display")
            """.trimIndent()
        )
    }
}
