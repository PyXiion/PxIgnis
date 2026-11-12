package ru.pyxiion.ignis.storage

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import java.util.IdentityHashMap

/**
 * The one Lua <-> JSON codec (persistent data, `async.fetch` bodies and responses).
 *
 * Writing:
 * - the type comes from `type()`, never from `isint()`/`isnumber()`: a string is always a string;
 * - a number is an int when it is a 32-bit integer, a long when it is another integral double, else a double;
 * - a table with keys exactly `1..n` (n >= 1) is a list, any other table (including `{}`) is an object;
 * - an integer object key is written as a string;
 * - functions, userdata, coroutines, cycles, NaN/Infinity and other key types raise [LuaError] with
 *   the path to the value, e.g. `data.shop.items[3]: function`.
 *
 * Reading: an object key matching `-?(0|[1-9][0-9]*)` within the int range becomes an integer key.
 * A string key of that shape therefore does not round-trip.
 */
object LuaJson {
    private val INT_KEY = Regex("-?(0|[1-9][0-9]*)")
    private val INT_LITERAL = Regex("-?[0-9]+")
    private val IDENT = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /** [special] may turn a table (e.g. a wrapped object) into JSON of its own; null falls through to the normal rules. */
    fun toJson(value: LuaValue, root: String = "data", special: ((LuaTable) -> JsonElement?)? = null): JsonElement =
        encode(value, root, IdentityHashMap(), special)

    /** Top-level files are always objects, even for a table with keys `1..n`. */
    fun toJsonObject(table: LuaTable, root: String = "data"): JsonObject =
        encodeTable(table, root, IdentityHashMap(), null, forceObject = true) as JsonObject

    fun fromJson(element: JsonElement): LuaValue = when {
        element.isJsonNull -> LuaValue.NIL
        element.isJsonPrimitive -> decodePrimitive(element.asJsonPrimitive)
        element.isJsonArray -> {
            val t = LuaTable()
            element.asJsonArray.forEachIndexed { i, e -> t.rawset(i + 1, fromJson(e)) }
            t
        }
        else -> {
            val t = LuaTable()
            for ((k, v) in element.asJsonObject.entrySet()) {
                val key = if (isIntKey(k)) LuaValue.valueOf(k.toInt()) else LuaValue.valueOf(k)
                t.rawset(key, fromJson(v))
            }
            t
        }
    }

    private fun isIntKey(key: String) = INT_KEY.matches(key) && key.toIntOrNull() != null

    private fun decodePrimitive(p: JsonPrimitive): LuaValue = when {
        p.isBoolean -> LuaValue.valueOf(p.asBoolean)
        p.isString -> LuaValue.valueOf(p.asString)
        else -> {
            val text = p.asString
            val asInt = if (INT_LITERAL.matches(text)) text.toIntOrNull() else null
            if (asInt != null) LuaValue.valueOf(asInt) else LuaValue.valueOf(p.asDouble)
        }
    }

    private fun encode(
        value: LuaValue,
        path: String,
        stack: IdentityHashMap<LuaTable, Unit>,
        special: ((LuaTable) -> JsonElement?)?,
    ): JsonElement =
        when (value.type()) {
            LuaValue.TNIL -> JsonNull.INSTANCE
            LuaValue.TBOOLEAN -> JsonPrimitive(value.toboolean())
            LuaValue.TSTRING -> JsonPrimitive(value.tojstring())
            LuaValue.TNUMBER -> encodeNumber(value, path)
            LuaValue.TTABLE -> special?.invoke(value.checktable()) ?: encodeTable(value.checktable(), path, stack, special)
            else -> throw LuaError("$path: ${value.typename()}")
        }

    private fun encodeNumber(value: LuaValue, path: String): JsonElement {
        if (value.isint()) return JsonPrimitive(value.toint())
        val d = value.todouble()
        if (d.isNaN() || d.isInfinite()) throw LuaError("$path: number $d is not representable in JSON")
        if (d == Math.floor(d) && d >= -9.223372036854775E18 && d < 9.223372036854775E18) {
            return JsonPrimitive(d.toLong())
        }
        return JsonPrimitive(d)
    }

    private fun encodeTable(
        table: LuaTable,
        path: String,
        stack: IdentityHashMap<LuaTable, Unit>,
        special: ((LuaTable) -> JsonElement?)?,
        forceObject: Boolean = false,
    ): JsonElement {
        if (stack.put(table, Unit) != null) throw LuaError("$path: cyclic reference")
        try {
            val len = if (forceObject) 0 else listLength(table)
            if (len > 0) {
                val arr = JsonArray()
                for (i in 1..len) arr.add(encode(table.rawget(i), "$path[$i]", stack, special))
                return arr
            }
            val obj = JsonObject()
            var k: LuaValue = LuaValue.NIL
            while (true) {
                val n = table.next(k)
                k = n.arg1()
                if (k.isnil()) break
                val name = keyName(k, path)
                obj.add(name, encode(n.arg(2), childPath(path, k), stack, special))
            }
            return obj
        } finally {
            stack.remove(table)
        }
    }

    /** `n` when the keys are exactly `1..n`, otherwise 0. */
    private fun listLength(table: LuaTable): Int {
        val len = table.length()
        if (len == 0) return 0
        var count = 0
        var k: LuaValue = LuaValue.NIL
        while (true) {
            val n = table.next(k)
            k = n.arg1()
            if (k.isnil()) break
            if (k.type() != LuaValue.TNUMBER || !k.isint()) return 0
            val idx = k.toint()
            if (idx < 1 || idx > len) return 0
            count++
        }
        return if (count == len) len else 0
    }

    private fun keyName(key: LuaValue, path: String): String = when {
        key.type() == LuaValue.TSTRING -> key.tojstring()
        key.type() == LuaValue.TNUMBER && key.isint() -> key.toint().toString()
        else -> throw LuaError("$path: key of type ${key.typename()} (only strings and integers can be stored)")
    }

    private fun childPath(path: String, key: LuaValue): String = when {
        key.type() == LuaValue.TNUMBER -> "$path[${key.toint()}]"
        IDENT.matches(key.tojstring()) -> "$path.${key.tojstring()}"
        else -> "$path[\"${key.tojstring()}\"]"
    }
}
