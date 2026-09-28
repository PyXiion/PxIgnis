package ru.pyxiion.ignis.api

import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaState
import org.luaj.vm2.LuaTransfer
import org.luaj.vm2.LuaUserdata
import org.luaj.vm2.LuaValue

/**
 * Which userdata may cross between Lua states (the main state and threadpool worker states).
 * Vectors are copied by value; server objects (players, worlds, ...) and async objects stay where they are.
 */
object StateTransferPolicy : LuaTransfer.UserdataPolicy {
    private class VectorValue(val x: Double, val y: Double, val z: Double)

    override fun export(u: LuaUserdata): Any = when {
        u is Vector -> VectorValue(u.x, u.y, u.z)
        AsyncLib.isAsyncObject(u.m_instance) ->
            throw LuaError("tasks, promises and mutexes belong to the Lua state that created them; pass values instead")
        else -> throw LuaError(
            "server objects (players, worlds, entities, ...) cannot leave the main thread; send plain values instead"
        )
    }

    override fun materialize(exported: Any, target: LuaState): LuaValue = when (exported) {
        is VectorValue -> Vector.of(exported.x, exported.y, exported.z)
        else -> throw IllegalStateException("unexpected exported userdata $exported")
    }
}
