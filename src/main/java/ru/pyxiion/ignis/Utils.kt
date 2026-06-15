package ru.pyxiion.ignis

import me.lucko.fabric.api.permissions.v0.Permissions
import net.minecraft.command.CommandSource
import net.minecraft.entity.Entity
import net.minecraft.nbt.NbtByte
import net.minecraft.nbt.NbtByteArray
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtDouble
import net.minecraft.nbt.NbtElement
import net.minecraft.nbt.NbtFloat
import net.minecraft.nbt.NbtInt
import net.minecraft.nbt.NbtIntArray
import net.minecraft.nbt.NbtList
import net.minecraft.nbt.NbtLong
import net.minecraft.nbt.NbtLongArray
import net.minecraft.nbt.NbtShort
import net.minecraft.nbt.NbtString
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.VarArgFunction

fun LuaValue.checkstringlist(): List<String> {
    val table = this.checktable()
    val result = mutableListOf<String>()

    var index = 1
    while (true) {
        val value = table.rawget(index++)
        if (value.isnil()) break

        require(value.isstring()) { "Expected string at index $index, got ${value.typename()}" }

        result.add(value.tojstring())
    }

    return result
}

fun Entity.checkPermission(permission: String): Boolean = Permissions.check(this, permission)

fun CommandSource.checkPermission(permission: String): Boolean = Permissions.check(this, permission)

fun luaTableOf(vararg items: Pair<String, LuaValue>): LuaTable {
    return LuaTable().apply {
        items.forEach { (k, v) ->
            this.set(k, v)
        }
    }
}

fun luaArrayOf(vararg items: LuaValue): LuaTable {
    return LuaTable().apply {
        items.forEachIndexed { i, v -> this.set(i + 1, v) }
    }
}

@Suppress("NOTHING_TO_INLINE")
inline fun ((Varargs) -> Varargs).asVarArgFunction() = object : VarArgFunction() {
    override fun invoke(args: Varargs): Varargs = this@asVarArgFunction(args)
}

@JvmName("asVarArgFunctionVoid")
@Suppress("NOTHING_TO_INLINE")
inline fun ((Varargs) -> Unit).asVarArgFunction() = object : VarArgFunction() {
    override fun invoke(args: Varargs): Varargs {
        this@asVarArgFunction(args)
        return NIL
    }
}

fun LuaValue.toVec3d(): Vec3d {
    val t = checktable()
    val x = t.get("x").let { if (it.isnumber()) it.todouble() else t.get(1).checkdouble() }
    val y = t.get("y").let { if (it.isnumber()) it.todouble() else t.get(2).checkdouble() }
    val z = t.get("z").let { if (it.isnumber()) it.todouble() else t.get(3).checkdouble() }
    return Vec3d(x, y, z)
}

fun LuaValue.toBlockPos(): BlockPos {
    val v = toVec3d()
    return BlockPos.ofFloored(v.x, v.y, v.z)
}

internal fun nbtToLua(element: NbtElement): LuaValue {
    return when (element) {
        is NbtCompound -> {
            val t = LuaTable()
            for (key in element.keys) {
                val value = element.get(key) ?: continue
                t.set(key, nbtToLua(value))
            }
            t
        }
        is NbtList -> {
            val t = LuaTable()
            for (i in 0 until element.size) {
                t.set(i + 1, nbtToLua(element.get(i)))
            }
            t
        }
        is NbtByte -> LuaValue.valueOf(element.value.toInt() != 0)
        is NbtShort -> LuaValue.valueOf(element.value.toInt())
        is NbtInt -> LuaValue.valueOf(element.value)
        is NbtLong -> LuaValue.valueOf(element.value.toDouble())
        is NbtFloat -> LuaValue.valueOf(element.value.toDouble())
        is NbtDouble -> LuaValue.valueOf(element.value)
        is NbtString -> LuaValue.valueOf(element.value)
        is NbtByteArray -> {
            val t = LuaTable()
            for ((i, b) in element.getByteArray().withIndex()) {
                t.set(i + 1, LuaValue.valueOf(b.toInt() and 0xFF))
            }
            t
        }
        is NbtIntArray -> {
            val t = LuaTable()
            for ((i, v) in element.getIntArray().withIndex()) {
                t.set(i + 1, LuaValue.valueOf(v))
            }
            t
        }
        is NbtLongArray -> {
            val t = LuaTable()
            for ((i, v) in element.getLongArray().withIndex()) {
                t.set(i + 1, LuaValue.valueOf(v.toDouble()))
            }
            t
        }
        else -> LuaValue.NIL
    }
}

internal fun luaToNbt(value: LuaValue): NbtElement {
    return when {
        value.isboolean() -> NbtByte.of(if (value.toboolean()) 1 else 0)
        value.isint() -> NbtInt.of(value.toint())
        value.islong() -> NbtLong.of(value.tolong())
        value.isnumber() -> NbtDouble.of(value.todouble())
        value.isstring() -> NbtString.of(value.tojstring())
        value.istable() -> {
            val t = value.checktable()
            var hasStringKeys = false
            var k = LuaValue.NIL
            while (true) {
                val next = t.next(k)
                if (next.isnil(1)) break
                val key = next.arg(1)
                if (!key.isint() || key.toint() < 1) {
                    hasStringKeys = true
                    break
                }
                k = key
            }

            if (!hasStringKeys && t.length() > 0) {
                val list = NbtList()
                for (i in 1..t.length()) {
                    list.add(luaToNbt(t.get(i)))
                }
                list
            } else {
                val compound = NbtCompound()
                var k2 = LuaValue.NIL
                while (true) {
                    val next = t.next(k2)
                    if (next.isnil(1)) break
                    val key = next.arg(1).tojstring()
                    val v = next.arg(2)
                    compound.put(key, luaToNbt(v))
                    k2 = next.arg(1)
                }
                compound
            }
        }
        else -> throw LuaError("writeNbt: неподдерживаемый тип Lua: ${value.typename()}")
    }
}