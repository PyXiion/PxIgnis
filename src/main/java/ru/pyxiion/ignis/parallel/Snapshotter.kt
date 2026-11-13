package ru.pyxiion.ignis.parallel

import ru.pyxiion.luanova.vm2.LuaClosure
import ru.pyxiion.luanova.vm2.LuaError
import ru.pyxiion.luanova.vm2.LuaTable
import ru.pyxiion.luanova.vm2.LuaValue
import ru.pyxiion.ignis.api.Vector
import ru.pyxiion.ignis.asObject
import ru.pyxiion.ignis.forEach
import java.util.IdentityHashMap

internal fun childPath(parent: String, key: LuaValue): String =
    if (key.type() == LuaValue.TSTRING) {
        if (parent.isEmpty()) key.tojstring() else "$parent.${key.tojstring()}"
    } else {
        "$parent[${key.tojstring()}]"
    }

internal fun unfit(op: String, path: String, kind: String): Nothing =
    throw LuaError("$op: $path is a $kind and cannot cross threads")

/**
 * Copies a value graph into [Snap]s. One instance per transfer: the memo keeps shared references, shared upvalue
 * cells and cycles (a recursive local function captures itself) intact. A node is registered before its children
 * are visited.
 *
 * With [allowFunctions] the Lua closures reachable from the value are copied too; [closures] lists the originals.
 * [op] and the paths make the failure messages: "$op: $path is a $kind and cannot cross threads".
 */
internal class Snapshotter(
    private val globals: LuaTable,
    private val stdlib: StdlibRegistry,
    private val allowFunctions: Boolean,
    private val op: String,
) {
    private val memo = IdentityHashMap<Any, Snap>()

    /** The Lua closures copied so far. */
    val closures = ArrayList<LuaClosure>()

    /** [path] is "" for the top-level function, whose upvalues are then reported as `upvalue 'NAME'`. */
    fun snapshot(v: LuaValue, path: String): Snap = when (v.type()) {
        LuaValue.TNIL, LuaValue.TBOOLEAN, LuaValue.TNUMBER, LuaValue.TSTRING -> Prim(v)
        LuaValue.TTABLE -> table(v as LuaTable, path)
        LuaValue.TFUNCTION -> function(v, path)
        LuaValue.TUSERDATA -> userdata(v, path)
        else -> unfit(op, path, v.typename())
    }

    private fun table(t: LuaTable, path: String): Snap {
        if (t === globals) return GlobalsRef
        stdlib.pathOf(t)?.let { return StdPath(it) }
        if (t.isFrozen()) return Ref(t)
        if (t.javaClass != LuaTable::class.java || t.getmetatable() != null) {
            unfit(op, path, "table with a metatable")
        }
        memo[t]?.let { return it }

        val tbl = Tbl(emptyArray(), emptyArray())
        memo[t] = tbl
        val keys = ArrayList<Snap>()
        val values = ArrayList<Snap>()
        t.forEach { k, value ->
            val child = childPath(path, k)
            keys.add(snapshot(k, child))
            values.add(snapshot(value, child))
        }
        tbl.keys = keys.toTypedArray()
        tbl.values = values.toTypedArray()
        return tbl
    }

    private fun function(f: LuaValue, path: String): Snap {
        stdlib.pathOf(f)?.let { return StdPath(it) }
        if (f !is LuaClosure) unfit(op, path, "native function")
        if (!allowFunctions) unfit(op, path, "function")
        memo[f]?.let { return it }

        val fn = Fn(f.p, emptyArray())
        memo[f] = fn
        closures.add(f)
        fn.cells = Array(f.upValues.size) { i -> cell(f, i, path) }
        return fn
    }

    private fun cell(f: LuaClosure, index: Int, path: String): Cell {
        val upValue = f.upValues[index] ?: return Cell(Prim(LuaValue.NIL))
        (memo[upValue] as Cell?)?.let { return it }

        val cell = Cell(Prim(LuaValue.NIL))
        memo[upValue] = cell
        val name = f.p.upvalues.getOrNull(index)?.name?.tojstring() ?: "?"
        val child = if (path.isEmpty()) "upvalue '$name'" else "$path \u2192 upvalue '$name'"
        cell.value = snapshot(upValue.value, child)
        return cell
    }

    private fun userdata(v: LuaValue, path: String): Snap {
        if (v is Vector) return Vec(v.x, v.y, v.z)
        v.asObject<SharedObject>()?.let { return Shared(it) }
        unfit(op, path, "userdata")
    }
}
