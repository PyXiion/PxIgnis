package ru.pyxiion.ignis

import org.luaj.vm2.LuaState
import org.luaj.vm2.LuaValue
import org.luaj.vm2.lib.jse.JsePlatform
import ru.pyxiion.ignis.api.MetaTableRegistry
import ru.pyxiion.ignis.api.Vector
import ru.pyxiion.ignis.api.initVecMeta
import kotlin.test.BeforeTest
import kotlin.test.Test

/** `core:particles` against fake `mc`, `world` and `async`: counts driver ticks, not server ticks. */
class ParticlesLuaTest {
    private val state: LuaState = JsePlatform.standardState()

    @BeforeTest
    fun setup() {
        initVecMeta(MetaTableRegistry.VEC)
        state.globals.set("vec", luaVarFunction { a -> Vector.of(a.arg(1).checkdouble(), a.arg(2).checkdouble(), a.arg(3).checkdouble()) })
        run(
            """
            package.loaded.async = {
                promise = function()
                    return {
                        resolve = function(s) s.resolved = true; return true end,
                        wait = function(s) return s.resolved end,
                    }
                end,
            }

            mc = { tasks = {}, n = 0 }
            function mc.scheduleRepeating(d, i, cb) mc.n = mc.n + 1; mc.tasks[mc.n] = { cb = cb }; return mc.n end
            function mc.cancelTask(id) mc.tasks[id].cancelled = true end
            function mc.tick(id)
                local t = mc.tasks[id or mc.n]
                if not t.cancelled then t.cb() end
            end

            T = 0
            function step() T = T + 1; mc.tick() end

            log = {}
            world = {
                emit = function(self, p, pos, o) log[#log + 1] = { p, pos, o } end,
                emitMany = function(self, p, ps, o) for _, pos in ipairs(ps) do log[#log + 1] = { p, pos, o } end end,
            }
            P = vec(0, 0, 0)  -- any userdata stands in for a particle

            -- runs fn(ctx, k) for d ticks (default 1)
            function leaf(fn, d) return fx.ticking(fn, d or 1) end
            function eq(a, b, what) assert(a == b, (what or "") .. ": expected " .. tostring(b) .. ", got " .. tostring(a)) end
            function near(a, b, what) assert(math.abs(a - b) < 1e-9, (what or "") .. ": expected " .. tostring(b) .. ", got " .. tostring(a)) end

            """.trimIndent()
        )
        val source = javaClass.getResource("/particles.lua")!!.readText()
        state.globals.set("fx", state.load(source, "particles.lua").call())
    }

    private fun run(chunk: String): LuaValue = state.load(chunk, "t").call()

    @Test
    fun `seq runs children back to back, one tick each`() = run(
        """
        local calls = {}
        local h = fx.play(fx.seq {
            fx.wait(2),
            leaf(function(ctx, k) calls[#calls + 1] = { T, k } end, 2),
        }, { at = vec(0, 0, 0), world = world })
        for i = 1, 3 do step(); assert(not h.done, "done too early at " .. i) end
        eq(#calls, 1); eq(calls[1][1], 3); eq(calls[1][2], 0)
        step()
        eq(#calls, 2); eq(calls[2][1], 4); eq(calls[2][2], 1)
        assert(h.done, "not done on tick 4")
        """
    ).let {}

    @Test
    fun `par finishes with its longest child`() = run(
        """
        local a, b = 0, 0
        local h = fx.play(fx.par {
            leaf(function() a = a + 1 end, 1),
            leaf(function() b = b + 1 end, 3),
        }, { at = vec(0, 0, 0), world = world })
        step(); step(); assert(not h.done)
        step()
        assert(h.done); eq(a, 1); eq(b, 3)
        """
    ).let {}

    @Test
    fun `times restarts a fresh instance`() = run(
        """
        local ks = {}
        local h = fx.play(fx.times(3, leaf(function(ctx, k) ks[#ks + 1] = k end, 2)),
            { at = vec(0, 0, 0), world = world })
        for i = 1, 5 do step(); assert(not h.done) end
        step()
        assert(h.done)
        eq(table.concat(ks, ","), "0,1,0,1,0,1")
        """
    ).let {}

    @Test
    fun `loop never finishes and stop is idempotent`() = run(
        """
        local n = 0
        local h = fx.play(fx.loop(leaf(function() n = n + 1 end)), { at = vec(0, 0, 0), world = world })
        for i = 1, 100 do step() end
        assert(not h.done); eq(n, 100)
        eq(h:stop(), true)
        assert(h.done)
        assert(mc.tasks[1].cancelled, "task not cancelled")
        eq(h._promise.resolved, true, "promise")
        step(); eq(n, 100, "leaf ran after stop")
        eq(h:stop(), false)
        """
    ).let {}

    @Test
    fun `follow tracks the entity and stops when it is removed`() = run(
        """
        local ent = { pos = vec(1, 2, 3), dir = vec(0, 0, 1), world = world, removed = false }
        local seen, calls = {}, 0
        local h = fx.play(fx.loop(leaf(function(ctx) calls = calls + 1; seen[#seen + 1] = ctx.origin.x end)),
            { at = ent, follow = true })
        step(); ent.pos = vec(5, 2, 3); step()
        eq(seen[1], 1); eq(seen[2], 5)
        ent.removed = true
        step()
        assert(h.done); eq(calls, 2)
        step(); eq(calls, 2)
        """
    ).let {}

    @Test
    fun `without follow the origin is a snapshot and removal does not stop`() = run(
        """
        local ent = { pos = vec(1, 2, 3), dir = vec(0, 0, 1), world = world, removed = false }
        local seen = {}
        local h = fx.play(fx.loop(leaf(function(ctx) seen[#seen + 1] = ctx.origin.x end)), { at = ent })
        step(); ent.pos = vec(9, 2, 3); ent.removed = true; step()
        eq(seen[1], 1); eq(seen[2], 1)
        assert(not h.done)
        """
    ).let {}

    @Test
    fun `with shifts origin and viewers only inside`() = run(
        """
        local V, W = { "v" }, { "w" }
        local h = fx.play(fx.par {
            fx.with({ offset = vec(0, 2, 0), viewers = V }, fx.par {
                leaf(function(ctx)
                    ctx:emit(P, ctx.origin)
                    ctx:emit(P, ctx.origin, { viewers = W })
                end),
            }),
            leaf(function(ctx) ctx:emit(P, ctx.origin) end),
        }, { at = vec(1, 0, 0), world = world })
        step()
        eq(#log, 3)
        eq(log[1][2].y, 2); eq(log[1][3].viewers, V)
        eq(log[2][2].y, 2); eq(log[2][3].viewers, W)
        eq(log[3][2].y, 0); eq(log[3][3], nil)
        """
    ).let {}

    @Test
    fun `a failing leaf stops the effect and rethrows`() = run(
        """
        local h = fx.play(leaf(function() error("boom") end), { at = vec(0, 0, 0), world = world })
        local ok, err = pcall(mc.tick)
        assert(not ok); assert(tostring(err):find("boom", 1, true), tostring(err))
        assert(h.done); assert(mc.tasks[1].cancelled)
        """
    ).let {}

    @Test
    fun `circle puts points around the origin`() = run(
        """
        fx.play(fx.circle { particle = P, radius = 2, points = 4 }, { at = vec(0, 0, 0), world = world })
        step()
        eq(#log, 4)
        local want = { { 2, 0 }, { 0, 2 }, { -2, 0 }, { 0, -2 } }
        for i, w in ipairs(want) do
            near(log[i][2].x, w[1], "x" .. i); near(log[i][2].y, 0, "y" .. i); near(log[i][2].z, w[2], "z" .. i)
        end
        """
    ).let {}

    @Test
    fun `burst grows its shell each tick`() = run(
        """
        local h = fx.play(fx.burst { particle = P, radius = 3, duration = 3, points = 10 }, { at = vec(0, 0, 0), world = world })
        for k = 0, 2 do
            log = {}
            step()
            eq(#log, 10)
            for _, e in ipairs(log) do near(e[2]:length(), 3 * (k + 1) / 3, "tick " .. k) end
        end
        assert(h.done)
        """
    ).let {}

    @Test
    fun `line spaces points between origin and target`() = run(
        """
        fx.play(fx.line { particle = P, to = vec(4, 0, 0), step = 1 }, { at = vec(0, 0, 0), world = world })
        step()
        eq(#log, 5)
        for i = 1, 5 do near(log[i][2].x, i - 1, "x" .. i) end
        """
    ).let {}

    @Test
    fun `ticking counts from zero per run and without ticks runs until stopped`() = run(
        """
        local ks = {}
        local h = fx.play(fx.ticking(function(ctx, k) ks[#ks + 1] = k end), { at = vec(0, 0, 0), world = world })
        for i = 1, 5 do step() end
        assert(not h.done); eq(table.concat(ks, ","), "0,1,2,3,4")
        assert(not pcall(fx.ticking, function() end, 0), "ticks = 0 accepted")
        """
    ).let {}

    @Test
    fun `effect without a finishing step runs until stopped`() = run(
        """
        local made, ticks = 0, 0
        local h = fx.play(fx.effect(function()
            made = made + 1
            return function() ticks = ticks + 1 end
        end), { at = vec(0, 0, 0), world = world })
        for i = 1, 50 do step() end
        assert(not h.done); eq(made, 1); eq(ticks, 50)
        """
    ).let {}

    @Test
    fun `factory state is fresh per run and outside state survives restarts`() = run(
        """
        local made, seenInside, outside = 0, {}, 0
        local node = fx.effect(function()
            made = made + 1
            local inside = 0                       -- per run
            return function()
                inside = inside + 1; outside = outside + 1
                seenInside[#seenInside + 1] = inside
                return inside >= 2
            end
        end)
        local h = fx.play(fx.times(3, node), { at = vec(0, 0, 0), world = world })
        for i = 1, 6 do step() end
        assert(h.done)
        eq(made, 3); eq(outside, 6)
        eq(table.concat(seenInside, ","), "1,2,1,2,1,2")
        """
    ).let {}

    @Test
    fun `bad arguments fail with the fx prefix`() = run(
        """
        local node = fx.wait(1)
        local cases = {
            function() fx.wait(0) end,
            function() fx.seq {} end,
            function() fx.circle {} end,
            function() fx.effect(1) end,
            function() fx.play(fx.effect(function() return 1 end), { at = vec(0, 0, 0), world = world }) end,
            function() fx.play(node, { at = vec(0, 0, 0) }) end,
            function() fx.play(node, { at = vec(0, 0, 0), world = world, follow = true }) end,
            function() fx.play(node, {}) end,
        }
        for i, f in ipairs(cases) do
            local ok, err = pcall(f)
            assert(not ok, "case " .. i .. " did not fail")
            assert(tostring(err):find("fx.", 1, true), "case " .. i .. ": " .. tostring(err))
        end
        """
    ).let {}
}
