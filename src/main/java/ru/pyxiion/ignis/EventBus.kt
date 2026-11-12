package ru.pyxiion.ignis

import org.luaj.vm2.LuaClosure
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaFunction
import org.luaj.vm2.LuaState
import org.luaj.vm2.LuaThread
import org.luaj.vm2.LuaValue
import org.slf4j.Logger

class EventHandler(
    val id: Int,
    val callback: LuaFunction,
    val throttle: Int = 0,
    var throttleRemaining: Int = 0,
) {
    /** Resetted at [EventBus.off] and [EventBus.clear] */
    var active = true
}

class EventBus(
    private val context: String,
    private val logger: Logger,
    private val stateProvider: () -> LuaState? = { null },
) {
    private val handlers = HashMap<String, List<EventHandler>>()
    private val byId = HashMap<Int, Pair<String, EventHandler>>()
    private var nextId = 0

    fun on(event: String, callback: LuaFunction, throttle: Int = 0): Int {
        require(throttle >= 0) { "throttle должен быть >= 0" }
        val id = ++nextId
        val entry = EventHandler(id, callback, throttle)
        handlers[event] = (handlers[event] ?: emptyList()) + entry
        byId[id] = event to entry
        return id
    }

    fun off(id: Int): Boolean {
        val (event, entry) = byId.remove(id) ?: return false
        entry.active = false
        val rest = (handlers[event] ?: emptyList()) - entry
        if (rest.isEmpty()) handlers.remove(event) else handlers[event] = rest
        return true
    }

    fun hasHandlers(event: String): Boolean = handlers.containsKey(event)

    /** [args] вызывается не более одного раза и только если у события есть подписчики. */
    fun fire(event: String, args: () -> Array<LuaValue>) {
        val list = handlers[event] ?: return
        val built = args()
        for (entry in list) call(event, entry, built)
    }

    fun fire(event: String, vararg args: LuaValue) = fire(event) { arrayOf(*args) }

    /** Истина, если какой-либо обработчик вернул `false`. Выполняются все обработчики. */
    fun fireCancellable(event: String, args: () -> Array<LuaValue>): Boolean {
        val list = handlers[event] ?: return false
        val built = args()
        var cancelled = false
        for (entry in list) {
            val r = call(event, entry, built) ?: continue
            if (r.isboolean() && !r.toboolean()) cancelled = true
        }
        return cancelled
    }

    fun fireCancellable(event: String, vararg args: LuaValue): Boolean =
        fireCancellable(event) { arrayOf(*args) }

    /** Результат обработчика или null, если он пропущен (снят, throttle) или упал. */
    private fun call(event: String, entry: EventHandler, args: Array<LuaValue>): LuaValue? {
        if (!entry.active) return null
        if (entry.throttle > 0) {
            if (entry.throttleRemaining > 0) return null
            entry.throttleRemaining = entry.throttle
        }
        return try {
            invokeCallback(entry.callback, args, event)
        } catch (e: LuaError) {
            logger.warn("Ошибка в Lua-обработчике события '$event'$context: ${e.message}")
            null
        } catch (e: Throwable) {
            logger.warn("Неизвестная ошибка в Lua-обработчике события '$event'$context: ${e.message}", e)
            null
        }
    }

    private fun invokeCallback(cb: LuaFunction, args: Array<LuaValue>, event: String): LuaValue {
        return when (cb) {
            is LuaClosure -> {
                val state = stateProvider()
                    ?: throw LuaError("Lua state is not available")
                val r = LuaThread(state, cb).resumeOrLog(LuaValue.varargsOf(args), "Событие '$event'$context")
                if (r.arg1().toboolean()) r.subargs(2).arg1() else r.arg(2)
            }
            else -> cb.invoke(LuaValue.varargsOf(args)).arg1()
        }
    }

    fun tick() {
        for (list in handlers.values) for (entry in list) {
            if (entry.throttleRemaining > 0) entry.throttleRemaining--
        }
    }

    fun clear() {
        for ((_, entry) in byId.values) entry.active = false
        handlers.clear()
        byId.clear()
        nextId = 0
    }
}
