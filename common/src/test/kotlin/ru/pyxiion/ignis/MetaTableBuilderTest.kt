package ru.pyxiion.ignis

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.VarArgFunction
import ru.pyxiion.ignis.api.util.metaTable

class MetaTableBuilderTest {

    @Test
    fun `parent prop is reachable through inherit`() {
        val parent = metaTable<Any> {
            prop("name") { LuaValue.valueOf("parent") }
        }
        val child = metaTable<Any> {
            inherit { parent.meta }
        }

        val t = LuaTable()
        t.setmetatable(child.meta)
        t.rawset("__pxrp_object", LuaValue.userdataOf(Any()))

        assertEquals("parent", t.get("name").tojstring())
    }

    @Test
    fun `child prop overrides parent prop`() {
        val parent = metaTable<Any> {
            prop("name") { LuaValue.valueOf("parent") }
        }
        val child = metaTable<Any> {
            inherit { parent.meta }
            prop("name") { LuaValue.valueOf("child") }
        }

        val t = LuaTable()
        t.setmetatable(child.meta)
        t.rawset("__pxrp_object", LuaValue.userdataOf(Any()))

        assertEquals("child", t.get("name").tojstring())
    }

    @Test
    fun `parent method is reachable through get fallback`() {
        val parent = metaTable<Any> {
            method("greet") { args ->
                val self = args.arg(1)
                LuaValue.valueOf("hello, ${self.rawget("__pxrp_type").tojstring()}")
            }
        }
        val child = metaTable<Any> {
            inherit { parent.meta }
        }

        val t = LuaTable()
        t.setmetatable(child.meta)
        t.rawset("__pxrp_type", LuaValue.valueOf("test"))
        t.rawset("__pxrp_object", LuaValue.userdataOf(Any()))

        val fn = t.get("greet")
        assertTrue(fn.isfunction())
        assertEquals("hello, test", fn.call(t).tojstring())
    }

    @Test
    fun `parent get fallback returns method before parent __index`() {
        val parent = metaTable<Any> {
            prop("name") { LuaValue.valueOf("propValue") }
            method("name") { args ->
                LuaValue.valueOf("methodValue")
            }
        }
        val child = metaTable<Any> {
            inherit { parent.meta }
        }

        val t = LuaTable()
        t.setmetatable(child.meta)
        t.rawset("__pxrp_object", LuaValue.userdataOf(Any()))

        assertTrue(t.get("name").isfunction())
        assertEquals("methodValue", t.get("name").call(t).tojstring())
    }

    @Test
    fun `parent lazy caches on child instance`() {
        var callCount = 0
        val parent = metaTable<Any> {
            lazy("computed") {
                callCount++
                LuaValue.valueOf(callCount.toDouble())
            }
        }
        val child = metaTable<Any> {
            inherit { parent.meta }
        }

        val t = LuaTable()
        t.setmetatable(child.meta)
        t.rawset("__pxrp_object", LuaValue.userdataOf(Any()))

        assertEquals(1.0, t.get("computed").todouble(), 0.001)
        assertEquals(1, callCount)
        assertEquals(1.0, t.get("computed").todouble(), 0.001)
        assertEquals(1, callCount)
    }

    @Test
    fun `both child and parent methods are reachable on child`() {
        val parent = metaTable<Any> {
            method("parentOnly") { args ->
                LuaValue.valueOf("parent")
            }
        }
        val child = metaTable<Any> {
            inherit { parent.meta }
            method("childOnly") { args ->
                LuaValue.valueOf("child")
            }
        }

        val t = LuaTable()
        t.setmetatable(child.meta)
        t.rawset("__pxrp_type", LuaValue.valueOf("test"))
        t.rawset("__pxrp_object", LuaValue.userdataOf(Any()))

        assertEquals("parent", t.get("parentOnly").call(t).tojstring())
        assertEquals("child", t.get("childOnly").call(t).tojstring())
    }

    @Test
    fun `parent setter is reachable through newindex fallthrough`() {
        var observed: String? = null
        val parent = metaTable<Any> {
            prop(
                "writable",
                get = { LuaValue.valueOf(observed ?: "unset") },
                set = { v -> observed = v.tojstring() }
            )
        }
        val child = metaTable<Any> {
            inherit { parent.meta }
        }

        val t = LuaTable()
        t.setmetatable(child.meta)
        t.rawset("__pxrp_object", LuaValue.userdataOf(Any()))

        t.set("writable", LuaValue.valueOf("written"))
        assertEquals("written", observed)
    }

    @Test
    fun `unknown key returns nil`() {
        val parent = metaTable<Any> {
            prop("known") { LuaValue.valueOf("known") }
        }
        val child = metaTable<Any> {
            inherit { parent.meta }
        }

        val t = LuaTable()
        t.setmetatable(child.meta)
        t.rawset("__pxrp_object", LuaValue.userdataOf(Any()))

        assertTrue(t.get("unknown").isnil())
    }

    @Test
    fun `pairs iterates both child and parent keys`() {
        val parent = metaTable<Any> {
            prop("parentKey") { LuaValue.valueOf("pv") }
            method("parentFn") { args -> LuaValue.valueOf("pf") }
        }
        val child = metaTable<Any> {
            inherit { parent.meta }
            prop("childKey") { LuaValue.valueOf("cv") }
        }

        val t = LuaTable()
        t.setmetatable(child.meta)
        t.rawset("__pxrp_object", LuaValue.userdataOf(Any()))

        val keys = mutableListOf<String>()
        val pairsFn = t.getmetatable().get("__pairs").checkfunction()
        val triple = pairsFn.call(t)
        val iterFn = triple.arg(1).checkfunction()
        val iterState = triple.arg(2)
        var prevKey = triple.arg(3)
        while (true) {
            val result = iterFn.call(iterState, prevKey)
            val nextKey = result.arg(1)
            if (nextKey.isnil()) break
            keys.add(nextKey.tojstring())
            prevKey = nextKey
        }

        assertTrue("childKey" in keys)
        assertTrue("parentKey" in keys)
        assertTrue("parentFn" in keys)
        assertFalse("__index" in keys)
    }

    @Test
    fun `multi-level inherit propagates props`() {
        val base = metaTable<Any> {
            prop("baseProp") { LuaValue.valueOf("base") }
        }
        val mid = metaTable<Any> {
            inherit { base.meta }
            prop("midProp") { LuaValue.valueOf("mid") }
        }
        val top = metaTable<Any> {
            inherit { mid.meta }
            prop("topProp") { LuaValue.valueOf("top") }
        }

        val t = LuaTable()
        t.setmetatable(top.meta)
        t.rawset("__pxrp_object", LuaValue.userdataOf(Any()))

        assertEquals("base", t.get("baseProp").tojstring())
        assertEquals("mid", t.get("midProp").tojstring())
        assertEquals("top", t.get("topProp").tojstring())
    }

    @Test
    fun `pairs on multi-level inherit includes all ancestor keys`() {
        val base = metaTable<Any> {
            prop("baseKey") { LuaValue.valueOf("bv") }
        }
        val mid = metaTable<Any> {
            inherit { base.meta }
            prop("midKey") { LuaValue.valueOf("mv") }
        }
        val top = metaTable<Any> {
            inherit { mid.meta }
            prop("topKey") { LuaValue.valueOf("tv") }
        }

        val t = LuaTable()
        t.setmetatable(top.meta)
        t.rawset("__pxrp_object", LuaValue.userdataOf(Any()))

        val keys = mutableListOf<String>()
        val pairsFn = t.getmetatable().get("__pairs").checkfunction()
        val triple = pairsFn.call(t)
        val iterFn = triple.arg(1).checkfunction()
        val iterState = triple.arg(2)
        var prevKey = triple.arg(3)
        while (true) {
            val result = iterFn.call(iterState, prevKey)
            val nextKey = result.arg(1)
            if (nextKey.isnil()) break
            keys.add(nextKey.tojstring())
            prevKey = nextKey
        }

        assertTrue("topKey" in keys)
        assertTrue("midKey" in keys)
        assertTrue("baseKey" in keys)
        assertEquals(3, keys.size)
    }
}
