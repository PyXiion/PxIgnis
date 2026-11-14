package ru.pyxiion.ignis.runtime

import ru.pyxiion.luanova.vm2.LuaError
import ru.pyxiion.luanova.vm2.interrupt.InterruptAction

/**
 * Stops Lua code that holds the server thread for too long (e.g. `while true do end` in a handler).
 *
 * Host code marks each entry into Lua with [guard]; PxLuaNova polls [checkpoint] every
 * `LuaState.DEFAULT_CHECKPOINT_INTERVAL` instructions. Once the outermost guarded call on the server thread
 * runs longer than [timeoutMillis], every following checkpoint throws until that call unwinds, so a
 * `pcall` loop cannot swallow the error and keep going.
 *
 * Lua running on other threads (`async.parallel` workers) is not limited: it does not stall the server.
 * State is touched only from the server thread, so it needs no synchronization.
 */
object ScriptWatchdog {
    /** 0 disables the limit. Override with `-Dpxignis.scriptTimeoutMs=<ms>`. */
    @Volatile
    var timeoutMillis: Long = System.getProperty("pxignis.scriptTimeoutMs")?.toLongOrNull() ?: 5000L

    @Volatile
    var serverThread: Thread? = null

    private var depth = 0
    private val logger = org.slf4j.LoggerFactory.getLogger("pxignis")
    private var sliceStart = 0L
    private var tripped = false

    inline fun <T> guard(block: () -> T): T {
        if (Thread.currentThread() !== serverThread) return block()
        enter()
        try {
            return block()
        } finally {
            leave()
        }
    }

    @PublishedApi
    internal fun enter() {
        if (depth++ == 0) {
            sliceStart = System.nanoTime()
            tripped = false
        }
    }

    @PublishedApi
    internal fun leave() {
        depth--
    }

    /**
     * Start of a server tick: no Lua call can be running, so a non-zero depth is a leaked [enter] and would make the
     * next timeout stick forever. Logs and clears it.
     */
    fun resetIfLeaked() {
        if (depth != 0) {
            logger.error("Script watchdog depth was {} at the start of a tick (leaked guard); resetting", depth)
            depth = 0
        }
        tripped = false
    }

    fun checkpoint(): InterruptAction {
        if (Thread.currentThread() !== serverThread || depth == 0 || timeoutMillis <= 0) return InterruptAction.CONTINUE
        if (tripped || System.nanoTime() - sliceStart > timeoutMillis * 1_000_000) {
            // Logged here, not only where the error surfaces: a script that pcalls around the failing code would
            // otherwise swallow every trace of it.
            if (!tripped) logger.warn("Script exceeded the {}ms time limit; its Lua calls are failing until it returns", timeoutMillis)
            tripped = true
            throw LuaError("script exceeded the ${timeoutMillis}ms time limit and was stopped")
        }
        return InterruptAction.CONTINUE
    }
}
