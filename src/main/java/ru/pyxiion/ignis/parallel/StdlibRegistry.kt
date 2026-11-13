package ru.pyxiion.ignis.parallel

import ru.pyxiion.luanova.vm2.LuaString
import ru.pyxiion.luanova.vm2.LuaTable
import ru.pyxiion.luanova.vm2.LuaValue
import ru.pyxiion.ignis.forEach
import java.util.IdentityHashMap

/**
 * Names the tables and functions of an environment built by `installStdlib`. Every state built the same way has
 * the same objects under the same paths, so a reference to one of them crosses threads as a path and is looked up
 * again on the other side (`math.floor` captured by a parallel function is the worker's own `math.floor`).
 *
 * The paths are the top-level globals and the direct table/function entries of the global tables. Deeper is not
 * recorded.
 */
internal class StdlibRegistry(private val globals: LuaTable) {
    private val paths = IdentityHashMap<LuaValue, Array<LuaValue>>()
    private val topLevel = HashSet<LuaValue>()

    init {
        val tables = ArrayList<Pair<LuaValue, LuaTable>>()
        globals.forEach { key, value ->
            topLevel.add(key)
            if (value === globals) return@forEach
            if (value.istable() || value.isfunction()) {
                paths.putIfAbsent(value, arrayOf(key))
                if (value is LuaTable) tables.add(key to value)
            }
        }
        // Second pass: a top-level path always beats a nested one.
        for ((key, table) in tables) {
            table.forEach { subKey, value ->
                if (value.istable() || value.isfunction()) paths.putIfAbsent(value, arrayOf(key, subKey))
            }
        }
    }

    fun pathOf(v: LuaValue): Array<LuaValue>? = paths[v]

    /** True when [globals] had a non-nil entry under [name] when the registry was built (`_VERSION` counts). */
    fun hasGlobal(name: LuaString): Boolean = name in topLevel

    companion object {
        fun resolve(globals: LuaTable, path: Array<LuaValue>): LuaValue {
            var v: LuaValue = globals
            for (key in path) v = v.checktable().rawget(key)
            return v
        }
    }
}
