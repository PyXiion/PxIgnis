package ru.pyxiion.ignis

import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import ru.pyxiion.ignis.api.MetaTableRegistry
import ru.pyxiion.ignis.api.Vector
import ru.pyxiion.ignis.api.Vector.Companion.toVec3d
import ru.pyxiion.ignis.api.initVecMeta
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame

class VectorTest {
    @BeforeTest
    fun initMeta() {
        // MetaTableRegistry.init() тянет за собой Minecraft; метатаблице вектора он не нужен.
        initVecMeta(MetaTableRegistry.VEC)
    }

    @Test
    fun `zero results are independent objects`() {
        val a = Vector.of(1.0, 2.0, 3.0)
        val zero = a.sub(a) as Vector
        zero.set("x", LuaValue.valueOf(5.0))

        val other = a.sub(a) as Vector
        assertNotSame(zero, other)
        assertEquals(0.0, other.x)
        assertEquals(0.0, Vector.of(0.0, 0.0, 0.0).x)
    }

    @Test
    fun `assigning an unknown field is an error`() {
        val v = Vector.of(1.0, 2.0, 3.0)
        assertFailsWith<LuaError> { v.set("w", LuaValue.valueOf(1.0)) }
    }

    @Test
    fun `vector accepts named and positional tables and numbers in arithmetic`() {
        val named = LuaTable().apply {
            set("x", LuaValue.valueOf(1.0)); set("y", LuaValue.valueOf(2.0)); set("z", LuaValue.valueOf(3.0))
        }
        val positional = LuaTable().apply {
            set(1, LuaValue.valueOf(1.0)); set(2, LuaValue.valueOf(2.0)); set(3, LuaValue.valueOf(3.0))
        }
        assertEquals(2.0, named.toVec3d().y)
        assertEquals(3.0, positional.toVec3d().z)

        val sum = Vector.of(1.0, 1.0, 1.0).add(LuaValue.valueOf(2.0)) as Vector
        assertEquals(3.0, sum.x)
        assertFailsWith<LuaError> { LuaValue.valueOf(2.0).toVec3d() }
    }
}
