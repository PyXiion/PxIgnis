package ru.pyxiion.ignis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import ru.pyxiion.ignis.api.MetaTableRegistry
import ru.pyxiion.ignis.api.RegionWrapper

class RegionTest {

    @Test
    fun `regionKeys contains expected entries`() {
        val expected = setOf(
            "id", "world", "getBounds", "players", "entities",
            "setBounds", "on", "off", "destroy", "contains",
        )
        val keys = RegionWrapper.regionKeys.toSet()
        assertEquals(expected, keys)
    }

    @Test
    fun `regionKeys has no duplicates`() {
        val keys = RegionWrapper.regionKeys
        assertEquals(keys.size, keys.toSet().size, "regionKeys should not contain duplicates")
    }

    @Test
    fun `MetaTableRegistry exposes region slot`() {
        val meta = MetaTableRegistry.get("region")
        assertTrue(meta.istable())
    }

    @Test
    fun `region metatable is shared across get calls`() {
        val a = MetaTableRegistry.get("region")
        val b = MetaTableRegistry.get("region")
        assertTrue(a === b)
    }

    @Test
    fun `region metatable is independent of hologram`() {
        val region = MetaTableRegistry.get("region")
        val hologram = MetaTableRegistry.get("hologram")
        assertTrue(region !== hologram)
    }

    @Test
    fun `region metatable is independent of world`() {
        val region = MetaTableRegistry.get("region")
        val world = MetaTableRegistry.get("world")
        assertTrue(region !== world)
    }

    @Test
    fun `AABB contains point inside`() {
        val box = Box(Vec3d(0.0, 0.0, 0.0), Vec3d(10.0, 10.0, 10.0))
        assertTrue(box.contains(Vec3d(5.0, 5.0, 5.0)))
    }

    @Test
    fun `AABB does not contain point outside`() {
        val box = Box(Vec3d(0.0, 0.0, 0.0), Vec3d(10.0, 10.0, 10.0))
        assertFalse(box.contains(Vec3d(15.0, 5.0, 5.0)))
        assertFalse(box.contains(Vec3d(-1.0, 5.0, 5.0)))
    }

    @Test
    fun `AABB contains point on min boundary`() {
        val box = Box(Vec3d(0.0, 0.0, 0.0), Vec3d(10.0, 10.0, 10.0))
        assertTrue(box.contains(Vec3d(0.0, 0.0, 0.0)))
    }

    @Test
    fun `AABB does not contain point on max boundary`() {
        val box = Box(Vec3d(0.0, 0.0, 0.0), Vec3d(10.0, 10.0, 10.0))
        assertFalse(box.contains(Vec3d(10.0, 10.0, 10.0)))
    }

    @Test
    fun `AABB contains point just below max boundary`() {
        val box = Box(Vec3d(0.0, 0.0, 0.0), Vec3d(10.0, 10.0, 10.0))
        assertTrue(box.contains(Vec3d(9.999, 5.0, 5.0)))
    }

    @Test
    fun `AABB does not contain point just outside on each axis`() {
        val box = Box(Vec3d(0.0, 0.0, 0.0), Vec3d(10.0, 10.0, 10.0))
        assertFalse(box.contains(Vec3d(10.0001, 5.0, 5.0)))
        assertFalse(box.contains(Vec3d(5.0, 10.0001, 5.0)))
        assertFalse(box.contains(Vec3d(5.0, 5.0, 10.0001)))
    }

    @Test
    fun `AABB with negative coordinates works correctly`() {
        val box = Box(Vec3d(-10.0, -10.0, -10.0), Vec3d(-5.0, -5.0, -5.0))
        assertTrue(box.contains(Vec3d(-7.5, -7.5, -7.5)))
        assertFalse(box.contains(Vec3d(-4.0, -7.5, -7.5)))
        assertFalse(box.contains(Vec3d(0.0, 0.0, 0.0)))
    }

    @Test
    fun `AABB with large coordinates works correctly`() {
        val box = Box(Vec3d(100000.0, 64.0, -50000.0), Vec3d(100100.0, 128.0, -49900.0))
        assertTrue(box.contains(Vec3d(100050.0, 100.0, -49950.0)))
        assertFalse(box.contains(Vec3d(50.0, 100.0, -49950.0)))
    }

    @Test
    fun `chunk math floorDiv handles negative coordinates`() {
        val x = -5
        val chunkX = Math.floorDiv(x, 16)
        assertEquals(-1, chunkX, "floorDiv(-5, 16) should be -1, not 0")
    }

    @Test
    fun `chunk math floorDiv handles positive coordinates`() {
        val x = 20
        val chunkX = Math.floorDiv(x, 16)
        assertEquals(1, chunkX)
    }

    @Test
    fun `chunk math floorDiv at chunk boundary`() {
        assertEquals(0, Math.floorDiv(0, 16))
        assertEquals(0, Math.floorDiv(15, 16))
        assertEquals(1, Math.floorDiv(16, 16))
        assertEquals(-1, Math.floorDiv(-1, 16))
        assertEquals(-1, Math.floorDiv(-16, 16))
        assertEquals(-2, Math.floorDiv(-17, 16))
    }

    @Test
    fun `chunks in 16x16x16 region cover exactly 1 chunk`() {
        val box = Box(Vec3d(0.0, 0.0, 0.0), Vec3d(15.9, 15.9, 15.9))
        val minCx = Math.floorDiv(box.minX.toInt(), 16)
        val minCz = Math.floorDiv(box.minZ.toInt(), 16)
        val maxCx = Math.floorDiv(box.maxX.toInt(), 16)
        val maxCz = Math.floorDiv(box.maxZ.toInt(), 16)
        assertEquals(0, minCx)
        assertEquals(0, minCz)
        assertEquals(0, maxCx)
        assertEquals(0, maxCz)
    }

    @Test
    fun `chunks in 32x32 region cover 4 chunks`() {
        val box = Box(Vec3d(0.0, 0.0, 0.0), Vec3d(31.9, 0.0, 31.9))
        val minCx = Math.floorDiv(box.minX.toInt(), 16)
        val minCz = Math.floorDiv(box.minZ.toInt(), 16)
        val maxCx = Math.floorDiv(box.maxX.toInt(), 16)
        val maxCz = Math.floorDiv(box.maxZ.toInt(), 16)
        assertEquals(0, minCx)
        assertEquals(0, minCz)
        assertEquals(1, maxCx)
        assertEquals(1, maxCz)
    }

    @Test
    fun `chunks spanning negative and positive span two chunks`() {
        val box = Box(Vec3d(-1.0, 0.0, -1.0), Vec3d(1.0, 0.0, 1.0))
        val minCx = Math.floorDiv(box.minX.toInt(), 16)
        val minCz = Math.floorDiv(box.minZ.toInt(), 16)
        val maxCx = Math.floorDiv(box.maxX.toInt(), 16)
        val maxCz = Math.floorDiv(box.maxZ.toInt(), 16)
        assertEquals(-1, minCx)
        assertEquals(-1, minCz)
        assertEquals(0, maxCx)
        assertEquals(0, maxCz)
    }

    @Test
    fun `region destroy callback fires through fire method`() {
        val fired = mutableListOf<String>()
        val handler = object : org.luaj.vm2.lib.ZeroArgFunction() {
            override fun call(): org.luaj.vm2.LuaValue {
                fired.add("destroyed")
                return org.luaj.vm2.LuaValue.NIL
            }
        }
        val handlers = mutableMapOf<String, MutableList<org.luaj.vm2.LuaFunction>>()
        handlers.getOrPut("destroy") { mutableListOf() }.add(handler)

        val list = handlers["destroy"]!!
        list.forEach { it.call() }

        assertEquals(1, fired.size)
        assertEquals("destroyed", fired[0])
    }

    @Test
    fun `throttle counter behavior is independent per callback`() {
        val counter1 = intArrayOf(0)
        val counter2 = intArrayOf(0)

        val fire = { c: IntArray -> if (c[0] <= 0) c[0] = c[0] + 1 }

        fire(counter1)
        fire(counter1)
        fire(counter2)
        assertEquals(1, counter1[0])
        assertEquals(1, counter2[0])
    }

    @Test
    fun `two-arg normalization computes correct min and max`() {
        val a = Vec3d(100.0, 64.0, 100.0)
        val b = Vec3d(0.0, 0.0, 0.0)
        val min = Vec3d(minOf(a.x, b.x), minOf(a.y, b.y), minOf(a.z, b.z))
        val max = Vec3d(maxOf(a.x, b.x), maxOf(a.y, b.y), maxOf(a.z, b.z))
        val box = Box(min, max)
        assertEquals(0.0, box.minX)
        assertEquals(0.0, box.minY)
        assertEquals(0.0, box.minZ)
        assertEquals(100.0, box.maxX)
        assertEquals(64.0, box.maxY)
        assertEquals(100.0, box.maxZ)
    }

    @Test
    fun `two-arg normalization with equal corners`() {
        val a = Vec3d(5.0, 5.0, 5.0)
        val b = Vec3d(5.0, 5.0, 5.0)
        val minX = minOf(a.x, b.x); val maxX = maxOf(a.x, b.x)
        val minY = minOf(a.y, b.y); val maxY = maxOf(a.y, b.y)
        val minZ = minOf(a.z, b.z); val maxZ = maxOf(a.z, b.z)
        val box = Box(Vec3d(minX, minY, minZ), Vec3d(maxX, maxY, maxZ))
        assertEquals(5.0, box.minX)
        assertEquals(5.0, box.maxX)
    }

    @Test
    fun `tick handler fires through fire`() {
        val fired = intArrayOf(0)
        val handler = object : org.luaj.vm2.lib.ZeroArgFunction() {
            override fun call(): org.luaj.vm2.LuaValue {
                fired[0]++
                return org.luaj.vm2.LuaValue.NIL
            }
        }
        val handlers = mutableMapOf<String, MutableList<org.luaj.vm2.LuaFunction>>()
        handlers.getOrPut("tick") { mutableListOf() }.add(handler)
        handlers["tick"]?.forEach { it.call() }
        assertEquals(1, fired[0])
    }

    @Test
    fun `clearing handlers prevents tick from firing`() {
        val fired = intArrayOf(0)
        val handler = object : org.luaj.vm2.lib.ZeroArgFunction() {
            override fun call(): org.luaj.vm2.LuaValue {
                fired[0]++
                return org.luaj.vm2.LuaValue.NIL
            }
        }
        val handlers = mutableMapOf<String, MutableList<org.luaj.vm2.LuaFunction>>()
        handlers.getOrPut("tick") { mutableListOf() }.add(handler)
        handlers.clear()
        handlers["tick"]?.forEach { it.call() }
        assertEquals(0, fired[0])
    }
}
