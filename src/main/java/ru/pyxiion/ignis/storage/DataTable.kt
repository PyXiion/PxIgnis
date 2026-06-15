package ru.pyxiion.ignis.storage

import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs

class DataTable(
    private val backend: DataBackend?,
    private val key: String?,
    private val parent: DataTable? = null,
    private val parentKey: LuaValue? = null
) : LuaTable() {
    private var loaded = parent != null
    private val lock = Any()

    private fun root(): DataTable = parent?.root() ?: this

    fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            val data = backend!!.load(key!!)
            for ((k, v) in data) {
                val luaVal = toLuaValue(v)
                val stored = if (luaVal.istable() && luaVal !is DataTable) {
                    wrapChild(LuaValue.valueOf(k), luaVal.checktable())
                } else luaVal
                super.rawset(LuaValue.valueOf(k), stored)
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
        assertValidKey(key)
        validateValue(value)
        val v = wrapIfTable(key, value)
        super.set(key, v)
        markDirty()
    }

    override fun rawset(key: LuaValue, value: LuaValue) {
        ensureLoaded()
        assertValidKey(key)
        validateValue(value)
        val v = wrapIfTable(key, value)
        super.rawset(key, v)
        markDirty()
    }

    private fun assertValidKey(key: LuaValue) {
        if (key.type() != LuaValue.TSTRING && !key.isint()) {
            throw LuaError("Cannot store keys that are neither strings nor integers in persistent data")
        }
    }

    override fun next(key: LuaValue): Varargs {
        ensureLoaded()
        return super.next(key)
    }

    override fun length(): Int {
        ensureLoaded()
        return super.length()
    }

    private fun wrapIfTable(key: LuaValue, value: LuaValue): LuaValue {
        if (!value.istable() || value is DataTable) return value
        return wrapChild(key, value.checktable())
    }

    private fun wrapChild(key: LuaValue, table: LuaTable): DataTable {
        val child = DataTable(null, null, this, key)
        var k = LuaValue.NIL
        while (true) {
            val next = table.next(k)
            if (next.arg(1).isnil()) break
            child.rawset(next.arg(1), next.arg(2))
            k = next.arg(1)
        }
        return child
    }

    private fun markDirty() {
        root().dirty = true
    }

    private var dirty = false

    fun save() {
        if (backend == null) return
        synchronized(lock) {
            ensureLoaded()
            backend.save(key!!, toJavaMap(this))
            dirty = false
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
                    if (key.type() != LuaValue.TSTRING && !key.isint()) {
                        throw LuaError("Cannot store keys that are neither strings nor integers in persistent data")
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

        fun isListTable(table: LuaTable): Boolean {
            val len = table.length()
            if (len == 0) return false
            for (i in 1..len) {
                if (table.get(i).isnil()) return false
            }
            var k = LuaValue.NIL
            while (true) {
                val next = table.next(k)
                if (next.arg1().isnil()) break
                val key = next.arg1()
                if (!key.isint()) return false
                val idx = key.toint()
                if (idx < 1 || idx > len) return false
                k = key
            }
            return true
        }

        fun listTableToJava(table: LuaTable): List<Any?> {
            val len = table.length()
            val result = ArrayList<Any?>(len)
            for (i in 1..len) {
                result.add(luaToJava(table.get(i)))
            }
            return result
        }

        fun luaToJava(value: LuaValue): Any? = when {
            value.isnil() -> null
            value.isboolean() -> value.toboolean()
            value.isint() -> value.toint()
            value.isnumber() -> {
                if (value.islong()) value.checkint() else value.checkdouble()
            }
            value.isstring() -> value.tojstring()
            value.istable() -> {
                val table = value.checktable()
                if (isListTable(table)) listTableToJava(table) else toJavaMap(table)
            }
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
