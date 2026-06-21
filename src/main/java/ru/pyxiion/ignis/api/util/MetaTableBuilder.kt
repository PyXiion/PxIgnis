package ru.pyxiion.ignis.api.util

import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.VarArgFunction

inline fun <reified T : Any> metaTable(block: MetaTableBuilder<T>.() -> Unit): BuiltMeta =
    MetaTableBuilder<T>(T::class.java).apply(block).build()

/**
 * @param meta  finished metatable (__index / __newindex / __pairs / methods)
 * @param keys  key list for __pairs — children merge these via pairsKeys(...)
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
 *   lazy("x", factory)             – computed once, cached via rawset
 *   lazy("x", factory, set)        – cached + setter
 *   map(mapping, get, set?)        – batch from Map<String, K>
 *   method("x") { args -> ... }    – args.arg(1) = self table
 *   toString { "..." }             - tostring
 *   inherit { MetaTableRegistry.X }
 *   inheritIndex / inheritNewIndex – asymmetric fallthrough
 */
class MetaTableBuilder<T : Any>(val type: Class<T>) {
    private val entries = linkedMapOf<LuaValue, Entry<T>>()
    private val methods = mutableListOf<Pair<LuaValue, (Varargs) -> LuaValue>>()
    private val pairsOnlyKeys = mutableListOf<LuaValue>()
    private var parentIndex: (() -> LuaTable)? = null
    private var parentNewIndex: (() -> LuaTable)? = null

    fun prop(name: String, getter: T.() -> LuaValue) {
        entries[LuaValue.valueOf(name)] = SimpleEntry({ t, _ -> getter(t) }, null)
    }

    fun prop(name: String, getter: (T, LuaTable) -> LuaValue) {
        entries[LuaValue.valueOf(name)] = SimpleEntry(getter, null)
    }

    fun prop(
        name: String,
        get: T.() -> LuaValue,
        set: T.(LuaValue) -> Unit,
    ) {
        entries[LuaValue.valueOf(name)] = SimpleEntry({ t, _ -> get(t) }, { t, v, _ -> set(t, v) })
    }

    /** First read creates and caches the value */
    fun lazy(name: String, factory: T.() -> LuaValue) {
        val luaName = LuaValue.valueOf(name)
        entries[luaName] = LazyEntry(luaName, factory, null)
    }

    /** Same as lazy(), with optional setter. [invalidate] controls whether setting clears the cache. */
    fun lazy(
        name: String,
        factory: T.() -> LuaValue,
        set: T.(LuaValue) -> Unit,
        invalidate: Boolean = true,
    ) {
        val luaName = LuaValue.valueOf(name)
        val setter: (T, LuaValue, LuaTable) -> Unit = { t, v, _ -> set(t, v) }

        if (invalidate) {
            entries[luaName] = LazyEntryWithInvalidate(factory, setter)
        } else {
            entries[luaName] = LazyEntry(luaName, factory, setter)
        }
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
    fun method(name: String, body: (Varargs) -> LuaValue) {
        method(LuaValue.valueOf(name), body)
    }

    /**
     * Registers a method. Varargs layout:
     *   arg(1) = self table   arg(2..n) = Lua arguments
     */
    fun method(name: LuaValue, body: (Varargs) -> LuaValue) {
        methods.add(name to body)
    }

    /**
     *
     */
    inline fun toString(crossinline factory: T.() -> String) {
        method(LuaValue.TOSTRING) { args: Varargs ->
            val self = args.arg(1).checktable()
            val obj = self.rawget("__pxrp_object").checkuserdata(type) as T

            LuaValue.valueOf(factory(obj))
        }
    }

    /** Unknown __index / __newindex keys fall through to [table]. */
    fun inherit(table: () -> LuaTable) {
        parentIndex = table
        parentNewIndex = table
    }

    fun inheritIndex(table: () -> LuaTable) {
        parentIndex = table
    }

    fun inheritNewIndex(table: () -> LuaTable) {
        parentNewIndex = table
    }

    /** Inherit __index/__newindex AND pairs keys from a parent BuiltMeta. */
    fun inherit(parent: BuiltMeta) {
        parentIndex = { parent.meta }
        parentNewIndex = { parent.meta }
        pairsOnlyKeys.addAll(parent.keys)
    }

    fun build(): BuiltMeta {
        val meta = LuaTable()
        val keysList = linkedSetOf<LuaValue>().apply {
            addAll(entries.keys)
            addAll(methods.map { it.first })
            addAll(pairsOnlyKeys)
            removeIf { !it.isstring() || it.tojstring().startsWith("__") }
        }.toList()

        meta.set("__index", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val self = args.arg(1).checktable()
                val key = args.arg(2)
                val obj = self.rawget("__pxrp_object").checkuserdata(type)

                val entry = entries[key]
                if (entry != null) return entry.get(obj as T, self)

                val mt = self.getmetatable()
                if (!mt.isnil()) {
                    val m = mt.get(key)
                    if (!m.isnil()) return m
                }

                val parent = parentIndex?.invoke()
                if (parent != null) {
                    val pi = parent.get("__index")
                    if (pi.isfunction()) return pi.invoke(varargsOf(self, key))
                }

                return NIL
            }
        })

        val hasSetters = entries.values.any { it.hasSetter }
        if (hasSetters || parentNewIndex != null) {
            meta.set("__newindex", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val key = args.arg(2)
                    val value = args.arg(3)
                    val obj = self.rawget("__pxrp_object").checkuserdata(type)

                    val entry = entries[key]
                    if (entry != null && entry.hasSetter) {
                        entry.set(obj as T, value, self)
                        return NIL
                    }

                    val mt = self.getmetatable()
                    if (!mt.isnil()) {
                        val m = mt.get(key)
                        if (m.isfunction()) {
                            m.invoke(varargsOf(arrayOf(self, key, value)))
                            return NIL
                        }
                    }

                    val parent = parentNewIndex?.invoke()
                    if (parent != null) {
                        val pn = parent.get("__newindex")
                        if (pn.isfunction()) return pn.invoke(varargsOf(arrayOf(self, key, value)))
                    }

                    return NIL
                }
            })
        }

        meta.set("__pairs", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
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
                return varargsOf(iterator, self, NIL)
            }
        })

        for ((name, body) in methods) {
            meta.rawset(name, object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs = body(args)
            })
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
        private val setter: ((T, LuaValue, LuaTable) -> Unit)?,
    ) : Entry<T> {
        override val hasSetter get() = setter != null
        override fun get(obj: T, self: LuaTable): LuaValue {
            val cached = self.rawget(name)
            if (!cached.isnil()) return cached
            val v = factory(obj)
            self.rawset(name, v)
            return v
        }

        override fun set(obj: T, value: LuaValue, self: LuaTable) {
            setter?.invoke(obj, value, self)
        }
    }

    private class LazyEntryWithInvalidate<T>(
        private val factory: (T) -> LuaValue,
        private val setter: ((T, LuaValue, LuaTable) -> Unit)?,
    ) : Entry<T> {
        private var cached: LuaValue = LuaValue.NIL

        override val hasSetter get() = setter != null
        override fun get(obj: T, self: LuaTable): LuaValue {
            if (cached.isnil()) cached = factory(obj)
            return cached
        }

        override fun set(obj: T, value: LuaValue, self: LuaTable) {
            cached = LuaValue.NIL
            setter?.invoke(obj, value, self)
        }
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
