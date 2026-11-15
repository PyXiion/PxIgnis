package ru.pyxiion.ignis

import ru.pyxiion.luanova.vm2.LuaState
import ru.pyxiion.luanova.vm2.LuaValue
import ru.pyxiion.luanova.vm2.lib.jse.JsePlatform
import kotlin.test.BeforeTest
import kotlin.test.Test

/** `core:ref`: refs, watchers, effects and computed values. */
class RefLuaTest {
    private val state: LuaState = JsePlatform.standardState()

    @BeforeTest
    fun setup() {
        run(
            """
            warnings = {}
            mc = { warn = function(ctx, text) warnings[#warnings + 1] = text end }
            function eq(a, b, what) assert(a == b, (what or "") .. ": expected " .. tostring(b) .. ", got " .. tostring(a)) end
            """.trimIndent()
        )
        val source = javaClass.getResource("/ref.lua")!!.readText()
        state.globals.set("ref", state.load(source, "ref.lua").call())
    }

    private fun run(chunk: String): LuaValue = state.load(chunk, "t").call()

    @Test
    fun `scalar get set update`() {
        run(
        """
        local r = ref.new(1)
        eq(r:get(), 1)
        r:set(2); eq(r:get(), 2)
        r:update(function(v) return v * 10 end); eq(r:get(), 20)
        assert(ref.isRef(r)); assert(not ref.isRef({})); assert(not ref.isRef(5))
        local seen = {}
        r:watch(function(n, o) seen[#seen + 1] = o .. ">" .. n end)
        eq(#seen, 0, "no call on registration")
        r:set(20); eq(#seen, 0, "unchanged value")
        r:set(21); r:update(function(v) return v + 1 end)
        eq(table.concat(seen, ","), "20>21,21>22")
        """.trimIndent()
        )
    }

    @Test
    fun `tables are replaced whole`() {
        run(
        """
        local t = { n = 1 }
        local r = ref.new(t)
        local calls = 0
        r:watch(function(new, old) calls = calls + 1; eq(old, t); eq(new.n, 2) end)
        r:set(t); eq(calls, 0, "same table is not a change")
        r:update(function(old) return { n = old.n + 1 } end)
        eq(calls, 1)
        eq(t.n, 1, "old table untouched")
        """.trimIndent()
        )
    }

    @Test
    fun `multiple watchers and cleanup`() {
        run(
        """
        local r = ref.new(0)
        local log = {}
        local off1 = r:watch(function() log[#log + 1] = "a" end)
        local off2 = r:watch(function() log[#log + 1] = "b" end)
        r:set(1); eq(table.concat(log), "ab")
        off1(); off1()
        r:set(2); eq(table.concat(log), "abb")
        off2()
        r:set(3); eq(table.concat(log), "abb")

        -- disconnecting during notification: a watcher removed by an earlier one does not run
        local log2, offB = {}, nil
        r:watch(function() log2[#log2 + 1] = "x"; offB() end)
        offB = r:watch(function() log2[#log2 + 1] = "y" end)
        r:set(4); eq(table.concat(log2), "x")
        -- a watcher disconnecting itself
        local n, offSelf = 0, nil
        offSelf = r:watch(function() n = n + 1; offSelf() end)
        r:set(5); r:set(6); eq(n, 1)
        """.trimIndent()
        )
    }

    @Test
    fun `watcher failure does not corrupt the ref`() {
        run(
        """
        local r = ref.new(1)
        local ok = {}
        r:watch(function() error("boom") end)
        r:watch(function(n) ok[#ok + 1] = n end)
        r:set(2)
        eq(r:get(), 2); eq(#ok, 1); eq(#warnings, 1)
        assert(warnings[1]:find("boom"))
        r:set(3); eq(r:get(), 3); eq(#ok, 2)
        -- failing update function leaves the value alone
        assert(not pcall(r.update, r, function() error("bad") end))
        eq(r:get(), 3)
        """.trimIndent()
        )
    }

    @Test
    fun `effect tracks and switches dependencies`() {
        run(
        """
        local useA, a, b = ref.new(true), ref.new("a1"), ref.new("b1")
        local runs = {}
        local dispose = ref.effect(function()
            runs[#runs + 1] = useA:get() and a:get() or b:get()
        end)
        eq(table.concat(runs, ","), "a1")
        b:set("b2"); eq(#runs, 1, "b not a dependency yet")
        a:set("a2"); eq(table.concat(runs, ","), "a1,a2")
        useA:set(false); eq(table.concat(runs, ","), "a1,a2,b2")
        a:set("a3"); eq(#runs, 3, "a dropped")
        b:set("b3"); eq(table.concat(runs, ","), "a1,a2,b2,b3")
        dispose(); dispose()
        b:set("b4"); eq(#runs, 4)
        """.trimIndent()
        )
    }

    @Test
    fun `effects are nested safely and survive failure`() {
        run(
        """
        local outer, inner = ref.new(0), ref.new(0)
        local innerRuns, outerRuns = 0, 0
        ref.effect(function()
            outer:get(); outerRuns = outerRuns + 1
            ref.effect(function() inner:get(); innerRuns = innerRuns + 1 end)
        end)
        eq(outerRuns, 1); eq(innerRuns, 1)
        inner:set(1); eq(innerRuns, 2); eq(outerRuns, 1, "inner change does not rerun outer")
        outer:set(1); eq(outerRuns, 2); eq(innerRuns, 3)
        inner:set(2); eq(innerRuns, 4, "old inner effect was disposed")

        local flaky, runs = ref.new(false), 0
        ref.effect(function() runs = runs + 1; if flaky:get() then error("nope") end end)
        flaky:set(true); eq(#warnings, 1)
        flaky:set(false); eq(runs, 3, "still subscribed after failing")

        -- self-modifying effect terminates
        local loop = ref.new(0)
        ref.effect(function() loop:set(loop:get() + 1) end)
        assert(loop:peek() <= 101)
        """.trimIndent()
        )
    }

    @Test
    fun `computed`() {
        run(
        """
        local price, qty = ref.new(3), ref.new(2)
        local total = ref.computed(function() return price:get() * qty:get() end)
        assert(ref.isRef(total))
        eq(total:get(), 6)
        local seen = {}
        total:watch(function(n, o) seen[#seen + 1] = o .. ">" .. n end)
        qty:set(5); eq(total:get(), 15); eq(table.concat(seen, ","), "6>15")
        assert(not pcall(total.set, total, 1)); assert(not pcall(total.update, total, function() return 1 end))
        -- chained: an effect and another computed depend on a computed
        local double = ref.computed(function() return total:get() * 2 end)
        local last
        ref.effect(function() last = double:get() end)
        price:set(1); eq(total:get(), 5); eq(double:get(), 10); eq(last, 10)
        total:dispose()
        price:set(100); eq(total:get(), 5)
        """.trimIndent()
        )
    }
}
