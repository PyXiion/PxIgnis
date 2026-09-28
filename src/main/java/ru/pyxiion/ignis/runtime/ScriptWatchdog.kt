package ru.pyxiion.ignis.runtime

import org.luaj.vm2.LuaError
import org.luaj.vm2.interrupt.InterruptAction

/**
 * Stops Lua code that holds the server thread for too long (e.g. `while true do end` in a handler).
 *
 * Host code marks each entry into Lua with [guard]; PxLuaNova polls [checkpoint] every
 * `LuaState.DEFAULT_CHECKPOINT_INTERVAL` instructions. Once the outermost guarded call on the server thread
 * runs longer than [timeoutMillis], every following checkpoint throws until that call unwinds, so a
 * `pcall` loop cannot swallow the error and keep going.
 *
 * Lua running on other threads (the `threadpool` async executor) is not limited: it does not stall the server.
 * State is touched only from the server thread, so it needs no synchronization.
 */
object ScriptWatchdog {
    /** 0 disables the limit. Override with `-Dpxignis.scriptTimeoutMs=<ms>`. */
    @Volatile
    var timeoutMillis: Long = System.getProperty("pxignis.scriptTimeoutMs")?.toLongOrNull() ?: 5000L

    @Volatile
    var serverThread: Thread? = null

    private var depth = 0
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

    fun checkpoint(): InterruptAction {
        if (Thread.currentThread() !== serverThread || depth == 0 || timeoutMillis <= 0) return InterruptAction.CONTINUE
        if (tripped || System.nanoTime() - sliceStart > timeoutMillis * 1_000_000) {
            tripped = true
            throw LuaError("script exceeded the ${timeoutMillis}ms time limit and was stopped")
        }
        return InterruptAction.CONTINUE
    }
}
