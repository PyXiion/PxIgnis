package ru.pyxiion.ignis

import me.lucko.fabric.api.permissions.v0.Permissions
import net.minecraft.command.CommandSource
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
import org.luaj.vm2.LuaThread
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.VarArgFunction
import org.slf4j.Logger

fun CommandSource.checkPermission(permission: String): Boolean = Permissions.check(this, permission)

fun luaTableOf(vararg items: Pair<String, LuaValue>): LuaTable {
    return LuaTable().apply {
        items.forEach { (k, v) ->
            this.set(k, v)
        }
    }
}

fun ((Varargs) -> Varargs).asVarArgFunction() = object : VarArgFunction() {
    override fun invoke(args: Varargs): Varargs = this@asVarArgFunction(args)
}

@JvmName("asVarArgFunctionVoid")
fun ((Varargs) -> Unit).asVarArgFunction() = object : VarArgFunction() {
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

inline fun <reified T> LuaValue.unwrap(): T =
    checktable().rawget("__pxrp_object").checkuserdata() as T

inline fun <reified T> LuaValue.unwrapOrNull(): T? {
    if (!istable()) return null
    val obj = checktable().rawget("__pxrp_object")
    return if (obj.isuserdata()) obj.checkuserdata() as? T else null
}

inline fun LuaTable.forEach(action: (k: LuaValue, v: LuaValue) -> Unit) {
    var k = LuaValue.NIL
    while (true) {
        val next = next(k)
        if (next.isnil(1)) break
        k = next.arg(1)
        action(k, next.arg(2))
    }
}

fun Iterable<LuaValue>?.toLuaArray(): LuaTable {
    return LuaTable().apply {
        this@toLuaArray?.forEachIndexed { i, value -> set(i+1, value) }
    }
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
            element.map(::nbtToLua).toLuaArray()
        }
        is NbtByte -> LuaValue.valueOf(element.value.toInt() != 0)
        is NbtShort -> LuaValue.valueOf(element.value.toInt())
        is NbtInt -> LuaValue.valueOf(element.value)
        is NbtLong -> LuaValue.valueOf(element.value.toDouble())
        is NbtFloat -> LuaValue.valueOf(element.value.toDouble())
        is NbtDouble -> LuaValue.valueOf(element.value)
        is NbtString -> LuaValue.valueOf(element.value)
        is NbtByteArray -> {
            element.byteArray.map { LuaValue.valueOf(it.toInt() and 0xFF) }.toLuaArray()
        }
        is NbtIntArray -> {
            element.intArray.map { LuaValue.valueOf(it) }.toLuaArray()
        }
        is NbtLongArray -> {
            // TODO: introduce LuaLong
            element.longArray.map { LuaValue.valueOf(it.toInt()) }.toLuaArray()
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

fun LuaThread.resumeOrThrow(args: Varargs): Varargs {
    val r = resume(args)
    if (!r.arg1().toboolean()) throw LuaError(r.arg(2).optjstring("Unknown coroutine error"))
    return r
}

fun LuaThread.resumeOrLog(args: Varargs, context: String): Varargs {
    val r = resume(args)
    if (!r.arg1().toboolean()) {
        val err = LuaError(r.arg(2).optjstring("Unknown coroutine error"))
        PxIgnis.logger.error("$context: ${err.message}", err)
    }
    return r
}

inline fun Logger.debug(f: () -> String) {
    if (isDebugEnabled) {
        debug(f())
    }
}