package ru.pyxiion.ignis

import ru.pyxiion.luanova.vm2.LuaState
import ru.pyxiion.luanova.vm2.LuaValue
import ru.pyxiion.luanova.vm2.lib.jse.JsePlatform
import kotlin.test.BeforeTest
import kotlin.test.Test

/** `core:display` against a fake `world:display` and `mc` scheduler: records the calls the library makes. */
class DisplayLuaTest {
    private val state: LuaState = JsePlatform.standardState()

    @BeforeTest
    fun setup() {
        run(
            """
            mc = { tasks = {}, n = 0, now = 0 }
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

            Core = {}
            Core.__index = Core
            function Core:set(p) self.log[#self.log + 1] = p; for k, v in pairs(p) do self.props[k] = v end end
            function Core:moveTo(pos, yaw, pitch) self.moved = { pos, yaw, pitch } end
            function Core:attach(e, off) self.attached = { e, off } end
            function Core:detach() self.attached = nil end
            function Core:show(p) self.shown = p end
            function Core:hide(p) self.hidden = p end
            function Core:remove() self.valid = false end
            function Core:onClick(fn) self.click = fn end
            function Core:onAttack(fn) self.attack = fn end

            cores = {}
            world = {
                display = function(_, kind, pos, props)
                    local c = setmetatable({ kind = kind, pos = pos, props = {}, log = {}, valid = true, id = #cores + 1 }, Core)
                    for k, v in pairs(props or {}) do c.props[k] = v end
                    cores[#cores + 1] = c
                    return c
                end,
            }

            function eq(a, b, what) assert(a == b, (what or "") .. ": expected " .. tostring(b) .. ", got " .. tostring(a)) end
            function near(a, b, what) assert(math.abs(a - b) < 1e-6, (what or "") .. ": expected " .. tostring(b) .. ", got " .. tostring(a)) end
            function last(c) return c.log[#c.log] end
            """.trimIndent()
        )
        val source = javaClass.getResource("/display.lua")!!.readText()
        state.globals.set("display", state.load(source, "display.lua").call())
    }

    private fun run(chunk: String): LuaValue = state.load(chunk, "t").call()

    @Test
    fun `quaternion and matrix math`() = run(
        """
        local q = display.quat
        local r = q.axis({0, 1, 0}, math.pi / 2)
        near(r.y, math.sin(math.pi / 4)); near(r.w, math.cos(math.pi / 4))
        -- 90 degrees about Y maps +X to -Z
        local m = display.mat.compose(nil, r, nil, nil)
        local p = display.mat.apply(m, {1, 0, 0})
        near(p.x, 0); near(p.z, -1)
        -- mul(a, b) applies b first
        local a = q.axis({0, 1, 0}, math.pi / 2)
        local b = q.axis({1, 0, 0}, math.pi / 2)
        local mm = display.mat.mul(display.mat.compose(nil, a), display.mat.compose(nil, b))
        local v = display.mat.apply(mm, {0, 1, 0})   -- X90 sends +Y to +Z, then Y90 sends +Z to +X
        near(v.x, 1); near(v.y, 0); near(v.z, 0, "z")
        local qm = display.mat.apply(display.mat.compose(nil, q.mul(a, b)), {0, 1, 0})
        near(qm.x, 1)
        -- compose: translate * scale
        local c = display.mat.compose({1, 2, 3}, nil, {2, 2, 2})
        local w = display.mat.apply(c, {1, 1, 1})
        near(w.x, 3); near(w.y, 4); near(w.z, 5)
        -- euler yaw
        local e = q.euler(math.pi / 2, 0, 0)
        near(e.y, r.y); near(e.w, r.w)
        -- slerp halfway between identity and 90 degrees is 45 degrees
        local s = q.slerp(q.identity(), r, 0.5)
        near(s.y, math.sin(math.pi / 8))
        """
    ).let {}

    @Test
    fun `helpers apply explicit defaults and fold scale into transform`() = run(
        """
        local h = display.item(world, {0, 0, 0}, "minecraft:stone", { scale = 0.4, glow = true, billboard = "center" })
        local c = cores[1]
        eq(c.kind, "item"); eq(c.props.item, "minecraft:stone"); eq(c.props.interp, 0); eq(c.props.teleport, 0)
        eq(c.props.brightness, 15); eq(c.props.glow, true); eq(c.props.billboard, "center")
        eq(c.props.transform.s, 0.4); eq(c.props.scale, nil)
        eq(h.kind, "item"); eq(h.valid, true)
        display.item(world, {0, 0, 0}, "minecraft:stone", { brightness = false })
        eq(cores[2].props.brightness, false)
        local t = display.text(world, {0, 0, 0}, "hi")
        eq(cores[3].props.billboard, "center"); eq(cores[3].props.text, "hi")
        local qd = display.quad(world, {0, 0, 0}, 0xFFFF0000, 2, 0.5)
        eq(cores[4].props.background, 0xFFFF0000); eq(cores[4].props.text, " ")
        near(cores[4].props.transform.s.x, 16); near(cores[4].props.transform.s.y, 0.5 * 4 / 0.9)
        """
    ).let {}

    @Test
    fun `animate sets interp, fade removes after ticks, life removes`() = run(
        """
        local h = display.block(world, {0, 0, 0}, "minecraft:stone")
        h:animate({ transform = { s = 2 } }, 7)
        eq(last(cores[1]).interp, 7); eq(last(cores[1]).transform.s, 2)
        h:fade(5, true)
        eq(last(cores[1]).transform.s, 0); eq(last(cores[1]).interp, 5)
        tick(4); eq(cores[1].valid, true)
        tick(1); eq(cores[1].valid, false)

        local t = display.text(world, {0, 0, 0}, "x")
        t:fade(3, true)
        eq(last(cores[2]).opacity, 4); eq(last(cores[2]).background, 0)
        local l = display.item(world, {0, 0, 0}, "minecraft:stone")
        l:life(2); tick(1); eq(cores[3].valid, true); tick(1); eq(cores[3].valid, false)
        """
    ).let {}

    @Test
    fun `flyTo clamps teleport duration and moves`() = run(
        """
        local h = display.item(world, {0, 0, 0}, "minecraft:stone")
        h:flyTo({1, 2, 3}, 100)
        eq(last(cores[1]).teleport, 59); eq(cores[1].moved[1][2], 2)
        """
    ).let {}

    @Test
    fun `spin steps stay under 180 degrees and stop on remove`() = run(
        """
        local h = display.item(world, {0, 0, 0}, "minecraft:stone", { scale = 1 })
        h:spin({0, 1, 0}, 10)
        local c = cores[1]
        local prev = nil
        for i = 1, 12 do
            tick(1)
        end
        local n = 0
        for _, p in ipairs(c.log) do
            if p.transform and p.transform.r then
                n = n + 1
                if prev then
                    local d = math.abs(prev.x * p.transform.r.x + prev.y * p.transform.r.y + prev.z * p.transform.r.z + prev.w * p.transform.r.w)
                    assert(d > math.cos(math.pi / 2) + 1e-6, "step of 180 degrees or more")
                end
                prev = p.transform.r
                eq(p.interp, 2)
            end
        end
        assert(n >= 5, "spin steps: " .. n)
        h:remove()
        local before = #c.log
        tick(10)
        eq(#c.log, before)
        """
    ).let {}

    @Test
    fun `rig multiplies parent transforms down the tree`() = run(
        """
        local rig = display.group(world, {0, 0, 0}, {
            body = { kind = "block", block = "minecraft:stone", transform = { t = {1, 0, 0} } },
            head = { kind = "item", item = "minecraft:stone", parent = "body", transform = { t = {0, 1, 0} } },
        })
        local body, head
        for _, c in ipairs(cores) do if c.kind == "block" then body = c else head = c end end
        near(last(body).transform[4], 1)
        near(last(head).transform[4], 1); near(last(head).transform[8], 1)
        rig:transform({ t = {10, 0, 0} }, 4)
        near(last(body).transform[4], 11); eq(last(body).interp, 4)
        near(last(head).transform[4], 11); near(last(head).transform[8], 1)
        -- rotating the parent about Y by 90 degrees swings the child offset
        rig:transform({ l = display.quat.axis({0, 0, 1}, math.pi / 2) }, 0)
        near(last(head).transform[4], 0 + -1 * 1, "head x"); near(last(head).transform[8], 1 * 0 + 1 * 0 + 1 * 1 * 0 + 1)
        rig:node("head"):set({ transform = { t = {0, 2, 0} } })
        near(last(head).transform[4], -2); near(last(head).transform[8], 1)
        rig:attach("host", {0, 1, 0})
        eq(body.attached[1], "host"); eq(head.attached[1], "host")
        rig:remove()
        eq(body.valid, false); eq(head.valid, false); eq(rig.valid, false)
        """
    ).let {}

    @Test
    fun `rig rejects unknown parents and cycles`() = run(
        """
        assert(not pcall(display.group, world, {0, 0, 0}, { a = { kind = "block", parent = "zzz" } }))
        assert(not pcall(display.group, world, {0, 0, 0}, {
            a = { kind = "block", parent = "b" }, b = { kind = "block", parent = "a" } }))
        """
    ).let {}

    @Test
    fun `timeline samples keyframes with easing and plays on a rig`() = run(
        """
        local anim = display.timeline {
            loop = false, step = 4,
            tracks = { head = { { t = 0, rot = {0, 0, 0} }, { t = 20, rot = {0, 1.57, 0}, ease = "inOut" } } },
        }
        eq(anim.length, 20)
        local mid = anim:sample("head", 10)
        near(mid.l.y, math.sin(1.57 / 4), "half")
        local ending = anim:sample("head", 99)
        near(ending.l.y, math.sin(1.57 / 2))

        local rig = display.group(world, {0, 0, 0}, { head = { kind = "item", item = "minecraft:stone" } })
        local c = cores[1]
        local play = display.play(rig, anim)
        eq(last(c).interp, 0)
        local n = #c.log
        tick(1); eq(#c.log, n + 1); eq(last(c).interp, 4)
        tick(4); tick(4); tick(4); tick(4)
        assert(play.done, "timeline should have finished")
        near(display.mat.apply({ table.unpack(last(c).transform) }, { 1, 0, 0 }).z, -math.sin(1.57), "final pose")
        local after = #c.log
        tick(10); eq(#c.log, after)
        """
    ).let {}

    @Test
    fun `looping timeline keeps running until stopped`() = run(
        """
        local anim = display.timeline { loop = true, step = 5,
            tracks = { self = { { t = 0, pos = {0, 0, 0} }, { t = 10, pos = {0, 2, 0} }, { t = 20, pos = {0, 0, 0} } } } }
        local h = display.item(world, {0, 0, 0}, "minecraft:stone")
        local play = display.play(h, anim)
        for _ = 1, 60 do tick(1) end
        assert(not play.done)
        local n = #cores[1].log
        play:stop(); tick(20)
        eq(#cores[1].log, n); assert(play.done)
        """
    ).let {}

    @Test
    fun `bar fills through scale and stays left aligned`() = run(
        """
        local bar = display.bar(world, {0, 0, 0}, { w = 2.0, h = 0.1, fg = 0xFF00FF00, bg = 0x80000000 })
        local fg, bg
        for _, c in ipairs(cores) do if c.props.background == 0xFF00FF00 then fg = c else bg = c end end
        assert(fg and bg)
        local full = last(fg).transform
        bar:set(0.5, 10)
        local m = last(fg).transform
        eq(last(fg).interp, 10)
        -- left edge stays at -w/2: translation.x - scale.x/2 (in blocks) is constant
        local function left(tr) return tr[4] - tr[1] / 8 / 2 end
        near(left(m), left(full), "left edge")
        near(m[1] * 2, full[1], "half width")
        bar:attach("mob", {0, 2, 0})
        eq(fg.attached[1], "mob"); eq(bg.attached[1], "mob")
        bar:remove()
        eq(fg.valid, false)
        """
    ).let {}
}
