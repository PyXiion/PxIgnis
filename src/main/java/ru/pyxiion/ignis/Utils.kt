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


fun LuaTable.asSequence(): Sequence<Pair<LuaValue, LuaValue>> = sequence {
    var key: LuaValue = LuaValue.NIL
    while (true) {
        val next = this@asSequence.next(key)
        key = next.arg1()
        if (key.isnil()) break
        yield(Pair(key, next.arg(2)))
    }
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

fun Iterable<Pair<LuaValue, LuaValue>>?.toLuaTable(): LuaTable {
    return LuaTable().apply {
        this@toLuaTable?.forEach { (k, v) -> set(k, v) }
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
        val err = lastError ?: LuaError(r.arg(2).optjstring("Unknown coroutine error"))
        PxIgnis.logger.error("$context: ${err.message}", err)
    }
    return r
}

inline fun Logger.debug(f: () -> String) {
    if (isDebugEnabled) {
        debug(f())
    }
}