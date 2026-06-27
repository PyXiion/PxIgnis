package ru.pyxiion.ignis.api.util

import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.LuaValue.varargsOf
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.VarArgFunction
import ru.pyxiion.ignis.asFunction
import ru.pyxiion.ignis.luaFunction
import ru.pyxiion.ignis.luaVarFunction

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
    private var parentIndex: (() -> LuaTable)? = null
    private var parentNewIndex: (() -> LuaTable)? = null

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

    fun inherit(parent: () -> LuaTable) {
        parentIndex = parent
        parentNewIndex = parent
    }

    fun build(): BuiltMeta {
        val meta = LuaTable()
        val keysList = linkedSetOf<LuaValue>().apply {
            addAll(entries.keys)
            addAll(methods.map { it.first })
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

            val parent = parentIndex?.invoke()
            if (parent != null) {
                val direct = parent.get(key)
                if (!direct.isnil()) return@luaFunction direct

                parent.get("__index").asFunction()?.let {
                    return@luaFunction it.invoke(varargsOf(s, key)) as LuaValue
                }
            }
            return@luaFunction LuaValue.NIL
        }
        )

        val hasSetters = entries.values.any { it.hasSetter }
        if (hasSetters || parentNewIndex != null) {
            meta.set("__newindex", luaVarFunction { args ->
                val self = args.arg(1).checktable()
                val key = args.arg(2)
                val value = args.arg(3)
                val obj = self.rawget("__pxrp_object").checkuserdata(type)

                val entry = entries[key]
                if (entry != null && entry.hasSetter) {
                    entry.set(obj as T, value, self)
                    return@luaVarFunction LuaValue.NIL
                }

                val mt = self.getmetatable()
                if (!mt.isnil()) {
                    val m = mt.get(key)
                    if (m.isfunction()) {
                        m.invoke(varargsOf(arrayOf(self, key, value)))
                        return@luaVarFunction LuaValue.NIL
                    }
                }

                val parent = parentNewIndex?.invoke()
                if (parent != null) {
                    val pn = parent.get("__newindex")
                    if (pn.isfunction()) return@luaVarFunction pn.invoke(varargsOf(arrayOf(self, key, value)))
                }

                return@luaVarFunction LuaValue.NIL
            }
            )
        }

        meta.set("__pairs", luaVarFunction { args ->
            val self = args.arg(1)
            val parentMeta = parentIndex?.invoke()

            val merged = linkedSetOf<LuaValue>().apply {
                addAll(keysList)
                if (parentMeta != null) {
                    val pp = parentMeta.get("__pairs")
                    if (pp.isfunction()) {
                        val triple = pp.invoke(self)
                        val iterFn = triple.arg(1)
                        val state = triple.arg(2)
                        var prevKey = triple.arg(3)
                        while (true) {
                            val nxt = iterFn.invoke(varargsOf(state, prevKey))
                            val k = nxt.arg(1)
                            if (k.isnil()) break
                            if (k.isstring() && !k.tojstring().startsWith("__")) {
                                add(k)
                            }
                            prevKey = k
                        }
                    }
                }
            }.toList()

            val iterator = object : VarArgFunction() {
                private var index = 0
                override fun invoke(args: Varargs): Varargs {
                    if (index >= merged.size) return NIL
                    val key = merged[index]
                    index++
                    return varargsOf(key, self.get(key))
                }
            }
            return@luaVarFunction varargsOf(iterator, self, LuaValue.NIL)
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
