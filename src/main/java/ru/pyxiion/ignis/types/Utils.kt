package ru.pyxiion.ignis.types

import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue

fun toLuaValue(obj: Any?): LuaValue {
    return when (obj) {
        null -> LuaValue.NIL
        is Boolean -> LuaValue.valueOf(obj)
        is Byte, is Short, is Int, is Long, is Float, is Double -> LuaValue.valueOf((obj as Number).toDouble())
        is String -> LuaValue.valueOf(obj)
        is Array<*> -> {
            val table = LuaTable()
            obj.forEachIndexed { index, value ->
                table.set(index + 1, toLuaValue(value))
            }
            table
        }
        is List<*> -> {
            val table = LuaTable()
            obj.forEachIndexed { index, value ->
                table.set(index + 1, toLuaValue(value))
            }
            table
        }
        is Map<*, *> -> {
            val table = LuaTable()
            obj.forEach { (k, v) ->
                table.set(toLuaValue(k!!), toLuaValue(v))
            }
            table
        }
        is LuaValue -> obj // уже LuaValue, не оборачиваем
        else -> LuaValue.userdataOf(obj)
    }
}