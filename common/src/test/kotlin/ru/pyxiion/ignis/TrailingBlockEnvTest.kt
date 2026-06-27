package ru.pyxiion.ignis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.luaj.vm2.LuaClosure
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Print
import org.luaj.vm2.lib.jse.JsePlatform

/**
 * Repro for the trailing-block `_ENV` bug.
 *
 * When a chunk uses the Nova trailing-block syntax `foo() \{ body }`,
 * the lambda's `_ENV` upvalue ends up pointing at a different table
 * than the chunk's `globals` after the chunk's frame closes. This
 * causes `print` and other globals to resolve to nil when the
 * callback is invoked later.
 *
 * The bug is NOT present for regular `function() ... end` callbacks.
 */
class TrailingBlockEnvTest {

    /**
     * Trailing block: store callback, return it, invoke after chunk closes.
     * If `_ENV` is broken, `print` will be nil inside the callback.
     */
    @Test
    fun `trailing block callback loses _ENV after chunk frame closes`() {
        val state = JsePlatform.standardState()
        val globals = state.globals

        val script = """
            --# nova syntax
            local result
            (function(cb)
                result = cb
            end) \{ return type(print) }
            return result
        """.trimIndent()

        val chunk = state.load(script, "@test_trailing")
        val callback: LuaValue = chunk.call()
        assertNotNull(callback)
        assertTrue(callback is LuaClosure, "callback should be a LuaClosure, got ${callback::class}")

        val cbClosure = callback as LuaClosure
        assertTrue(cbClosure.upValues.isNotEmpty(), "callback should have upvalues")
        val upvals = cbClosure.upValues
        val upvalDescs = cbClosure.p.upvalues
        println("=== Trailing-block callback upvalues ===")
        println("upvalue count: ${upvals.size}")
        for (i in upvals.indices) {
            val desc = upvalDescs[i]
            val v = upvals[i].getValue()
            println("  upvalue[$i]: instack=${desc.instack} idx=${desc.idx} name=${desc.name} " +
                "valueType=${v.typename()} valueId=${System.identityHashCode(v)} " +
                "isGlobals=${v === globals} hasPrint=${!v.get("print").isnil()}")
        }
        val cbEnv = upvals[0].getValue()
        val same = cbEnv === globals
        val envHasPrint = !cbEnv.get("print").isnil()

        // Dump bytecode of the chunk and the callback
        val chunkProto = (chunk as LuaClosure).p
        println("=== Chunk bytecode ===")
        Print.printFunction(chunkProto, true)
        println("=== Callback bytecode ===")
        Print.printFunction(cbClosure.p, true)

        // Re-check the upvalue identity right before calling
        val recheckEnv = upvals[0].getValue()
        val recheckSame = recheckEnv === globals
        val recheckHasPrint = !recheckEnv.get("print").isnil()
        val globalsHasPrint = !globals.get("print").isnil()
        println("=== Right before callback.call() ===")
        println("  upvalues[0] === globals: $recheckSame")
        println("  upvalues[0].tojstring(): ${recheckEnv.tojstring()}")
        println("  globals.tojstring(): ${globals.tojstring()}")
        println("  upvalues[0].get('print').isnil(): ${recheckEnv.get("print").isnil()}")
        println("  globals.get('print').isnil(): $globalsHasPrint")
        println("  upvalues[0].id=${System.identityHashCode(recheckEnv)} globals.id=${System.identityHashCode(globals)}")

        // Invoking the callback now (after chunk frame closed) should not throw
        val result = callback.call()

        println("=== After callback.call() ===")
        println("  result: ${result.tojstring()}")
        println("  globals.get('print') type: ${globals.get("print").typename()}")
        println("  upvalues[0].id=${System.identityHashCode(upvals[0].getValue())} globals.id=${System.identityHashCode(globals)}")
        println("  upvalues[0] === globals: ${upvals[0].getValue() === globals}")

        assertEquals("function", result.tojstring(),
            "type(print) inside trailing-block callback should return 'function'; " +
            "if it returns 'nil', the callback's _ENV lost 'print'")
    }

    /**
     * Compare bytecode of trailing-block lambda vs equivalent regular function.
     * They should produce identical protos/upvalue layouts.
     */
    @Test
    fun `trailing block bytecode matches regular function`() {
        val state = JsePlatform.standardState()

        val lambdaScript = """
            --# nova syntax
            local result
            (function(cb)
                result = cb
            end) \{ type(print) }
            return result
        """.trimIndent()

        val regularScript = """
            local result
            (function(cb)
                result = cb
            end)(function() type(print) end)
            return result
        """.trimIndent()

        val lambdaChunk = state.load(lambdaScript, "@lambda") as LuaClosure
        val regularChunk = state.load(regularScript, "@regular") as LuaClosure

        println("=== LAMBDA chunk ===")
        Print.printFunction(lambdaChunk.p, true)
        println("=== REGULAR chunk ===")
        Print.printFunction(regularChunk.p, true)

        // Compare the callback prototype (child function 1)
        val lambdaCallbackProto = lambdaChunk.p.p[1]
        val regularCallbackProto = regularChunk.p.p[1]

        println("=== LAMBDA callback proto ===")
        Print.printFunction(lambdaCallbackProto, true)
        println("=== REGULAR callback proto ===")
        Print.printFunction(regularCallbackProto, true)

        assertEquals(regularCallbackProto.upvalues.size, lambdaCallbackProto.upvalues.size,
            "upvalue count should match")
        for (i in regularCallbackProto.upvalues.indices) {
            val ru = regularCallbackProto.upvalues[i]
            val lu = lambdaCallbackProto.upvalues[i]
            assertEquals(ru.instack, lu.instack, "upvalue[$i] instack mismatch")
            assertEquals(ru.idx, lu.idx, "upvalue[$i] idx mismatch")
            assertEquals(ru.name.tojstring(), lu.name.tojstring(), "upvalue[$i] name mismatch")
        }
        assertTrue(regularCallbackProto.code.contentEquals(lambdaCallbackProto.code),
            "callback bytecode should match")
    }

    /**
     * Anonymous function (no trailing block): should work fine.
     */
    @Test
    fun `anonymous function callback keeps _ENV after chunk frame closes`() {
        val state = JsePlatform.standardState()
        val globals = state.globals

        val script = """
            local function store(cb) return cb end
            return store(function() return type(print) end)
        """.trimIndent()

        val chunk = state.load(script, "@test_anon")
        val callback: LuaValue = chunk.call()
        assertNotNull(callback)
        assertTrue(callback is LuaClosure)

        val cbClosure = callback as LuaClosure
        assertTrue(cbClosure.upValues.isNotEmpty())
        val cbEnv = cbClosure.upValues[0].getValue()
        assertTrue(cbEnv === globals,
            "anonymous callback _ENV should be the same as globals. " +
            "closureEnv.id=${System.identityHashCode(cbEnv)} " +
            "globals.id=${System.identityHashCode(globals)}"
        )

        val result = callback.call()
        assertEquals("function", result.tojstring())
    }
}
