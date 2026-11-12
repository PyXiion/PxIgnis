package ru.pyxiion.ignis.parallel

import org.luaj.vm2.LuaClosure
import org.luaj.vm2.LuaState
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.UpValue
import ru.pyxiion.ignis.api.Vector
import java.util.IdentityHashMap

/**
 * Builds values of [state] from [Snap]s. Must run on the thread that owns [state]. One instance per transfer;
 * closures are built fresh every time because they own their upvalues.
 */
internal class Materializer(private val state: LuaState, private val shared: SharedLib) {
    private val memo = IdentityHashMap<Snap, Any>()

    fun materialize(s: Snap): LuaValue = when (s) {
        is Prim -> s.v
        GlobalsRef -> state.globals
        is StdPath -> StdlibRegistry.resolve(state.globals, s.path)
        is Ref -> s.table
        is Vec -> Vector.of(s.x, s.y, s.z)
        is Shared -> shared.wrap(s.obj)
        is Tbl -> (memo[s] as LuaTable?) ?: table(s)
        is Fn -> (memo[s] as LuaClosure?) ?: function(s)
        is Cell -> throw IllegalStateException("a cell is not a value")
    }

    private fun table(s: Tbl): LuaTable {
        val t = LuaTable()
        memo[s] = t
        for (i in s.keys.indices) t.rawset(materialize(s.keys[i]), materialize(s.values[i]))
        return t
    }

    private fun function(s: Fn): LuaClosure {
        val closure = LuaClosure(s.proto, LuaValue.NIL, state)
        memo[s] = closure
        closure.upValues = Array(s.cells.size) { upValue(s.cells[it]) }
        return closure
    }

    private fun upValue(cell: Cell): UpValue {
        (memo[cell] as UpValue?)?.let { return it }
        val uv = UpValue(arrayOf(LuaValue.NIL), 0)
        memo[cell] = uv
        uv.value = materialize(cell.value)
        return uv
    }
}
