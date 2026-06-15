package ru.pyxiion.ignis

import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaFunction
import org.luaj.vm2.LuaValue
import org.slf4j.Logger

class EventHandler(
    val id: Int,
    val callback: LuaFunction,
    val throttle: Int = 0,
    var throttleRemaining: Int = 0,
)

class EventBus(
    private val context: String,
    private val logger: Logger,
) {
    private val handlers = mutableMapOf<String, MutableList<EventHandler>>()
    private val byId = mutableMapOf<Int, Pair<String, EventHandler>>()
    private var nextId = 0

    fun on(event: String, callback: LuaFunction, throttle: Int = 0): Int {
        require(throttle >= 0) { "throttle должен быть >= 0" }
        val id = ++nextId
        val entry = EventHandler(id, callback, throttle)
        handlers.getOrPut(event) { mutableListOf() }.add(entry)
        byId[id] = event to entry
        return id
    }

    fun off(id: Int): Boolean {
        val pair = byId.remove(id) ?: return false
        val (event, entry) = pair
        handlers[event]?.remove(entry)
        if (handlers[event]?.isEmpty() == true) handlers.remove(event)
        return true
    }

    fun hasHandlers(event: String): Boolean =
        handlers[event]?.isNotEmpty() == true

    fun fire(event: String, vararg args: LuaValue) {
        val list = handlers[event] ?: return
        list.forEach { entry ->
            if (entry.throttle > 0) {
                if (entry.throttleRemaining > 0) return@forEach
                entry.throttleRemaining = entry.throttle
            }
            try {
                entry.callback.invoke(LuaValue.varargsOf(args))
            } catch (e: LuaError) {
                logger.warn("Ошибка в Lua-обработчике события '$event'$context: ${e.message}")
            } catch (e: Throwable) {
                logger.warn("Неизвестная ошибка в Lua-обработчике события '$event'$context: ${e.message}", e)
            }
        }
    }

    fun fireWithResults(event: String, vararg args: LuaValue): List<LuaValue> {
        val results = mutableListOf<LuaValue>()
        val list = handlers[event] ?: return results
        list.forEach { entry ->
            try {
                results.add(entry.callback.invoke(LuaValue.varargsOf(args)).arg(1))
            } catch (e: LuaError) {
                logger.warn("Ошибка в Lua-обработчике события '$event'$context: ${e.message}")
            } catch (e: Throwable) {
                logger.warn("Неизвестная ошибка в Lua-обработчике события '$event'$context: ${e.message}", e)
            }
        }
        return results
    }

    fun tick() {
        handlers.values.forEach { list ->
            list.forEach { entry ->
                if (entry.throttleRemaining > 0) entry.throttleRemaining--
            }
        }
    }

    fun clear() {
        handlers.clear()
        byId.clear()
        nextId = 0
    }
}
