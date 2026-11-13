package ru.pyxiion.ignis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.minecraft.nbt.ByteTag
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.DoubleTag
import net.minecraft.nbt.IntTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.LongTag
import net.minecraft.nbt.StringTag
import ru.pyxiion.luanova.vm2.LuaError
import ru.pyxiion.luanova.vm2.LuaTable
import ru.pyxiion.luanova.vm2.LuaValue
import ru.pyxiion.ignis.api.util.LuaNbt

class LuaNbtTest {
    private fun roundTrip(v: LuaValue) = LuaNbt.fromTag(LuaNbt.toTag(v)!!)

    private fun seq(vararg v: LuaValue) = LuaTable().also { t -> v.forEachIndexed { i, x -> t.rawset(i + 1, x) } }

    @Test
    fun `scalars are typed`() {
        assertEquals(ByteTag.valueOf(true), LuaNbt.toTag(LuaValue.TRUE))
        assertEquals(IntTag.valueOf(5), LuaNbt.toTag(LuaValue.valueOf(5)))
        assertEquals(IntTag.valueOf(5), LuaNbt.toTag(LuaValue.valueOf(5.0)))
        assertEquals(LongTag.valueOf(1L shl 40), LuaNbt.toTag(LuaValue.valueOf((1L shl 40).toDouble())))
        assertEquals(DoubleTag.valueOf(1.5), LuaNbt.toTag(LuaValue.valueOf(1.5)))
        assertEquals(StringTag.valueOf("a"), LuaNbt.toTag(LuaValue.valueOf("a")))
        assertNull(LuaNbt.toTag(LuaValue.NIL))
    }

    @Test
    fun `scalars round trip`() {
        assertEquals(LuaValue.TRUE, roundTrip(LuaValue.TRUE))
        assertEquals(LuaValue.FALSE, roundTrip(LuaValue.FALSE))
        assertTrue(roundTrip(LuaValue.TRUE).isboolean())
        assertEquals(7, roundTrip(LuaValue.valueOf(7)).toint())
        assertEquals(1.5, roundTrip(LuaValue.valueOf(1.5)).todouble())
        assertEquals("hi", roundTrip(LuaValue.valueOf("hi")).tojstring())
        assertEquals((1L shl 40).toDouble(), roundTrip(LuaValue.valueOf((1L shl 40).toDouble())).todouble())
    }

    @Test
    fun `sequence is a list and keyed table is a compound`() {
        assertTrue(LuaNbt.toTag(seq(LuaValue.valueOf(1), LuaValue.valueOf("x"))) is ListTag)
        val t = LuaTable().also { it.rawset("k", LuaValue.TRUE) }
        assertTrue(LuaNbt.toTag(t) is CompoundTag)
        assertTrue(LuaNbt.toTag(LuaTable()) is CompoundTag)
    }

    @Test
    fun `nested tables round trip`() {
        val inner = LuaTable().also { it.rawset("b", seq(LuaValue.TRUE, LuaValue.valueOf(2.5))) }
        val root = LuaTable().also {
            it.rawset("a", inner)
            it.rawset("s", LuaValue.valueOf("v"))
            it.rawset("n", LuaValue.valueOf(3))
        }
        val back = roundTrip(root)
        assertEquals("v", back.get("s").tojstring())
        assertEquals(3, back.get("n").toint())
        val list = back.get("a").get("b")
        assertEquals(2, list.length())
        assertEquals(LuaValue.TRUE, list.get(1))
        assertEquals(2.5, list.get(2).todouble())
    }

    @Test
    fun `nil entries are skipped and bad keys rejected`() {
        val t = LuaTable().also { it.rawset("a", LuaValue.valueOf(1)); it.rawset("b", LuaValue.NIL) }
        assertEquals(setOf("a"), (LuaNbt.toTag(t) as CompoundTag).keySet())
        val mixed = seq(LuaValue.valueOf(1)).also { it.rawset("k", LuaValue.valueOf(2)) }
        assertFailsWith<LuaError> { LuaNbt.toTag(mixed) }
        val holes = LuaTable().also { it.rawset(1, LuaValue.valueOf(1)); it.rawset(3, LuaValue.valueOf(3)) }
        assertFailsWith<LuaError> { LuaNbt.toTag(holes) }
    }

    @Test
    fun `self reference is rejected`() {
        val t = LuaTable()
        t.rawset("self", t)
        assertFailsWith<LuaError> { LuaNbt.toTag(t) }
    }
}
