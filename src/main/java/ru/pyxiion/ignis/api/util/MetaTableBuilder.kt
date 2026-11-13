package ru.pyxiion.ignis.api.util

import ru.pyxiion.luanova.vm2.LuaError
import ru.pyxiion.luanova.vm2.LuaTable
import ru.pyxiion.luanova.vm2.LuaValue
import ru.pyxiion.luanova.vm2.LuaValue.varargsOf
import ru.pyxiion.luanova.vm2.Varargs
import ru.pyxiion.luanova.vm2.lib.VarArgFunction
import ru.pyxiion.ignis.asFunction
import ru.pyxiion.ignis.luaFunction
import ru.pyxiion.ignis.luaVarFunction

inline fun <reified T : Any> metaTable(block: MetaTableBuilder<T>.() -> Unit): BuiltMeta =
    MetaTableBuilder<T>(T::class.java).apply(block).build()

/**
 * @param meta  finished metatable (__index / __newindex / __pairs / methods)
 * @param keys  keys `__pairs` iterates: own props and methods plus everything inherited
 */
class BuiltMeta(val meta: LuaTable, val keys: List<LuaValue>) {
    /** Copies everything from [meta] onto [target] */
    fun apply(target: LuaTable) {
        meta.keys().forEach { target.rawset(it, meta.get(it)) }
    }
}

/**
 * DSL summary:
 *   prop("x", getter)              – read-only
 *   prop("x", get, set)            – read-write (setter is T.(LuaValue))
 *   lazy("x", factory)             – read-only, computed once per wrapper table and cached on it via rawset
 *   map(mapping, get, set?)        – batch from Map<String, K>
 *   method("x") { args -> ... }    – args.arg(1) = self table
 *   toString { "..." }             – tostring
 *   inherit(parent)                – fall through to another BuiltMeta (props, methods, setters, __pairs keys)
 *
 * Assigning to a read-only field, to a method or to an unknown name raises a [LuaError]; per-object state
 * therefore does not live in script-assigned fields (wrappers keep their own state with rawset).
 */
class MetaTableBuilder<T : Any>(val type: Class<T>) {
    private val entries = linkedMapOf<LuaValue, Entry<T>>()
    private val methods = mutableListOf<Pair<LuaValue, (Varargs) -> Varargs>>()
    private var parent: BuiltMeta? = null

    fun prop(name: String, getter: T.() -> LuaValue) {
        entries[LuaValue.valueOf(name)] = SimpleEntry({ t, _ -> getter(t) }, null)
    }

    fun propWithTable(name: String, getter: T.(table: LuaTable) -> LuaValue) {
        entries[LuaValue.valueOf(name)] = SimpleEntry(getter, null)
    }

    fun prop(
        name: String,
        get: T.() -> LuaValue,
        set: T.(LuaValue) -> Unit,
    ) {
        entries[LuaValue.valueOf(name)] = SimpleEntry({ t, _ -> get(t) }, { t, v, _ -> set(t, v) })
    }

    /** First read creates and caches the value on the wrapper table. */
    fun lazy(name: String, factory: T.() -> LuaValue) {
        val luaName = LuaValue.valueOf(name)
        entries[luaName] = LazyEntry(luaName, factory)
    }

    /** Each map entry becomes a separate __index key sharing one getter/setter. */
    fun <K : Any> map(
        mapping: Map<String, K>,
        getter: (T, K) -> LuaValue,
    ) {
        for ((key, k) in mapping) entries[LuaValue.valueOf(key)] = MappedEntry(k, getter, null)
    }

    fun <K : Any> map(
        mapping: Map<String, K>,
        getter: (T, K) -> LuaValue,
        setter: (T, K, LuaValue) -> Unit,
    ) {
        for ((key, k) in mapping) entries[LuaValue.valueOf(key)] = MappedEntry(k, getter, setter)
    }

    /**
     * Registers a method. Varargs layout:
     *   arg(1) = self table   arg(2..n) = Lua arguments
     */
    fun method(name: String, body: (Varargs) -> Varargs) {
        method(LuaValue.valueOf(name), body)
    }

    fun method(name: LuaValue, body: (Varargs) -> Varargs) {
        methods.add(name to body)
    }

    inline fun toString(crossinline factory: T.() -> String) {
        method(LuaValue.TOSTRING) { args: Varargs ->
            val self = args.arg(1).checktable()
            val obj = self.rawget("__pxrp_object").checkuserdata(type) as T

            LuaValue.valueOf(factory(obj))
        }
    }

    fun inherit(parent: BuiltMeta) {
        this.parent = parent
    }

    fun build(): BuiltMeta {
        val parent = this.parent
        val meta = LuaTable()
        val methodNames = methods.map { it.first }.toSet()
        val keysList = linkedSetOf<LuaValue>().apply {
            if (parent != null) addAll(parent.keys)
            addAll(entries.keys)
            addAll(methodNames)
            removeIf { !it.isstring() || it.tojstring().startsWith("__") }
        }.toList()

        meta.set("__index", luaFunction { self, key ->
            val s = self.checktable()
            val obj = self.rawget("__pxrp_object").checkuserdata(type)

            val entry = entries[key]
            if (entry != null) return@luaFunction entry.get(obj as T, s)

            val mt = self.getmetatable()
            if (!mt.isnil()) {
                val m = mt.get(key)
                if (!m.isnil()) return@luaFunction m
            }

            if (parent != null) {
                val direct = parent.meta.get(key)
                if (!direct.isnil()) return@luaFunction direct

                parent.meta.get("__index").asFunction()?.let {
                    return@luaFunction it.invoke(varargsOf(s, key)) as LuaValue
                }
            }
            return@luaFunction LuaValue.NIL
        })

        meta.set("__newindex", luaVarFunction { args ->
            val self = args.arg(1).checktable()
            val key = args.arg(2)
            val value = args.arg(3)
            val obj = self.rawget("__pxrp_object").checkuserdata(type)
            val owner = self.rawget("__pxrp_type").optjstring(type.simpleName)

            val entry = entries[key]
            if (entry != null) {
                if (!entry.hasSetter) throw LuaError("$owner.${key.tojstring()}: поле только для чтения")
                entry.set(obj as T, value, self)
                return@luaVarFunction LuaValue.NIL
            }
            if (key in methodNames) throw LuaError("$owner.${key.tojstring()} — метод, его нельзя перезаписать")

            val parentNewIndex = parent?.meta?.get("__newindex")
            if (parentNewIndex != null && parentNewIndex.isfunction()) {
                parentNewIndex.invoke(varargsOf(arrayOf(self, key, value)))
                return@luaVarFunction LuaValue.NIL
            }
            throw LuaError("$owner: неизвестное поле '${key.tojstring()}'")
        })

        meta.set("__pairs", luaVarFunction { args ->
            val self = args.arg(1)
            val iterator = object : VarArgFunction() {
                private var index = 0
                override fun invoke(args: Varargs): Varargs {
                    if (index >= keysList.size) return NIL
                    val key = keysList[index]
                    index++
                    return varargsOf(key, self.get(key))
                }
            }
            varargsOf(iterator, self, LuaValue.NIL)
        })

        for ((name, body) in methods) {
            meta.rawset(name, luaVarFunction(body))
        }

        return BuiltMeta(meta, keysList)
    }

    // -- entry implementations --

    private sealed interface Entry<T> {
        val hasSetter: Boolean
        fun get(obj: T, self: LuaTable): LuaValue
        fun set(obj: T, value: LuaValue, self: LuaTable)
    }

    private class SimpleEntry<T>(
        private val getter: (T, LuaTable) -> LuaValue,
        private val setter: ((T, LuaValue, LuaTable) -> Unit)?,
    ) : Entry<T> {
        override val hasSetter get() = setter != null
        override fun get(obj: T, self: LuaTable): LuaValue = getter(obj, self)
        override fun set(obj: T, value: LuaValue, self: LuaTable) {
            setter?.invoke(obj, value, self)
        }
    }

    /** Caches the result on first read, returns cached copy on subsequent reads. */
    private class LazyEntry<T>(
        private val name: LuaValue,
        private val factory: (T) -> LuaValue,
    ) : Entry<T> {
        override val hasSetter get() = false
        override fun get(obj: T, self: LuaTable): LuaValue {
            val cached = self.rawget(name)
            if (!cached.isnil()) return cached
            val v = factory(obj)
            self.rawset(name, v)
            return v
        }

        override fun set(obj: T, value: LuaValue, self: LuaTable) = Unit
    }

    /** Uses a shared getter/setter pair with a typed discriminator K (attribute, slot…). */
    private class MappedEntry<T, K>(
        private val k: K,
        private val getter: (T, K) -> LuaValue,
        private val setter: ((T, K, LuaValue) -> Unit)?,
    ) : Entry<T> {
        override val hasSetter get() = setter != null
        override fun get(obj: T, self: LuaTable): LuaValue = getter(obj, k)
        override fun set(obj: T, value: LuaValue, self: LuaTable) {
            setter?.invoke(obj, k, value)
        }
    }
}
