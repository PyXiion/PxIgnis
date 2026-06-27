package ru.pyxiion.ignis

import ru.pyxiion.ignis.IgnisPlatform
import net.minecraft.commands.CommandSource
import org.luaj.vm2.*
import org.luaj.vm2.lib.OneArgFunction
import org.luaj.vm2.lib.ThreeArgFunction
import org.luaj.vm2.lib.TwoArgFunction
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.lib.ZeroArgFunction
import org.slf4j.Logger

fun CommandSource.checkPermission(permission: String): Boolean = IgnisPlatform.instance.checkPermission(this, permission)

fun luaTableOf(vararg items: Pair<String, LuaValue>): LuaTable {
    return LuaTable().apply {
        items.forEach { (k, v) ->
            this.set(k, v)
        }
    }
}

class KotlinZeroArgBridge(private val f: () -> LuaValue) : ZeroArgFunction() {
    override fun call(): LuaValue = f()
}

class KotlinOneArgBridge(private val f: (LuaValue) -> LuaValue) : OneArgFunction() {
    override fun call(arg1: LuaValue): LuaValue = f(arg1)
}

class KotlinTwoArgBridge(private val f: (LuaValue, LuaValue) -> LuaValue) : TwoArgFunction() {
    override fun call(arg1: LuaValue, arg2: LuaValue): LuaValue = f(arg1, arg2)
}

class KotlinThreeArgBridge(private val f: (LuaValue, LuaValue, LuaValue) -> LuaValue) : ThreeArgFunction() {
    override fun call(arg1: LuaValue, arg2: LuaValue, arg3: LuaValue): LuaValue = f(arg1, arg2, arg3)
}

class KotlinVarArgBridge(private val f: (args: Varargs) -> Varargs) : VarArgFunction() {
    override fun invoke(args: Varargs): Varargs = f(args)
}

fun luaFunctionZero(f: () -> LuaValue) = KotlinZeroArgBridge(f)
fun luaFunction(f: (LuaValue) -> LuaValue): LuaFunction = KotlinOneArgBridge(f)
fun luaFunction(f: (LuaValue, LuaValue) -> LuaValue): LuaFunction = KotlinTwoArgBridge(f)
fun luaFunction(f: (LuaValue, LuaValue, LuaValue) -> LuaValue): LuaFunction = KotlinThreeArgBridge(f)


fun ((Varargs) -> Varargs).asVarArgFunction() = KotlinVarArgBridge(this)

fun luaVarFunction(f: (args: Varargs) -> Varargs): LuaFunction = KotlinVarArgBridge(f)

inline fun luaFunctionNil(crossinline f: (LuaValue) -> Unit): LuaFunction =
    luaFunction { v: LuaValue ->
        f(v)
        LuaValue.NIL
    }

inline fun luaFunctionNil(crossinline f: (LuaValue, LuaValue) -> Unit): LuaFunction =
    KotlinTwoArgBridge { arg1, arg2 ->
        f(arg1, arg2)
        LuaValue.NIL
    }

inline fun luaFunctionNil(crossinline f: (LuaValue, LuaValue, LuaValue) -> Unit): LuaFunction =
    KotlinThreeArgBridge { arg1, arg2, arg3 ->
        f(arg1, arg2, arg3)
        LuaValue.NIL
    }

inline fun luaVarFunctionNil(crossinline f: (Varargs) -> Unit): LuaFunction =
    KotlinVarArgBridge { args ->
        f(args)
        LuaValue.NIL
    }

@JvmName("asVarArgFunctionVoid")
fun ((Varargs) -> Unit).asVarArgFunction() = luaVarFunctionNil(this)

inline fun Boolean.toLua(): LuaBoolean = LuaValue.valueOf(this)
inline fun Int.toLua(): LuaNumber = LuaValue.valueOf(this)
inline fun Float.toLua(): LuaNumber = LuaValue.valueOf(this.toDouble())
inline fun Double.toLua(): LuaNumber = LuaValue.valueOf(this)

inline fun String.toLua(): LuaString = LuaValue.valueOf(this)

fun LuaValue?.takeIfValid(): LuaValue? = this?.takeUnless { it.isnil() }

fun LuaValue?.asLuaString(): LuaString? = this?.takeIf { it.isstring() } as LuaString?
fun LuaValue?.asJString(): String? = this?.takeIf { it.isstring() }?.checkjstring()

fun LuaValue?.asTable(): LuaTable? = this?.takeIf { it.istable() } as LuaTable?
fun LuaValue?.asFunction(): LuaFunction? = this?.takeIf { it.isfunction() } as LuaFunction?

inline fun <reified T> LuaValue?.asObject(): T? = this?.takeIf { it.isuserdata(T::class.java) }?.touserdata() as T?

inline fun <reified T> LuaValue.unwrap(): T =
    checktable().rawget("__pxrp_object").checkuserdata() as T

inline fun <reified T> LuaValue.unwrapOrNull(): T? {
    if (!istable()) return null
    val obj = checktable().rawget("__pxrp_object")
    return if (obj.isuserdata()) obj.checkuserdata() as? T else null
}

fun LuaValue?.orNil(): LuaValue = this ?: LuaValue.NIL


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
        this@toLuaArray?.forEachIndexed { i, value -> set(i + 1, value) }
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