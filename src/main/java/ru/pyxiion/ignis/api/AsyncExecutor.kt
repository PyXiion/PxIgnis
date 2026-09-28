package ru.pyxiion.ignis.api

import org.luaj.vm2.LuaError
import java.util.concurrent.ConcurrentHashMap

data class AsyncExecutor(
    val name: String,
    val dispatch: (Runnable) -> Unit,
    val shutdown: (() -> Unit)? = null,
    /** Runs tasks in parallel with other Lua code: each task then gets its own Lua state (see [AsyncLib.Isolation]). */
    val isolated: Boolean = false,
)

class AsyncExecutorRegistry {
    private val executors = ConcurrentHashMap<String, AsyncExecutor>()
    @Volatile
    private var shutDown = false

    fun register(executor: AsyncExecutor) {
        if (shutDown) {
            throw IllegalStateException("Cannot register executor after registry shutdown")
        }
        val existing = executors.putIfAbsent(executor.name, executor)
        if (existing != null) {
            throw IllegalArgumentException("Duplicate executor name: '${executor.name}'")
        }
    }

    fun resolve(name: String): AsyncExecutor {
        if (shutDown) {
            throw LuaError(
                "executor registry is shut down; cannot start new work on '$name'"
            )
        }
        return executors[name] ?: throw LuaError(
            "unknown executor '$name'. Available: ${names().joinToString(", ") { "'$it'" }}"
        )
    }

    fun names(): Set<String> = executors.keys.toSet()

    fun shutdown() {
        if (shutDown) return
        shutDown = true
        executors.values.forEach { it.shutdown?.invoke() }
    }
}
