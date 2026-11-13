package ru.pyxiion.ignis.parallel

import ru.pyxiion.luanova.vm2.LuaError
import ru.pyxiion.luanova.vm2.LuaState
import ru.pyxiion.luanova.vm2.LuaTable
import ru.pyxiion.luanova.vm2.LuaValue
import ru.pyxiion.luanova.vm2.interrupt.InterruptAction
import ru.pyxiion.luanova.vm2.interrupt.InterruptHandler
import ru.pyxiion.ignis.luaFunction
import ru.pyxiion.ignis.luaTableOf
import ru.pyxiion.ignis.runtime.installStdlib
import ru.pyxiion.ignis.sandbox.Vfs
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs Lua functions on pool threads. Each thread owns one isolated `LuaState` (built on that thread, with the
 * shared stdlib and nothing of the server), so Lua values never cross threads except as [Snap]s.
 * [dispatch] brings results back onto the server thread.
 */
internal class ParallelPool(private val vfs: Vfs, private val dispatch: (Runnable) -> Unit) {
    private val threadCount = Runtime.getRuntime().availableProcessors().minus(1).coerceIn(1, 4)
    private val threadIds = AtomicInteger()

    private val executor = ThreadPoolExecutor(
        threadCount, threadCount, 0, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(QUEUE_LIMIT),
        { r -> Thread(r, "PxIgnis-parallel-${threadIds.incrementAndGet()}").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    @Volatile
    private var closed = false

    private val envs = CopyOnWriteArrayList<WorkerEnv>()
    private val local = ThreadLocal<WorkerEnv>()

    /** The state of one pool thread. Must be constructed on that thread. */
    private inner class WorkerEnv {
        val state: LuaState = LuaState.builder()
            .interruptHandler(InterruptHandler {
                if (closed) {
                    // Re-arm first: a pcall inside the worker must not be able to swallow the cancellation.
                    state.interrupt()
                    throw LuaError("parallel call cancelled")
                }
                InterruptAction.CONTINUE
            })
            .build()

        val stdlib: StdlibRegistry = installStdlib(state, vfs)
        val shared = SharedLib(SharedLib.blockingSend, SharedLib.blockingReceive)

        init {
            val async = LuaTable()
            shared.install(async)
            async.setmetatable(luaTableOf("__index" to luaFunction { _, key ->
                throw LuaError("async.${key.tojstring()} is not available in a parallel function")
            }))
            state.globals.get("package").checktable().get("loaded").checktable().rawset("async", async)

            // Last: from here on a read of an unknown global or any assignment to a global fails.
            state.globals.setmetatable(LuaTable().also {
                it.rawset("__index", luaFunction { _, key ->
                    throw LuaError("global '${key.tojstring()}' is not available in a parallel function")
                })
                it.rawset("__newindex", luaFunction { _, key, _ ->
                    throw LuaError("assignment to global '${key.tojstring()}' is not allowed in a parallel function")
                })
            })
        }
    }

    /**
     * Queues `fn(args...)`. [onDone] runs on the server thread with the results or the failure, and not at all
     * when the pool was closed in the meantime.
     */
    fun submit(fn: Snap, args: Array<Snap>, onDone: (Result<Array<Snap>>) -> Unit) {
        try {
            executor.execute { run(fn, args, onDone) }
        } catch (e: RejectedExecutionException) {
            throw LuaError("async.parallel: too many pending calls (limit $QUEUE_LIMIT)")
        }
    }

    private fun run(fn: Snap, args: Array<Snap>, onDone: (Result<Array<Snap>>) -> Unit) {
        if (closed) return
        try {
            val env = local.get() ?: WorkerEnv().also {
                local.set(it)
                envs.add(it)
                // close() may have walked `envs` before this one was added: it would never be interrupted.
                if (closed) it.state.interrupt()
            }
            if (closed) return
            LuaState.setCurrent(env.state)

            val materializer = Materializer(env.state, env.shared)
            val f = materializer.materialize(fn)
            val result = f.invoke(LuaValue.varargsOf(Array(args.size) { materializer.materialize(args[it]) }))

            val snapshotter = Snapshotter(env.state.globals, env.stdlib, allowFunctions = false, op = "parallel function")
            val snaps = Array(result.narg()) { snapshotter.snapshot(result.arg(it + 1), "result #${it + 1}") }
            finish(Result.success(snaps), onDone)
        } catch (e: LuaError) {
            // Only the text crosses back: the error object belongs to the worker's state.
            finish(Result.failure(LuaError(e.message ?: "parallel function failed")), onDone)
        } catch (t: Throwable) {
            finish(Result.failure(LuaError("parallel function failed: $t")), onDone)
        }
    }

    private fun finish(result: Result<Array<Snap>>, onDone: (Result<Array<Snap>>) -> Unit) {
        if (!closed) dispatch { onDone(result) }
    }

    /** Cancels everything: queued jobs are dropped, running ones are interrupted, the threads end. */
    fun close() {
        closed = true
        executor.shutdownNow()
        for (env in envs) env.state.interrupt()
    }

    private companion object {
        const val QUEUE_LIMIT = 256
    }
}
