package ru.pyxiion.ignis.api

import org.luaj.vm2.*
import org.luaj.vm2.lib.LuaContinuableFunction
import ru.pyxiion.ignis.*
import ru.pyxiion.ignis.runtime.ScriptWatchdog
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class AsyncLib(
    private val executors: AsyncExecutorRegistry,
    private val luaState: LuaState,
    private val scheduler: Scheduler,
    private val isolation: Isolation? = null,
) {

    /**
     * Lua states are single-threaded, so tasks on an [AsyncExecutor.isolated] executor run in a fresh state
     * from [newWorkerState], and work a worker sends to any other executor runs in [mainState]. Crossing states
     * copies the function, its upvalues, the arguments and the results with [LuaTransfer]; [userdata] decides
     * which userdata may cross. Without an Isolation every task runs in this library's own state.
     */
    class Isolation(
        val mainState: LuaState,
        val userdata: LuaTransfer.UserdataPolicy,
        val newWorkerState: () -> LuaState,
    )

    // ── Executor resolution ──────────────────────────────────────────

    private fun requireExecutor(value: LuaValue): AsyncExecutor {
        val name = value.checkjstring()
            ?: throw LuaError("async: expected executor name (string), got ${value.typename()}")
        return executors.resolve(name)
    }

    // ── Serialized resumer ────────────────────────────────────────────
    // Serializes all resume requests for one LuaThread through a single
    // selected executor. Only one thread.resume() runs at a time; requests
    // that arrive while a resume is in flight are queued and dispatched
    // after the current resume returns. Requests for dead coroutines drop.
    // If the resume callback throws or the executor rejects the dispatch,
    // the owner is notified once through onFailure and queued resumes drop.

    internal class SerializedResumer(
        private val isDead: () -> Boolean,
        private val executor: AsyncExecutor,
        private val resume: (Varargs) -> Unit,
        private val onFailure: (Throwable) -> Unit,
    ) {
        private val lock = ReentrantLock()
        private var running = false
        private var failed = false
        private val pending = ArrayDeque<Varargs>()

        /** Submit a resume request. Dispatches immediately if idle, queues otherwise. */
        fun requestResume(args: Varargs) {
            var runNow = false
            lock.withLock {
                if (failed || isDead()) return
                if (running) {
                    pending.addLast(args)
                    return
                }
                running = true
                runNow = true
            }
            if (runNow) dispatch(args)
        }

        private fun dispatch(args: Varargs) {
            try {
                executor.dispatch { runResume(args) }
            } catch (e: Throwable) {
                fail(e)
            }
        }

        private fun runResume(args: Varargs) {
            var error: Throwable? = null
            try {
                resume(args)
            } catch (e: Throwable) {
                error = e
            }
            var next: Varargs? = null
            lock.withLock {
                if (error != null) {
                    failed = true
                    pending.clear()
                    running = false
                } else if (isDead()) {
                    pending.clear()
                    running = false
                } else if (pending.isNotEmpty()) {
                    next = pending.removeFirst()
                } else {
                    running = false
                }
            }
            if (error != null) {
                notifyFailure(error)
                return
            }
            if (next != null) dispatch(next)
        }

        private fun fail(e: Throwable) {
            var notify: Throwable? = null
            lock.withLock {
                if (!failed) {
                    failed = true
                    pending.clear()
                    running = false
                    notify = e
                }
            }
            if (notify != null) notifyFailure(notify)
        }

        private fun notifyFailure(e: Throwable) {
            try {
                onFailure(e)
            } catch (_: Throwable) {
                // The failure handler must not re-enter or break the resumer.
            }
        }
    }

    // ── Awaitable ──────────────────────────────────────────────────────

    class Awaitable {
        private val lock = ReentrantLock()
        private var _state = STATE_PENDING
        private var _result: Varargs = LuaValue.NONE
        private var _error: Throwable? = null
        private val waiters = mutableListOf<(String, Varargs, Throwable?) -> Unit>()

        // Results produced in another Lua state: kept as a snapshot and rebuilt, once, on the first read.
        // Reads happen on the consumer's own thread (wait/try/run/all continuations).
        private var _transferred: LuaTransfer.Snapshot? = null
        private var _materialize: ((LuaTransfer.Snapshot) -> Varargs)? = null

        val state: String get() { lock.withLock { return _state } }
        val isDone: Boolean get() { lock.withLock { return _state != STATE_PENDING } }
        val error: Throwable? get() { lock.withLock { return _error } }

        fun resolve(result: Varargs = LuaValue.NONE): Boolean {
            lock.withLock {
                if (_state != STATE_PENDING) return false
                _state = STATE_RESOLVED
                _result = result
            }
            notifyWaiters()
            return true
        }

        fun resolveTransferred(snapshot: LuaTransfer.Snapshot, materialize: (LuaTransfer.Snapshot) -> Varargs): Boolean {
            lock.withLock {
                if (_state != STATE_PENDING) return false
                _state = STATE_RESOLVED
                _transferred = snapshot
                _materialize = materialize
            }
            notifyWaiters()
            return true
        }

        private fun resultLocked(): Varargs {
            val snapshot = _transferred ?: return _result
            _result = _materialize!!(snapshot)
            _transferred = null
            _materialize = null
            return _result
        }

        fun reject(err: Throwable): Boolean {
            lock.withLock {
                if (_state != STATE_PENDING) return false
                _state = STATE_REJECTED
                _error = err
            }
            notifyWaiters()
            return true
        }

        fun get(): Varargs = lock.withLock {
            when (_state) {
                STATE_RESOLVED -> resultLocked()
                STATE_REJECTED -> throw _error ?: LuaError("awaitable rejected")
                else -> throw LuaError("awaitable is still pending")
            }
        }

        fun getOrNull(): Varargs? = lock.withLock {
            when (_state) {
                STATE_RESOLVED -> resultLocked()
                else -> null
            }
        }

        fun await(callback: (String, Varargs, Throwable?) -> Unit) {
            lock.withLock {
                if (_state != STATE_PENDING) {
                    callback(_state, _result, _error)
                    return
                }
                waiters.add(callback)
            }
        }

        private fun notifyWaiters() {
            val snapshot: List<(String, Varargs, Throwable?) -> Unit>
            lock.withLock {
                snapshot = waiters.toList()
                waiters.clear()
            }
            val s = state
            for (w in snapshot) w(s, _result, _error)
        }

        companion object {
            const val STATE_PENDING = "pending"
            const val STATE_RESOLVED = "resolved"
            const val STATE_REJECTED = "rejected"
        }
    }

    // ── Async object (Lua userdata) ───────────────────────────────────

    class AsyncObject(
        val awaitable: Awaitable,
        val type: String
    )

    private fun wrapObject(awaitable: Awaitable, type: String): LuaValue {
        return LuaValue.userdataOf(AsyncObject(awaitable, type), ASYNC_METATABLE)
    }

    private fun extractAwaitable(args: Varargs, index: Int = 1, op: String = "async"): Awaitable {
        return args.arg(index).asObject<AsyncObject>()?.awaitable
            ?: throw LuaError("$op: expected task or promise, got ${args.arg(index).typename()}")
    }

    // ── Metatable ──────────────────────────────────────────────────────

    private val ASYNC_METATABLE by lazy {
        luaTableOf().also { mt ->
            mt.set("__index", luaFunction { self, key ->
                val obj = self.asObject<AsyncObject>()
                    ?: throw LuaError("expected async object, got ${self.typename()}")
                when (key.optjstring(null)) {
                    "done" -> obj.awaitable.isDone.toLua()
                    "state" -> LuaValue.valueOf(obj.awaitable.state)
                    "type" -> LuaValue.valueOf(obj.type)
                    "wait" -> waitFn
                    "try" -> tryFn
                    "resolve" -> resolveFn
                    "error" -> errorFn
                    else -> mt.get(key)
                }
            })
        }
    }

    // ── wait() ─────────────────────────────────────────────────────────

    private val waitFn: LuaFunction by lazy {
        object : LuaContinuableFunction<Awaitable>() {
            override fun invoke(args: Varargs, continuation: Awaitable?): Varargs {
                if (continuation != null) return continuation.get()

                val awaitable = extractAwaitable(args, op = "wait")
                if (awaitable.isDone) return awaitable.get()

                val coro = luaState.currentThread
                    ?: throw LuaError("wait: must be called inside a coroutine")

                awaitable.await { _, _, _ ->
                    resumeThread(coro, LuaValue.NONE, "async.wait callback")
                }
                throw YieldContinuationException(this, args, awaitable)
            }
        }
    }

    // ── try() ──────────────────────────────────────────────────────────

    private val tryFn: LuaFunction by lazy {
        object : LuaContinuableFunction<Awaitable>() {
            override fun invoke(args: Varargs, continuation: Awaitable?): Varargs {
                if (continuation != null) {
                    return when (continuation.state) {
                        Awaitable.STATE_RESOLVED -> LuaValue.varargsOf(LuaValue.TRUE, continuation.get())
                        Awaitable.STATE_REJECTED -> LuaValue.varargsOf(
                            LuaValue.FALSE,
                            LuaValue.valueOf(continuation.error?.message ?: "unknown error")
                        )
                        else -> LuaValue.FALSE
                    }
                }

                val awaitable = extractAwaitable(args, op = "try")
                if (awaitable.isDone) {
                    return when (awaitable.state) {
                        Awaitable.STATE_RESOLVED -> LuaValue.varargsOf(LuaValue.TRUE, awaitable.get())
                        Awaitable.STATE_REJECTED -> LuaValue.varargsOf(
                            LuaValue.FALSE,
                            LuaValue.valueOf(awaitable.error?.message ?: "unknown error")
                        )
                        else -> LuaValue.FALSE
                    }
                }

                val coro = luaState.currentThread
                    ?: throw LuaError("try: must be called inside a coroutine")

                // The continuation reads the outcome; results may only be read on this coroutine's thread.
                awaitable.await { _, _, _ ->
                    resumeThread(coro, LuaValue.NONE, "async.try callback")
                }
                throw YieldContinuationException(this, args, awaitable)
            }
        }
    }

    // ── Task execution helpers ────────────────────────────────────────

    /**
     * Resumes a task coroutine of [state]. When [exportTo] is set the task runs in another state than this
     * library's, and its results are handed over as a snapshot that this library materializes on read.
     */
    private fun resumeTask(
        thread: LuaThread,
        args: Varargs,
        awaitable: Awaitable,
        state: LuaState = luaState,
        exportTo: LuaTransfer.UserdataPolicy? = null,
    ) {
        val previous = LuaState.current()
        LuaState.setCurrent(state)
        try {
            val result = ScriptWatchdog.guard { thread.resume(args) }
            if (thread.status == "dead") {
                if (result.arg1().toboolean()) {
                    if (exportTo == null) {
                        awaitable.resolve(result.subargs(2))
                    } else {
                        val snapshot = LuaTransfer.snapshot(result.subargs(2), state, exportTo) { "task result #$it" }
                        awaitable.resolveTransferred(snapshot) { LuaTransfer.materialize(it, luaState, exportTo) }
                    }
                } else {
                    awaitable.reject(LuaError(result.arg(2).optjstring("task error")))
                }
            }
        } catch (e: Throwable) {
            awaitable.reject(e)
        } finally {
            LuaState.setCurrent(previous)
        }
    }

    private fun createTaskInternal(executor: AsyncExecutor, f: LuaFunction, taskArgs: Varargs): Awaitable {
        val iso = isolation
        if (iso != null && (executor.isolated || luaState !== iso.mainState)) {
            return createCrossStateTask(iso, executor, f, taskArgs)
        }
        val awaitable = Awaitable()
        val thread = LuaThread(luaState, f)
        thread.executionContext = executor

        val resumer = SerializedResumer(
            { thread.status == "dead" },
            executor,
            resume = { args -> resumeTask(thread, args, awaitable) },
            onFailure = { e -> awaitable.reject(e) },
        )
        thread.resumeHandler = LuaThread.ResumeHandler { _, value ->
            resumer.requestResume(value)
        }

        resumer.requestResume(taskArgs)
        return awaitable
    }

    /**
     * Runs [f] in another Lua state: a fresh worker state for an isolated executor, the main state otherwise.
     * The function and arguments are snapshotted here, on the caller's thread, and rebuilt on the executor's
     * thread before the first resume, so neither state is ever touched by the other's thread.
     */
    private fun createCrossStateTask(iso: Isolation, executor: AsyncExecutor, f: LuaFunction, taskArgs: Varargs): Awaitable {
        val payload = LuaTransfer.snapshot(LuaValue.varargsOf(f, taskArgs), luaState, iso.userdata) { i ->
            if (i == 1) "the task function" else "task argument #${i - 1}"
        }
        val awaitable = Awaitable()
        val task = CrossStateTask()
        lateinit var resumer: SerializedResumer
        resumer = SerializedResumer(
            { task.thread?.status == "dead" },
            executor,
            resume = { args ->
                var thread = task.thread
                var resumeArgs = args
                if (thread == null) {
                    val state = if (executor.isolated) iso.newWorkerState() else iso.mainState
                    val values = LuaTransfer.materialize(payload, state, iso.userdata)
                    thread = LuaThread(state, values.arg1())
                    thread.executionContext = executor
                    thread.resumeHandler = LuaThread.ResumeHandler { _, value -> resumer.requestResume(value) }
                    task.thread = thread
                    resumeArgs = values.subargs(2)
                }
                resumeTask(thread, resumeArgs, awaitable, thread.state, iso.userdata)
            },
            onFailure = { e -> awaitable.reject(e) },
        )
        resumer.requestResume(LuaValue.NONE)
        return awaitable
    }

    companion object {
        /** Tasks, promises and mutexes: bound to the Lua state that created them. */
        fun isAsyncObject(instance: Any?): Boolean = instance is AsyncObject || instance is MutexObject
    }

    private class CrossStateTask {
        @Volatile
        var thread: LuaThread? = null
    }

    // ── Task ───────────────────────────────────────────────────────────

    private fun handleTask(args: Varargs): Varargs {
        val executor = requireExecutor(args.arg(1))
        val f = args.arg(2).asFunction()
            ?: throw LuaError("async.task: expected function, got ${args.arg(2).typename()}")
        val taskArgs = args.subargs(3)

        val awaitable = createTaskInternal(executor, f, taskArgs)
        return wrapObject(awaitable, "task")
    }

    // ── async.run ──────────────────────────────────────────────────────

    private fun handleRun(args: Varargs): Varargs {
        val executor = requireExecutor(args.arg(1))
        val f = args.arg(2).asFunction()
            ?: throw LuaError("async.run: expected function, got ${args.arg(2).typename()}")
        val taskArgs = args.subargs(3)

        val awaitable = createTaskInternal(executor, f, taskArgs)

        if (awaitable.isDone) {
            return if (awaitable.state == Awaitable.STATE_RESOLVED) {
                awaitable.get()
            } else {
                throw awaitable.error ?: LuaError("async.run: task failed")
            }
        }

        val coro = luaState.currentThread
            ?: throw LuaError("async.run: must be called inside a coroutine")

        awaitable.await { _, _, _ ->
            resumeThread(coro, LuaValue.NONE, "async.run callback")
        }
        throw YieldContinuationException(runContinuable, args, awaitable)
    }

    private val runContinuable: LuaFunction by lazy {
        object : LuaContinuableFunction<Awaitable>() {
            override fun invoke(args: Varargs, continuation: Awaitable?): Varargs {
                if (continuation != null) {
                    if (continuation.state == Awaitable.STATE_RESOLVED) {
                        return continuation.get()
                    } else {
                        throw continuation.error ?: LuaError("async.run: task failed")
                    }
                }
                return handleRun(args)
            }
        }
    }

    // ── Promise ────────────────────────────────────────────────────────

    private fun handlePromise(@Suppress("UNUSED_PARAMETER") args: Varargs): Varargs {
        return wrapObject(Awaitable(), "promise")
    }

    private val resolveFn: LuaFunction by lazy { luaVarFunction(::handleResolve) }
    private val errorFn: LuaFunction by lazy { luaVarFunction(::handleError) }

    private fun handleResolve(args: Varargs): Varargs {
        val awaitable = extractAwaitable(args, op = "resolve")
        val values = if (args.narg() >= 2) args.subargs(2) else LuaValue.NONE
        val settled = awaitable.resolve(values)
        return settled.toLua()
    }

    private fun handleError(args: Varargs): Varargs {
        val awaitable = extractAwaitable(args, op = "error")
        val message = if (args.narg() >= 2) args.arg(2).tojstring() else "promise rejected"
        val settled = awaitable.reject(LuaError(message))
        return settled.toLua()
    }

    // ── all / allSettled ───────────────────────────────────────────────

    private fun collectAwaitables(args: Varargs): List<Awaitable> {
        val first = args.arg(1)
        return if (first.istable()) {
            val table = first.checktable()
            val len = table.length().toInt()
            (1..len).map { i ->
                table.get(i).asObject<AsyncObject>()?.awaitable
                    ?: throw LuaError("async.all: expected task/promise at index $i, got ${table.get(i).typename()}")
            }
        } else {
            (1..args.narg()).map { i ->
                args.arg(i).asObject<AsyncObject>()?.awaitable
                    ?: throw LuaError("async.all: expected task/promise at index $i, got ${args.arg(i).typename()}")
            }
        }
    }

    private fun handleAll(args: Varargs): Varargs {
        val awaitables = collectAwaitables(args)
        if (awaitables.isEmpty()) return LuaTable()
        if (awaitables.all { it.isDone }) {
            throwIfAnyRejected(awaitables)
            return buildAllResults(awaitables)
        }

        val coro = luaState.currentThread
            ?: throw LuaError("async.all: must be called inside a coroutine")

        val remaining = AtomicInteger(awaitables.size)
        val allFuture = CompletableFuture<Void>()

        for (a in awaitables) {
            a.await { _, _, _ ->
                if (remaining.decrementAndGet() == 0) allFuture.complete(null)
            }
        }

        allFuture.thenRun {
            resumeThread(coro, LuaValue.NONE, "async.all callback")
        }
        throw YieldContinuationException(this@AsyncLib.allContinuable, args, allFuture)
    }

    private val allContinuable: LuaFunction by lazy {
        object : LuaContinuableFunction<CompletableFuture<Void>>() {
            override fun invoke(args: Varargs, continuation: CompletableFuture<Void>?): Varargs {
                if (continuation != null) {
                    val awaitables = collectAwaitables(args)
                    throwIfAnyRejected(awaitables)
                    return buildAllResults(awaitables)
                }
                return handleAll(args)
            }
        }
    }

    private fun throwIfAnyRejected(awaitables: List<Awaitable>) {
        val first = awaitables.firstOrNull { it.state == Awaitable.STATE_REJECTED }
        if (first != null) throw first.error ?: LuaError("async.all: task failed")
    }

    private fun buildAllResults(awaitables: List<Awaitable>): LuaTable {
        val t = LuaTable()
        for (i in awaitables.indices) {
            val a = awaitables[i]
            val entry = LuaTable()
            entry.rawset("ok", LuaValue.valueOf(a.state == Awaitable.STATE_RESOLVED))
            val value = a.getOrNull()
            if (value != null) entry.rawset("value", value.arg(1))
            val err = a.error
            if (err != null) entry.rawset("error", LuaValue.valueOf(err.message ?: "unknown error"))
            t.set(i + 1, entry)
        }
        return t
    }

    private fun handleAllSettled(args: Varargs): Varargs {
        val awaitables = collectAwaitables(args)
        if (awaitables.isEmpty()) return LuaTable()
        if (awaitables.all { it.isDone }) return buildAllResults(awaitables)

        val coro = luaState.currentThread
            ?: throw LuaError("async.allSettled: must be called inside a coroutine")

        val remaining = AtomicInteger(awaitables.size)
        val allFuture = CompletableFuture<Void>()

        for (a in awaitables) {
            a.await { _, _, _ ->
                if (remaining.decrementAndGet() == 0) allFuture.complete(null)
            }
        }

        allFuture.thenRun {
            resumeThread(coro, LuaValue.NONE, "async.allSettled callback")
        }
        throw YieldContinuationException(this@AsyncLib.allSettledContinuable, args, allFuture)
    }

    private val allSettledContinuable: LuaFunction by lazy {
        object : LuaContinuableFunction<CompletableFuture<Void>>() {
            override fun invoke(args: Varargs, continuation: CompletableFuture<Void>?): Varargs {
                if (continuation != null) {
                    val awaitables = collectAwaitables(args)
                    return buildAllResults(awaitables)
                }
                return handleAllSettled(args)
            }
        }
    }

    // ── sleep ──────────────────────────────────────────────────────────

    private fun handleSleep(args: Varargs): Varargs {
        val ticks = args.arg(1).checkint()
        require(ticks >= 0) { "async.sleep(ticks) requires non-negative ticks" }

        val co = luaState.currentThread
            ?: throw LuaError("async.sleep: must be called inside a coroutine")

        scheduler.schedule(ticks, luaVarFunctionNil { _ ->
            resumeThread(co, LuaValue.NIL, "async.sleep callback")
        })
        luaState.yield(LuaValue.NIL)
        return LuaValue.NIL
    }

    // ── fetch ──────────────────────────────────────────────────────────

    private fun handleFetch(args: Varargs): Varargs {
        require(args.narg() >= 1) { "async.fetch(url) or async.fetch({...}) requires 1 argument" }

        val request = LuaHttp.buildRequest(LuaHttp.parseRequest(args.arg(1)))
        val co = luaState.currentThread
            ?: throw LuaError("async.fetch: must be called inside a coroutine")

        LuaHttp.send(request)
            .thenAccept { response ->
                resumeThread(co, LuaHttp.buildResponse(response), "async.fetch callback")
            }
            .exceptionally { error ->
                resumeThread(co, LuaHttp.buildError(error), "async.fetch callback")
                null
            }

        luaState.yield(LuaValue.NIL)
        return LuaValue.NIL
    }

    // ── Mutex ──────────────────────────────────────────────────────────

    private class MutexOperation(
        val parentThread: LuaThread,
        val executor: AsyncExecutor,
        val function: LuaFunction,
        val args: Varargs,
    ) {
        var continuation: MutexContinuation? = null
    }

    private class MutexContinuation(
        val mutex: MutexState,
        val op: MutexOperation,
        var result: Varargs = LuaValue.NONE,
        var error: Throwable? = null,
    ) {
        private val _finished = java.util.concurrent.atomic.AtomicBoolean(false)
        val isFinished: Boolean get() = _finished.get()
        fun tryFinish(): Boolean = _finished.compareAndSet(false, true)
    }

    private class MutexState {
        private val lock = ReentrantLock()
        private var _owner: MutexOperation? = null
        private val _waiters = ArrayDeque<MutexOperation>()

        fun tryAcquire(op: MutexOperation): Boolean {
            lock.withLock {
                if (_owner == null) {
                    _owner = op
                    return true
                }
                _waiters.addLast(op)
                return false
            }
        }

        fun releaseAndResumeNext(completedOp: MutexOperation, onAcquire: (MutexOperation) -> Unit) {
            val nextOp: MutexOperation?
            lock.withLock {
                if (_owner !== completedOp) return
                _owner = null
                nextOp = if (_waiters.isNotEmpty()) {
                    val next = _waiters.removeFirst()
                    _owner = next
                    next
                } else {
                    null
                }
            }
            if (nextOp != null) {
                onAcquire(nextOp)
            }
        }
    }

    private class MutexObject(
        val state: MutexState,
    )

    private val MUTEX_METATABLE by lazy {
        luaTableOf().also { mt ->
            mt.set("__index", luaFunction { self, key ->
                val obj = self.asObject<MutexObject>()
                    ?: throw LuaError("expected mutex, got ${self.typename()}")
                when (key.optjstring(null)) {
                    "with" -> mutexWithFn
                    else -> mt.get(key)
                }
            })
        }
    }

    private fun wrapMutex(state: MutexState): LuaValue {
        return LuaValue.userdataOf(MutexObject(state), MUTEX_METATABLE)
    }

    private fun handleMutex(@Suppress("UNUSED_PARAMETER") args: Varargs): Varargs {
        return wrapMutex(MutexState())
    }

    private fun releaseMutex(mutex: MutexState, completedOp: MutexOperation) {
        mutex.releaseAndResumeNext(completedOp) { nextOp ->
            try {
                nextOp.executor.dispatch {
                    runMutexCallback(nextOp, mutex)
                }
            } catch (e: Throwable) {
                // The executor rejected the promotion; fail the promoted waiter
                // so its parent is resumed with an error instead of hanging.
                val continuation = nextOp.continuation
                    ?: return@releaseAndResumeNext
                finishMutexOperation(continuation, LuaValue.NONE, e)
            }
        }
    }

    private fun finishMutexOperation(continuation: MutexContinuation, result: Varargs, error: Throwable?) {
        if (!continuation.tryFinish()) return
        continuation.result = result
        continuation.error = error
        releaseMutex(continuation.mutex, continuation.op)
        resumeThread(continuation.op.parentThread, LuaValue.NONE, "mutex callback completion")
    }

    private fun resumeMutexChild(childThread: LuaThread, args: Varargs, continuation: MutexContinuation) {
        val previous = LuaState.current()
        LuaState.setCurrent(luaState)
        try {
            val result = ScriptWatchdog.guard { childThread.resume(args) }
            if (childThread.status == "dead") {
                if (result.arg1().toboolean()) {
                    finishMutexOperation(continuation, result.subargs(2), null)
                } else {
                    finishMutexOperation(
                        continuation,
                        LuaValue.NONE,
                        LuaError(result.arg(2).optjstring("mutex callback error"))
                    )
                }
            }
        } finally {
            LuaState.setCurrent(previous)
        }
    }

    private fun runMutexCallback(op: MutexOperation, mutex: MutexState): Varargs? {
        val continuation = op.continuation
            ?: error("mutex operation has no continuation")

        val childThread = LuaThread(luaState, op.function)
        childThread.executionContext = op.executor

        val resumer = SerializedResumer(
            { childThread.status == "dead" },
            op.executor,
            resume = { args -> resumeMutexChild(childThread, args, continuation) },
            onFailure = { e -> finishMutexOperation(continuation, LuaValue.NONE, e) },
        )
        childThread.resumeHandler = LuaThread.ResumeHandler { _, value ->
            resumer.requestResume(value)
        }

        resumer.requestResume(op.args)
        return null
    }

    private fun handleMutexWith(args: Varargs): Varargs {
        val mutexObj = args.arg(1).asObject<MutexObject>()
            ?: throw LuaError("mutex:with: expected mutex, got ${args.arg(1).typename()}")
        val mutex = mutexObj.state
        val fn = args.arg(2).asFunction()
            ?: throw LuaError("mutex:with: expected function, got ${args.arg(2).typename()}")
        val fnArgs = args.subargs(3)

        val coro = luaState.currentThread
            ?: throw LuaError("mutex:with: must be called inside a coroutine")
        val executor = coro.executionContext as? AsyncExecutor
            ?: throw LuaError("mutex:with: no executor context")

        val op = MutexOperation(coro, executor, fn, fnArgs)
        val continuation = MutexContinuation(mutex, op)
        op.continuation = continuation

        if (mutex.tryAcquire(op)) {
            runMutexCallback(op, mutex)
        }
        throw YieldContinuationException(mutexWithContinuable, args, continuation)
    }

    private val mutexWithFn: LuaFunction by lazy { luaVarFunction(::handleMutexWith) }

    private val mutexWithContinuable: LuaFunction by lazy {
        object : LuaContinuableFunction<MutexContinuation>() {
            override fun invoke(args: Varargs, continuation: MutexContinuation?): Varargs {
                if (continuation != null) {
                    if (continuation.error != null) {
                        throw continuation.error!!
                    }
                    return continuation.result
                }
                return handleMutexWith(args)
            }
        }
    }

    // ── Resume helper (always goes through the serialized handler) ──────

    internal fun resumeThread(thread: LuaThread, args: Varargs, context: String) {
        val handler = thread.resumeHandler
            ?: throw LuaError(
                "$context: coroutine has no resume handler; async operations require an async-capable coroutine"
            )
        handler.resume(thread, args)
    }

    // ── Module table ───────────────────────────────────────────────────

    fun buildModule(): LuaTable {
        if (isolation == null || luaState === isolation.mainState) LuaHttp.resetResponseMeta()
        val module = LuaTable()
        module.set("task", luaVarFunction(::handleTask))
        module.set("run", runContinuable)
        module.set("promise", luaVarFunction(::handlePromise))
        module.set("resolve", luaVarFunction(::handleResolve))
        module.set("error", luaVarFunction(::handleError))
        module.set("all", allContinuable)
        module.set("allSettled", allSettledContinuable)
        module.set("sleep", luaVarFunction(::handleSleep))
        module.set("fetch", luaVarFunction(::handleFetch))
        module.set("mutex", luaVarFunction(::handleMutex))
        return module
    }
}
