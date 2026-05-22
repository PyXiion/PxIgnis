package ru.pyxiion.pxrp.storage

import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs

class DataTable(
    private val backend: DataBackend,
    private val key: String
) : LuaTable() {
    private var loaded = false
    private val lock = Any()

    fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            val data = backend.load(key)
            for ((k, v) in data) {
                super.rawset(LuaValue.valueOf(k), toLuaValue(v))
            }
            loaded = true
        }
    }

    override fun get(key: LuaValue): LuaValue {
        ensureLoaded()
        return super.get(key)
    }

    override fun rawget(key: LuaValue): LuaValue {
        ensureLoaded()
        return super.rawget(key)
    }

    override fun set(key: LuaValue, value: LuaValue) {
        ensureLoaded()
        if (!key.isstring()) throw LuaError("Cannot store non-string keys in persistent data")
        validateValue(value)
        super.set(key, value)
    }

    override fun rawset(key: LuaValue, value: LuaValue) {
        ensureLoaded()
        if (!key.isstring()) throw LuaError("Cannot store non-string keys in persistent data")
        validateValue(value)
        super.rawset(key, value)
    }

    override fun next(key: LuaValue): Varargs {
        ensureLoaded()
        return super.next(key)
    }

    override fun length(): Int {
        ensureLoaded()
        return super.length()
    }

    fun save() {
        synchronized(lock) {
            backend.save(key, toJavaMap(this))
        }
    }

    private fun validateValue(value: LuaValue, visited: MutableSet<LuaTable> = HashSet()) {
        when {
            value.isnil() || value.isboolean() || value.isnumber() || value.isstring() -> {}
            value.istable() -> {
                val table = value.checktable()
                if (!visited.add(table)) {
                    throw LuaError("Cyclic reference in persistent data")
                }
                var k = LuaValue.NIL
                while (true) {
                    val next = table.next(k)
                    if (next.arg1().isnil()) break
                    val key = next.arg1()
                    if (!key.isstring()) {
                        throw LuaError("Cannot store non-string keys in persistent data")
                    }
                    validateValue(next.arg(2), visited)
                    k = key
                }
            }
            value.isfunction() -> throw LuaError("Cannot store function values in persistent data")
            value.isuserdata() -> throw LuaError("Cannot store userdata values in persistent data")
            value.isthread() -> throw LuaError("Cannot store thread values in persistent data")
            else -> throw LuaError("Cannot store ${value.typename()} values in persistent data")
        }
    }

    companion object {
        fun toJavaMap(table: LuaTable): Map<String, Any?> {
            val result = LinkedHashMap<String, Any?>()
            var k = LuaValue.NIL
            while (true) {
                val next = table.next(k)
                if (next.arg1().isnil()) break
                val key = next.arg1()
                if (key.isstring()) {
                    result[key.tojstring()] = luaToJava(next.arg(2))
                }
                k = key
            }
            return result
        }

        fun luaToJava(value: LuaValue): Any? = when {
            value.isnil() -> null
            value.isboolean() -> value.toboolean()
            value.isint() -> value.toint()
            value.isnumber() -> value.todouble()
            value.isstring() -> value.tojstring()
            value.istable() -> toJavaMap(value.checktable())
            else -> throw LuaError("Cannot serialize ${value.typename()} to JSON")
        }

        fun toLuaValue(value: Any?): LuaValue = when (value) {
            null -> LuaValue.NIL
            is Boolean -> LuaValue.valueOf(value)
            is Int -> LuaValue.valueOf(value)
            is Long -> LuaValue.valueOf(value.toDouble())
            is Double -> LuaValue.valueOf(value)
            is Float -> LuaValue.valueOf(value.toDouble())
            is String -> LuaValue.valueOf(value)
            is Map<*, *> -> {
                val table = LuaTable()
                for ((k, v) in value) {
                    table.set(k.toString(), toLuaValue(v))
                }
                table
            }
            is List<*> -> {
                val table = LuaTable()
                for ((i, v) in value.withIndex()) {
                    table.set(i + 1, toLuaValue(v))
                }
                table
            }
            else -> throw LuaError("Cannot deserialize ${value::class.java.simpleName} from JSON")
        }
    }
}
