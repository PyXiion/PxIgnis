package ru.pyxiion.ignis.voice

import kotlinx.coroutines.CompletableDeferred
import ru.pyxiion.luanova.vm2.LuaValue

/**
 * What `voice.load` returns: decoding in progress, then [data] or [error]. Server thread only, except that
 * [done] may be awaited from a coroutine.
 */
class Sound(val name: String) {
    @Volatile
    var data: SoundData? = null
        private set

    @Volatile
    var error: String? = null
        private set

    /** Completes (never exceptionally) when decoding has finished, successfully or not. */
    val done = CompletableDeferred<Unit>()

    /** The Lua wrapper that was handed to scripts, passed to callbacks. */
    var lua: LuaValue = LuaValue.NIL

    private val waiters = ArrayList<() -> Unit>()

    val ready: Boolean get() = data != null
    val settled: Boolean get() = done.isCompleted

    fun complete(data: SoundData) {
        if (settled) return
        this.data = data
        finish()
    }

    fun fail(message: String) {
        if (settled) return
        error = message
        finish()
    }

    private fun finish() {
        done.complete(Unit)
        val run = ArrayList(waiters)
        waiters.clear()
        run.forEach { it() }
    }

    /** Runs [block] once decoding has settled: at once if it already has. */
    fun whenSettled(block: () -> Unit) {
        if (settled) block() else waiters.add(block)
    }
}
