package ru.pyxiion.ignis

import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaFunction
import org.luaj.vm2.LuaValue
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class LuaEventManager {
    private val handlers = mutableMapOf<String, MutableList<LuaFunction>>()

    fun on(event: String, handler: LuaFunction) {
        handlers.getOrPut(event) { mutableListOf() }.add(handler)
    }

    fun fire(event: String, vararg args: LuaValue) {
        handlers[event]?.forEach { handler ->
            try {
                handler.invoke(args)
            } catch (e: LuaError) {
                LOGGER.warn("Ошибка в Lua-обработчике события '$event': ${e.message}")
            } catch (e: Throwable) {
                LOGGER.warn("Неизвестная ошибка в Lua-обработчике события '$event': ${e.message}", e)
            }
        }
    }

    fun fireWithResults(event: String, vararg args: LuaValue): List<LuaValue> {
        val results = mutableListOf<LuaValue>()
        handlers[event]?.forEach { handler ->
            try {
                results.add(handler.invoke(args).arg(1))
            } catch (e: LuaError) {
                LOGGER.warn("Ошибка в Lua-обработчике события '$event': ${e.message}")
            } catch (e: Throwable) {
                LOGGER.warn("Неизвестная ошибка в Lua-обработчике события '$event': ${e.message}", e)
            }
        }
        return results
    }

    fun clear() {
        handlers.clear()
    }

    companion object {
        private val LOGGER: Logger = LoggerFactory.getLogger(LuaEventManager::class.java)
    }
}
