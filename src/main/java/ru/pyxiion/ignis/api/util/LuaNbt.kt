package ru.pyxiion.ignis.api.util

import net.minecraft.nbt.ByteTag
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.DoubleTag
import net.minecraft.nbt.IntTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.LongTag
import net.minecraft.nbt.NumericTag
import net.minecraft.nbt.StringTag
import net.minecraft.nbt.Tag
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs

/**
 * Lua value <-> NBT, with explicit typing so that values survive a round trip.
 *
 * Lua to NBT: `boolean` -> byte (0/1), `string` -> string, integral `number` -> int (long beyond the int range),
 * any other `number` -> double, a non-empty sequence `{a, b, c}` -> list, any other table (including the empty one) ->
 * compound with string keys. `nil` values (and `nil` keys' entries) are skipped.
 *
 * NBT to Lua: a byte tag is a `boolean` when it holds 0 or 1 (this codec only writes bytes for booleans; a byte
 * written by something else with another value comes back as a number), other numeric tags are numbers (long beyond
 * 2^53 loses precision, since Lua numbers are doubles).
 */
object LuaNbt {
    private const val MAX_DEPTH = 64

    /** Returns null for `nil` (the caller removes the key). */
    fun toTag(value: LuaValue): Tag? = toTag(value, 0)

    fun toCompound(value: LuaValue, what: String): CompoundTag {
        val tag = if (value.isnil()) CompoundTag() else toTag(value, 0)
        return tag as? CompoundTag
            ?: throw LuaError("$what: a table with string keys is expected")
    }

    fun fromTag(tag: Tag): LuaValue = when (tag) {
        is ByteTag -> {
            val b = tag.byteValue().toInt()
            if (b == 0 || b == 1) LuaValue.valueOf(b == 1) else LuaValue.valueOf(b)
        }
        is NumericTag -> {
            if (tag is DoubleTag || tag is net.minecraft.nbt.FloatTag) LuaValue.valueOf(tag.doubleValue())
            else {
                val l = tag.longValue()
                if (l in Int.MIN_VALUE..Int.MAX_VALUE) LuaValue.valueOf(l.toInt()) else LuaValue.valueOf(l.toDouble())
            }
        }
        is StringTag -> LuaValue.valueOf(tag.value())
        is ListTag -> LuaTable().also { t -> tag.forEachIndexed { i, e -> t.rawset(i + 1, fromTag(e)) } }
        is CompoundTag -> LuaTable().also { t -> for (k in tag.keySet()) t.rawset(k, fromTag(tag.get(k)!!)) }
        else -> LuaValue.NIL // arrays (byte/int/long) are not produced by this codec
    }

    private fun toTag(value: LuaValue, depth: Int): Tag? {
        if (depth > MAX_DEPTH) throw LuaError("data: nesting is too deep (or the table refers to itself)")
        return when (value.type()) {
            LuaValue.TNIL -> null
            LuaValue.TBOOLEAN -> ByteTag.valueOf(value.toboolean())
            LuaValue.TSTRING -> StringTag.valueOf(value.tojstring())
            LuaValue.TNUMBER -> number(value)
            LuaValue.TTABLE -> table(value as LuaTable, depth)
            else -> throw LuaError("data: unsupported value of type ${value.typename()}")
        }
    }

    private fun number(v: LuaValue): Tag {
        val d = v.todouble()
        if (d == Math.rint(d) && !d.isInfinite() && Math.abs(d) < 9.0e18) {
            val l = d.toLong()
            return if (l in Int.MIN_VALUE..Int.MAX_VALUE) IntTag.valueOf(l.toInt()) else LongTag.valueOf(l)
        }
        return DoubleTag.valueOf(d)
    }

    private fun table(t: LuaTable, depth: Int): Tag {
        var count = 0
        var k: Varargs = t.next(LuaValue.NIL)
        val keys = ArrayList<LuaValue>()
        while (!k.arg1().isnil()) {
            if (!k.arg(2).isnil()) { keys.add(k.arg1()); count++ }
            k = t.next(k.arg1())
        }
        val n = t.length()
        if (count > 0 && count == n) { // keys are exactly 1..n
            val list = ListTag()
            for (i in 1..n) list.add(toTag(t.get(i), depth + 1)!!)
            return list
        }
        val compound = CompoundTag()
        for (key in keys) {
            if (key.type() != LuaValue.TSTRING) throw LuaError("data: table keys must be strings (or a pure sequence), got ${key.tojstring()}")
            compound.put(key.tojstring(), toTag(t.get(key), depth + 1)!!)
        }
        return compound
    }
}
