package ru.pyxiion.ignis

import org.luaj.vm2.LuaClosure
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaFunction
import org.luaj.vm2.LuaState
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaThread
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.slf4j.Logger
import ru.pyxiion.ignis.events.EventCatalog
import ru.pyxiion.ignis.events.EventDef
import ru.pyxiion.ignis.events.LegacyEvent
import ru.pyxiion.ignis.runtime.ScriptErrors
import ru.pyxiion.ignis.runtime.ScriptWatchdog

/**
 * @property priority handlers with a higher number run first; equal priorities run in registration order.
 * @property receiveCancelled when false (the default), the handler is skipped once the event is cancelled.
 * @property throttle minimum number of ticks between two calls of this handler (0 = no limit).
 */
data class HandlerOptions(val priority: Int = 0, val receiveCancelled: Boolean = false, val throttle: Int = 0) {
    init {
        require(throttle >= 0) { "throttle must be >= 0" }
    }

    companion object {
        val DEFAULT = HandlerOptions()

        val PRIORITY_ALIASES: Map<String, Int> = linkedMapOf(
            "lowest" to -200, "low" to -100, "normal" to 0, "high" to 100, "highest" to 200,
        )

        /** Parses the options table of `mc.on` / `region:on`; nil means defaults. */
        fun parse(value: LuaValue, where: String): HandlerOptions {
            if (value.isnil()) return DEFAULT
            if (!value.istable()) throw LuaError("$where: options must be a table, got ${value.typename()}")
            val t = value.checktable()
            var key = LuaValue.NIL
            while (true) {
                key = t.next(key).arg1()
                if (key.isnil()) break
                if (key.tojstring() !in KNOWN_OPTIONS) {
                    throw LuaError("$where: unknown option '${key.tojstring()}' (known: ${KNOWN_OPTIONS.joinToString()})")
                }
            }
            val p = t.get("priority")
            val priority = when {
                p.isnil() -> 0
                p.isnumber() -> p.toint()
                p.isstring() -> PRIORITY_ALIASES[p.tojstring()]
                    ?: throw LuaError("$where: unknown priority '${p.tojstring()}' (use a number or one of ${PRIORITY_ALIASES.keys.joinToString()})")
                else -> throw LuaError("$where: priority must be a number or a string")
            }
            val throttle = t.get("throttle").optint(0)
            if (throttle < 0) throw LuaError("$where: throttle must be >= 0")
            return HandlerOptions(priority, t.get("receiveCancelled").optboolean(false), throttle)
        }

        private val KNOWN_OPTIONS = setOf("priority", "receiveCancelled", "throttle")

        /**
         * Reads `(handler, opts?)` or `(opts, handler)` - the second order is what a Nova trailing block produces:
         * `mc.on("block.break", { priority = "high" }) \{ e -> ... }`.
         */
        fun handlerAndOptions(a: LuaValue, b: LuaValue, where: String): Pair<LuaFunction, HandlerOptions> =
            if (a.istable() && b.isfunction()) b.checkfunction() to parse(a, where)
            else (a as? LuaFunction ?: throw LuaError("$where: handler must be a function, got ${a.typename()}")) to parse(b, where)
    }
}

class EventHandler internal constructor(
    val id: Int,
    /** The name the script passed to `on`. */
    val registeredAs: String,
    val callback: LuaFunction,
    val options: HandlerOptions,
    internal val legacy: LegacyEvent?,
) {
    internal var nextAllowedTick = Long.MIN_VALUE
    internal var removed = false
}

/** What happened to a posted event. [event] is null when nobody listened. */
class EventResult(val cancelled: Boolean, val event: LuaTable?) {
    /** The reason passed to `e:cancel(reason)`, if any. */
    val reason: String? get() = event?.rawget("reason")?.takeIf { it.isstring() }?.tojstring()

    companion object {
        @JvmField
        val NONE = EventResult(false, null)
    }
}

/**
 * Handlers for one scope (the global `mc.on`, or one region).
 *
 * Built-in events ([catalog]) pass handlers a single event table `e`; handlers cancel with `e:cancel()`.
 * Handlers registered under a pre-1.0 name ([LegacyEvent]) still get positional arguments and cancel by
 * returning false. Custom events (`mc.emit`) pass their arguments through as they are.
 *
 * For every event, handlers run by priority and a cancelled event skips handlers without `receiveCancelled`.
 * A handler that throws cancels a built-in cancellable event (fail closed), so a broken permission check does
 * not let the action through.
 *
 * @param context shown in error messages, e.g. "region #3"; empty for the global bus.
 * @param clock the current tick for `throttle`; by default a counter advanced by [tick].
 */
class EventBus(
    private val context: String,
    private val logger: Logger,
    private val stateProvider: () -> LuaState? = { null },
    private val catalog: EventCatalog = EventCatalog.EMPTY,
    private val clock: (() -> Long)? = null,
) {
    private val handlers = mutableMapOf<String, MutableList<EventHandler>>()
    private val byId = mutableMapOf<Int, Pair<String, EventHandler>>()
    private var nextId = 0
    private var ticks = 0L
    private val warnedLegacy = HashSet<String>()

    fun on(event: String, callback: LuaFunction, options: HandlerOptions = HandlerOptions.DEFAULT): Int {
        val legacy = catalog.legacy(event)
        val key = when {
            legacy != null -> {
                if (warnedLegacy.add(event)) {
                    logger.warn(
                        "{}: event '{}' is deprecated, use '{}' (its handler gets an event table)",
                        scope(), event, legacy.target,
                    )
                }
                legacy.target
            }
            catalog.def(event) != null -> event
            else -> {
                if (!catalog.isEmpty && ':' !in event) warnUnknown(event)
                event
            }
        }
        val id = ++nextId
        val entry = EventHandler(id, event, callback, options, legacy)
        val list = handlers.getOrPut(key) { mutableListOf() }
        // Stable: after every handler of the same or higher priority.
        val at = list.indexOfFirst { it.options.priority < options.priority }
        if (at < 0) list.add(entry) else list.add(at, entry)
        byId[id] = key to entry
        return id
    }

    private fun warnUnknown(event: String) {
        val hint = catalog.suggest(event)?.let { " Did you mean '$it'?" }
            ?: " Custom events should be namespaced, e.g. 'mymod:$event'."
        ScriptErrors.warn(scope(), "unknown event '$event', the handler will never be called.$hint")
    }

    fun off(id: Int): Boolean {
        val (key, entry) = byId.remove(id) ?: return false
        entry.removed = true
        handlers[key]?.remove(entry)
        if (handlers[key]?.isEmpty() == true) handlers.remove(key)
        return true
    }

    /** True when some handler listens to [event] (a built-in, legacy or custom name). */
    fun hasHandlers(event: String): Boolean =
        handlers[catalog.legacy(event)?.target ?: event]?.isNotEmpty() == true

    /** Handler count per name the scripts registered, for `/ignis status`. */
    fun stats(): Map<String, Int> =
        byId.values.groupingBy { it.second.registeredAs }.eachCount().toSortedMap()

    /** Fires a built-in event without fields. */
    fun fire(event: String) {
        post(event)
    }

    /**
     * Fires the built-in [event]. [fill] sets the event's fields and runs only when someone listens,
     * so posting an event with no handlers costs a map lookup.
     */
    fun post(event: String, fill: (LuaTable) -> Unit = {}): EventResult {
        val def = catalog.def(event) ?: throw IllegalArgumentException("'$event' is not a built-in event of this bus")
        val list = handlers[event]
        if (list.isNullOrEmpty()) return EventResult.NONE
        val e = newEvent(def)
        fill(e)
        return EventResult(dispatch(event, list, def.cancellable, e, LuaValue.NONE), e)
    }

    /**
     * Fires a custom event (`mc.emit`): handlers get [args] as they are, and a handler cancels it by returning
     * false. Returns false when the event was cancelled.
     */
    fun emit(event: String, args: Varargs): Boolean {
        if (catalog.isBuiltIn(event)) throw LuaError("'$event' is a built-in event and cannot be emitted from Lua")
        val list = handlers[event]
        if (list.isNullOrEmpty()) return true
        return !dispatch(event, list, true, null, args)
    }

    /** Runs [list]; returns whether the event ended up cancelled. [e] is null for custom events. */
    private fun dispatch(event: String, list: List<EventHandler>, cancellable: Boolean, e: LuaTable?, args: Varargs): Boolean {
        var cancelled = false
        val now = clock?.invoke() ?: ticks
        for (h in list.toTypedArray()) {
            if (h.removed) continue
            if (e != null && cancellable) cancelled = e.rawget("cancelled").toboolean()
            if (cancelled && !h.options.receiveCancelled) continue
            val legacy = h.legacy
            if (legacy != null && !legacy.accepts(e!!)) continue
            if (h.options.throttle > 0) {
                if (now < h.nextAllowedTick) continue
                h.nextAllowedTick = now + h.options.throttle
            }
            val callArgs: Varargs = when {
                legacy != null -> LuaValue.varargsOf(legacy.args(e!!))
                e != null -> e
                else -> args
            }
            try {
                val r = invoke(h.callback, callArgs).arg1()
                if (cancellable && (legacy != null || e == null) && r.isboolean() && !r.toboolean()) {
                    cancelled = true
                    e?.rawset("cancelled", LuaValue.TRUE)
                }
            } catch (t: Throwable) {
                val where = scope(h.registeredAs)
                // Built-in cancellable events fail closed; a custom event is only cancelled by `return false`.
                if (cancellable && e != null) {
                    e.rawset("cancelled", LuaValue.TRUE)
                    ScriptErrors.error("$where, event cancelled because the handler failed", t)
                } else {
                    ScriptErrors.error(where, t)
                }
            }
        }
        if (e != null) {
            cancelled = cancellable && e.rawget("cancelled").toboolean()
            (e.rawget(STATE_KEY).touserdata() as EventState).finished = true
        }
        return cancelled
    }

    private fun invoke(cb: LuaFunction, args: Varargs): Varargs = when (cb) {
        is LuaClosure -> {
            val state = stateProvider() ?: throw LuaError("Lua state is not available")
            val thread = LuaThread(state, cb)
            val r = ScriptWatchdog.guard { thread.resume(args) }
            when {
                !r.arg1().toboolean() -> throw thread.lastError ?: LuaError(r.arg(2).tojstring())
                // Still running asynchronously: whatever it returns later is too late to count.
                thread.status != "dead" -> LuaValue.NONE
                else -> r.subargs(2)
            }
        }
        else -> ScriptWatchdog.guard { cb.invoke(args) }
    }

    private fun scope(event: String? = null): String {
        val name = if (event == null) "events" else "event '$event'"
        return if (context.isEmpty()) name else "$name ($context)"
    }

    /** Advances the default throttle clock by one tick. */
    fun tick() {
        ticks++
    }

    fun clear() {
        byId.values.forEach { it.second.removed = true }
        handlers.clear()
        byId.clear()
        warnedLegacy.clear()
        nextId = 0
    }

    private class EventState(val name: String, val cancellable: Boolean) {
        var finished = false
    }

    private fun newEvent(def: EventDef): LuaTable {
        val e = LuaTable()
        e.rawset("name", LuaValue.valueOf(def.name))
        e.rawset("cancellable", LuaValue.valueOf(def.cancellable))
        e.rawset("cancelled", LuaValue.FALSE)
        e.rawset(STATE_KEY, LuaValue.userdataOf(EventState(def.name, def.cancellable)))
        e.setmetatable(EVENT_META)
        return e
    }

    private companion object {
        const val STATE_KEY = "__pxrp_object"

        val EVENT_META: LuaTable = LuaTable().apply {
            rawset("__index", LuaTable().apply {
                rawset("cancel", luaVarFunction { args ->
                    val e = args.checktable(1)
                    val state = e.rawget(STATE_KEY).touserdata() as? EventState
                        ?: throw LuaError("cancel: expected an event table")
                    if (!state.cancellable) throw LuaError("event '${state.name}' cannot be cancelled")
                    if (state.finished) {
                        throw LuaError("event '${state.name}' has already happened; call e:cancel() before the first async call")
                    }
                    e.rawset("cancelled", LuaValue.TRUE)
                    if (!args.arg(2).isnil()) e.rawset("reason", args.arg(2).checkstring())
                    LuaValue.NONE
                })
            })
        }
    }
}
