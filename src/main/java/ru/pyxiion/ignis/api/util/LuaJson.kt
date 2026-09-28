package ru.pyxiion.ignis.api.util

import com.google.gson.*
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import ru.pyxiion.ignis.forEach
import ru.pyxiion.ignis.toLuaArray

/**
 * JSON <-> Lua value conversion.
 *
 * A Lua table becomes a JSON array only if its keys are exactly `1..#t`; otherwise it is an object
 * and non-string keys are dropped. An empty table encodes as `[]`.
 */
object LuaJson {
    private val gson = Gson()

    /** @throws JsonSyntaxException if [json] is malformed. */
    fun decode(json: String): LuaValue = toLua(gson.fromJson(json, JsonElement::class.java))

    fun encode(value: LuaValue): String = gson.toJson(toJson(value))

    fun toLua(element: JsonElement?): LuaValue = when {
        element == null || element.isJsonNull -> LuaValue.NIL
        element.isJsonPrimitive -> {
            val p = element.asJsonPrimitive
            when {
                p.isBoolean -> LuaValue.valueOf(p.asBoolean)
                p.isNumber -> LuaValue.valueOf(p.asDouble)
                p.isString -> LuaValue.valueOf(p.asString)
                else -> LuaValue.NIL
            }
        }
        element.isJsonArray -> element.asJsonArray.map(::toLua).toLuaArray()
        element.isJsonObject -> {
            val t = LuaTable()
            for ((key, v) in element.asJsonObject.entrySet()) t.set(key, toLua(v))
            t
        }
        else -> LuaValue.NIL
    }

    fun toJson(value: LuaValue): JsonElement = when {
        value.isnil() -> JsonNull.INSTANCE
        value.isboolean() -> JsonPrimitive(value.toboolean())
        value.isint() -> JsonPrimitive(value.toint())
        value.islong() -> JsonPrimitive(value.tolong())
        value.isnumber() -> JsonPrimitive(value.todouble())
        value.isstring() -> JsonPrimitive(value.tojstring())
        value.istable() -> tableToJson(value.checktable())
        else -> JsonNull.INSTANCE
    }

    private fun tableToJson(table: LuaTable): JsonElement {
        var isSequence = true
        val keys = mutableSetOf<Int>()
        val len = table.length()

        table.forEach { k, _ ->
            if (k.isint() && k.toint() >= 1) keys.add(k.toint())
            else isSequence = false
        }

        // Sparse tables like {[2] = x} report #t == 0; without the size check they'd encode as [].
        if (isSequence) {
            isSequence = keys.size == len && keys.all { it in 1..len }
        }

        return if (isSequence) {
            val arr = JsonArray()
            for (i in 1..len) arr.add(toJson(table.get(i)))
            arr
        } else {
            val obj = JsonObject()
            table.forEach { k, v ->
                if (k.isstring()) obj.add(k.checkjstring(), toJson(v))
            }
            obj
        }
    }
}
