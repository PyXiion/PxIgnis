package ru.pyxiion.ignis.parallel

import org.luaj.vm2.Lua
import org.luaj.vm2.LuaClosure
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaString
import org.luaj.vm2.LuaTable
import org.luaj.vm2.Prototype
import java.util.Collections
import java.util.IdentityHashMap

private val ENV = LuaString.valueOf("_ENV")

/**
 * Rejects, at definition time, the global accesses a worker could not satisfy: a read of a name that is not a
 * stdlib global and any write to a global. Only closures whose `_ENV` is the main [globals] are scanned (a closure
 * with a custom environment keeps its own rules); the prototypes nested inside them are scanned too.
 *
 * Reads that happen through `_G[name]` are not visible here; the worker's globals guard reports those at run time.
 */
internal fun checkGlobals(closures: Collection<LuaClosure>, globals: LuaTable, stdlib: StdlibRegistry) {
    val seen: MutableSet<Prototype> = Collections.newSetFromMap(IdentityHashMap())
    for (closure in closures) {
        val env = closure.p.upvalues.indexOfFirst { it.name == ENV }
        if (env < 0 || closure.upValues.getOrNull(env)?.value !== globals) continue
        scan(closure.p, stdlib, seen)
    }
}

private fun scan(proto: Prototype, stdlib: StdlibRegistry, seen: MutableSet<Prototype>) {
    if (!seen.add(proto)) return

    for (pc in proto.code.indices) {
        val i = proto.code[pc]
        val a = (i shr 6) and 0xff
        val b = i ushr 23
        val c = (i shr 14) and 0x1ff
        when (i and 0x3f) {
            Lua.OP_GETTABUP -> {
                val name = constantKey(proto, b, c)
                if (name != null && !stdlib.hasGlobal(name)) {
                    throw LuaError(
                        "async.parallel: global '$name' is not available in a parallel function (${where(proto, pc)})"
                    )
                }
            }

            Lua.OP_SETTABUP -> {
                val name = constantKey(proto, a, b)
                if (name != null) {
                    throw LuaError(
                        "async.parallel: assignment to global '$name' is not allowed in a parallel function (${where(proto, pc)})"
                    )
                }
            }
        }
    }
    for (child in proto.p) scan(child, stdlib, seen)
}

/** The string constant that [rk] selects when [upvalue] is `_ENV`, otherwise null. */
private fun constantKey(proto: Prototype, upvalue: Int, rk: Int): LuaString? {
    if (proto.upvalues[upvalue].name != ENV || rk <= 0xff) return null
    return proto.k[rk and 0xff] as? LuaString
}

private fun where(proto: Prototype, pc: Int): String =
    "${proto.shortsource()}:${proto.lineinfo?.getOrNull(pc) ?: 0}"
