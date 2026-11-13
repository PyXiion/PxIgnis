package ru.pyxiion.ignis.parallel

import ru.pyxiion.luanova.vm2.LuaClosure
import ru.pyxiion.luanova.vm2.LuaError
import ru.pyxiion.luanova.vm2.LuaTable
import ru.pyxiion.luanova.vm2.LuaValue
import ru.pyxiion.ignis.forEach
import ru.pyxiion.ignis.luaFunction
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Adds `table.freeze` and `table.isfrozen` to the `table` library in [globals].
 *
 * Invariant: a frozen table has no metatable, and everything reachable from it is a nil, boolean, number, string,
 * or another frozen table, so it can cross threads by reference. Shared objects are excluded: a userdata wrapped
 * for one state would keep that state's metatable (and its blocking or yielding channel methods) in another.
 * Nothing but `table.freeze` (and the `vec` metatable) may call `LuaTable.freeze()`.
 */
internal fun installFreeze(globals: LuaTable) {
    val table = globals.get("table").checktable()
    table.rawset("freeze", luaFunction { t -> freeze(t.checktable()) })
    table.rawset("isfrozen", luaFunction { t -> LuaValue.valueOf(t.checktable().isFrozen()) })
}

private fun freeze(root: LuaTable): LuaValue {
    val collected: MutableSet<LuaTable> = Collections.newSetFromMap(IdentityHashMap())
    val order = ArrayList<LuaTable>()

    fun visit(v: LuaValue, path: String) {
        val kind = when (v.type()) {
            LuaValue.TNIL, LuaValue.TBOOLEAN, LuaValue.TNUMBER, LuaValue.TSTRING -> return
            LuaValue.TTABLE -> {
                val t = v as LuaTable
                if (t.isFrozen() || !collected.add(t)) return
                if (t.javaClass != LuaTable::class.java || t.getmetatable() != null) {
                    "table with a metatable"
                } else {
                    order.add(t)
                    t.forEach { k, value ->
                        val child = childPath(path, k)
                        visit(k, child)
                        visit(value, child)
                    }
                    return
                }
            }

            LuaValue.TUSERDATA -> "userdata"
            LuaValue.TFUNCTION -> if (v is LuaClosure) "function" else "native function"
            else -> v.typename()
        }
        throw LuaError("table.freeze: $path is a $kind and cannot be frozen")
    }

    visit(root, "table")
    for (t in order) t.freeze()
    return root
}
