package ru.pyxiion.ignis.api

import com.google.gson.*
import java.util.concurrent.ConcurrentLinkedQueue
import org.luaj.vm2.*
import org.luaj.vm2.lib.LuaContinuableFunction
import ru.pyxiion.ignis.*
import ru.pyxiion.ignis.storage.LuaJson
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import ru.pyxiion.ignis.parallel.Materializer
import ru.pyxiion.ignis.parallel.ParallelPool
import ru.pyxiion.ignis.parallel.SharedChannel
import ru.pyxiion.ignis.parallel.SharedLib
import ru.pyxiion.ignis.parallel.Snapshotter
import ru.pyxiion.ignis.parallel.StdlibRegistry
import ru.pyxiion.ignis.parallel.checkGlobals
import ru.pyxiion.ignis.sandbox.Vfs
import java.time.Duration

/**
 * `async` module. Lua runs on the server thread only: [dispatch] is the way back onto it, and every callback
 * that arrives from elsewhere (an HTTP response, a parallel result) goes through it before touching a coroutine.
 * It never runs inline: work is queued and [drain] runs it from the server tick, so a callback cannot re-enter
 * the Lua code that registered it.
 */
class AsyncLib(
    private val stateProvider: () -> LuaState,
    private val scheduler: Scheduler,
    private val vfs: Vfs,
) {
    // The Lua state is rebuilt on every script reload and does not exist yet when LuaMcApi is constructed.
    private val luaState: LuaState get() = stateProvider()

    @Volatile
    private var generation = 0

    private val queue = ConcurrentLinkedQueue<Runnable>()

    /** Queues [work] for the server thread. Safe from any thread. */
    val dispatch: (Runnable) -> Unit = { queue.add(it) }

    /**
     * Runs the queued work on the server thread, in rounds: a round runs what was queued before it began, so work
     * never runs inside the code that queued it, and a chain of wake-ups (channel -> task -> waiter) still
     * finishes within one tick. [MAX_DRAIN_ROUNDS] keeps a task that re-queues itself forever from stalling
     * the server; the rest waits for the next tick.
     */
    fun drain() {
        repeat(MAX_DRAIN_ROUNDS) {
            val pending = queue.size
            if (pending == 0) return
            repeat(pending) {
                val work = queue.poll() ?: return
                try {
                    work.run()
                } catch (e: Throwable) {
                    PxIgnis.logger.error("async: ошибка в отложенной задаче: ${e.message}", e)
                }
            }
        }
    }

    /**
     * Script reload: operations still pending (fetch, tasks, waiters) must not resume their coroutines, and the
     * parallel workers are cancelled.
     */
    fun invalidate() {
        generation++
        queue.clear()
        pool?.close()
        pool = null
    }

    // ── Awaitable ──────────────────────────────────────────────────────

    class Awaitable {
        private var _state = STATE_PENDING
        private var _result: Varargs = LuaValue.NONE
        private var _error: Throwable? = null
        private val waiters = mutableListOf<(String, Varargs, Throwable?) -> Unit>()

        val state: String get() = _state
        val isDone: Boolean get() = _state != STATE_PENDING
        val error: Throwable? get() = _error

        fun resolve(result: Varargs = LuaValue.NONE): Boolean {
            if (_state != STATE_PENDING) return false
            _state = STATE_RESOLVED
            _result = result
            notifyWaiters()
            return true
        }

        fun reject(err: Throwable): Boolean {
            if (_state != STATE_PENDING) return false
            _state = STATE_REJECTED
            _error = err
            notifyWaiters()
            return true
        }

        fun get(): Varargs = when (_state) {
            STATE_RESOLVED -> _result
            STATE_REJECTED -> throw _error ?: LuaError("awaitable rejected")
            else -> throw LuaError("awaitable is still pending")
        }

        fun getOrNull(): Varargs? = if (_state == STATE_RESOLVED) _result else null

        /** Calls [callback] now if already settled, otherwise when it settles. */
        fun await(callback: (String, Varargs, Throwable?) -> Unit) {
            if (_state != STATE_PENDING) {
                callback(_state, _result, _error)
                return
            }
            waiters.add(callback)
        }

        private fun notifyWaiters() {
            val snapshot = waiters.toList()
            waiters.clear()
            for (w in snapshot) w(_state, _result, _error)
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
                    "resolve" -> resolveFn
                    "error" -> errorFn
                    else -> mt.get(key)
                }
            })
        }
    }

    // ── Waiting ────────────────────────────────────────────────────────
    // wait, run and all raise the awaited task's error in the waiting coroutine. A plain yield cannot do that
    // (the resume values are the call's results), so they are continuable: on resume the function is called
    // again with the awaitable and either returns or throws.

    private fun continuableOn(
        name: String,
        start: (Varargs) -> Awaitable,
        settle: (Awaitable, Varargs) -> Varargs,
    ): LuaFunction = object : LuaContinuableFunction<Awaitable>() {
        override fun invoke(args: Varargs, continuation: Awaitable?): Varargs {
            if (continuation != null) return settle(continuation, args)

            // The main thread has no coroutine to suspend: starting first would leave work running unobserved.
            val coro = luaState.currentThread?.takeUnless { it.isMainThread }
                ?: throw LuaError("$name: must be called inside a coroutine")
            val awaitable = start(args)
            if (awaitable.isDone) return settle(awaitable, args)

            val gen = generation
            awaitable.await { _, _, _ -> resumeThread(coro, LuaValue.NONE, "$name callback", gen) }
            throw YieldContinuationException(this, args, awaitable)
        }
    }

    private fun settleResult(awaitable: Awaitable, protect: Boolean): Varargs = when (awaitable.state) {
        Awaitable.STATE_RESOLVED ->
            if (protect) LuaValue.varargsOf(LuaValue.TRUE, awaitable.get()) else awaitable.get()

        else ->
            if (protect) LuaValue.varargsOf(LuaValue.FALSE, LuaValue.valueOf(awaitable.error?.message ?: "unknown error"))
            else throw awaitable.error ?: LuaError("task failed")
    }

    /** `obj:wait()` returns the result or raises the error; `obj:wait(true)` returns `ok, result|error` instead. */
    private val waitFn: LuaFunction by lazy {
        continuableOn(
            "wait",
            start = { extractAwaitable(it, op = "wait") },
            settle = { awaitable, args -> settleResult(awaitable, args.arg(2).toboolean()) },
        )
    }

    // ── Tasks ──────────────────────────────────────────────────────────

    private fun runTask(thread: LuaThread, args: Varargs, awaitable: Awaitable, gen: Int) {
        if (gen != generation || thread.status == "dead") return
        val previous = LuaState.current()
        LuaState.setCurrent(luaState)
        try {
            val result = thread.resume(args)
            if (thread.status == "dead") {
                if (result.arg1().toboolean()) {
                    awaitable.resolve(result.subargs(2))
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

    /** Starts [f] in its own coroutine on the next server task; every later resume is queued the same way. */
    private fun createTask(f: LuaFunction, taskArgs: Varargs): Awaitable {
        val awaitable = Awaitable()
        val gen = generation
        val thread = LuaThread(luaState, f)
        thread.resumeHandler = LuaThread.ResumeHandler { _, value ->
            dispatch { runTask(thread, value, awaitable, gen) }
        }
        dispatch { runTask(thread, taskArgs, awaitable, gen) }
        return awaitable
    }

    private fun taskFunction(args: Varargs, op: String): LuaFunction =
        args.arg(1).asFunction()
            ?: throw LuaError("$op: expected function, got ${args.arg(1).typename()}")

    /** `async.task(fn, ...)` */
    private fun handleTask(args: Varargs): Varargs =
        wrapObject(createTask(taskFunction(args, "async.task"), args.subargs(2)), "task")

    /** `async.run(fn, ...)`: a task that the calling coroutine waits for. */
    private val runFn: LuaFunction by lazy {
        continuableOn(
            "async.run",
            start = { createTask(taskFunction(it, "async.run"), it.subargs(2)) },
            settle = { awaitable, _ -> settleResult(awaitable, false) },
        )
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
        return awaitable.resolve(values).toLua()
    }

    private fun handleError(args: Varargs): Varargs {
        val awaitable = extractAwaitable(args, op = "error")
        val message = if (args.narg() >= 2) args.arg(2).tojstring() else "promise rejected"
        return awaitable.reject(LuaError(message)).toLua()
    }

    // ── all ────────────────────────────────────────────────────────────

    private fun collectAwaitables(args: Varargs): List<Awaitable> {
        val first = args.arg(1)
        return if (first.istable()) {
            val table = first.checktable()
            (1..table.length()).map { i ->
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

    /** `async.all(list [, {settled = true}])`: with `settled` a failure does not raise, it is in the entry. */
    private fun isSettledMode(args: Varargs): Boolean =
        args.arg(1).istable() && args.arg(2).istable() && args.arg(2).get("settled").toboolean()

    private fun combine(list: List<Awaitable>): Awaitable {
        val combined = Awaitable()
        if (list.isEmpty()) {
            combined.resolve()
            return combined
        }
        var remaining = list.size
        for (a in list) {
            a.await { _, _, _ -> if (--remaining == 0) combined.resolve() }
        }
        return combined
    }

    private val allFn: LuaFunction by lazy {
        continuableOn(
            "async.all",
            start = { combine(collectAwaitables(it)) },
            settle = { _, args ->
                val list = collectAwaitables(args)
                if (!isSettledMode(args)) {
                    val failed = list.firstOrNull { it.state == Awaitable.STATE_REJECTED }
                    if (failed != null) throw failed.error ?: LuaError("async.all: task failed")
                }
                buildAllResults(list)
            },
        )
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

    // ── sleep ──────────────────────────────────────────────────────────

    private fun handleSleep(args: Varargs): Varargs {
        val ticks = args.arg(1).checkint()
        if (ticks < 0) throw LuaError("async.sleep(ticks) requires non-negative ticks")

        val co = luaState.currentThread
            ?: throw LuaError("async.sleep: must be called inside a coroutine")
        val gen = generation

        scheduler.schedule(ticks, luaVarFunctionNil { _ ->
            resumeThread(co, LuaValue.NIL, "async.sleep callback", gen)
        })
        luaState.yield(LuaValue.NIL)
        return LuaValue.NIL
    }

    // ── fetch ──────────────────────────────────────────────────────────

    private fun handleFetch(args: Varargs): Varargs {
        if (args.narg() < 1) throw LuaError("async.fetch(url) or async.fetch({...}) requires 1 argument")

        val (url, method, headers, body, timeout) = parseRequest(args.arg(1))

        val builder = HttpRequest.newBuilder().uri(URI.create(url))
        headers.forEach { (k, v) -> builder.header(k, v) }

        val bodyPublisher = if (body != null) {
            HttpRequest.BodyPublishers.ofString(body)
        } else {
            HttpRequest.BodyPublishers.noBody()
        }

        when (method.uppercase()) {
            "GET" -> builder.GET()
            "DELETE" -> builder.DELETE()
            "HEAD" -> builder.method("HEAD", HttpRequest.BodyPublishers.noBody())
            else -> builder.method(method.uppercase(), bodyPublisher)
        }

        timeout?.let { builder.timeout(Duration.ofSeconds(it)) }

        val request = builder.build()
        val co = luaState.currentThread
            ?: throw LuaError("async.fetch: must be called inside a coroutine")
        val gen = generation

        // The completion runs on an HTTP client thread: the response table is built and the coroutine resumed
        // on the server thread, and not at all if the scripts were reloaded in the meantime.
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .whenComplete { response, error ->
                dispatch {
                    if (gen == generation) {
                        try {
                            val result = if (error != null) buildError(error) else buildResponse(response)
                            resumeThread(co, result, "async.fetch callback", gen)
                        } catch (e: Throwable) {
                            PxIgnis.logger.error("async.fetch: не удалось возобновить корутину: ${e.message}", e)
                        }
                    }
                }
            }

        luaState.yield(LuaValue.NIL)
        return LuaValue.NIL
    }

    // ── Resume helper ──────────────────────────────────────────────────

    /** Resumes [thread] through its own handler, unless the scripts were reloaded since [gen]. */
    internal fun resumeThread(thread: LuaThread, args: Varargs, context: String, gen: Int) {
        if (gen != generation) return
        val handler = thread.resumeHandler
            ?: throw LuaError(
                "$context: coroutine has no resume handler; async operations require an async-capable coroutine"
            )
        handler.resume(thread, args)
    }

    // ── Parallel ───────────────────────────────────────────────────────

    private var stdlib: StdlibRegistry? = null
    private var pool: ParallelPool? = null

    /** `async.parallel(fn)`: returns a function that runs `fn` on a pool thread and waits for the result. */
    private fun handleParallel(args: Varargs): Varargs {
        val fn = args.arg(1) as? LuaClosure
            ?: throw LuaError("async.parallel: expected a Lua function, got ${args.arg(1).typename()}")
        val registry = stdlib ?: throw LuaError("async.parallel: the async module is not initialised")

        // A trial copy: what cannot cross threads fails here, not at the first call.
        val globals = luaState.globals
        val trial = Snapshotter(globals, registry, allowFunctions = true, op = "async.parallel")
        trial.snapshot(fn, "")
        checkGlobals(trial.closures, globals, registry)

        return continuableOn(
            "parallel call",
            start = { submitParallel(fn, it) },
            settle = { awaitable, _ -> settleResult(awaitable, false) },
        )
    }

    /** Captured values and arguments are copied on every call, so a later change of a captured local is seen. */
    private fun submitParallel(fn: LuaClosure, callArgs: Varargs): Awaitable {
        val registry = stdlib ?: throw LuaError("async.parallel: the async module is not initialised")
        val snapshotter = Snapshotter(luaState.globals, registry, allowFunctions = true, op = "parallel call")
        val fnSnap = snapshotter.snapshot(fn, "")
        val argSnaps = Array(callArgs.narg()) { snapshotter.snapshot(callArgs.arg(it + 1), "argument #${it + 1}") }

        val awaitable = Awaitable()
        val gen = generation
        val workers = pool ?: ParallelPool(vfs, dispatch).also { pool = it }
        workers.submit(fnSnap, argSnaps) { result ->
            if (gen == generation && !awaitable.isDone) {
                try {
                    val snaps = result.getOrThrow()
                    val materializer = Materializer(luaState, sharedLib)
                    awaitable.resolve(LuaValue.varargsOf(Array(snaps.size) { materializer.materialize(snaps[it]) }))
                } catch (e: Throwable) {
                    awaitable.reject(if (e is LuaError) e else LuaError(e.message ?: e.toString()))
                }
            }
        }
        return awaitable
    }

    // ── Shared channels ────────────────────────────────────────────────
    // A channel is also used from workers (they block, see SharedLib.blockingSend). On the server thread the
    // coroutine yields until the channel has room / an item; on resume the call runs again and retries, so
    // losing a race to another party simply waits again.

    private fun yieldUntil(
        self: LuaFunction,
        args: Varargs,
        name: String,
        register: (callback: () -> Unit) -> Unit,
    ): Nothing {
        val coro = luaState.currentThread?.takeUnless { it.isMainThread }
            ?: throw LuaError("$name: must be called inside a coroutine")
        val gen = generation
        val wake = Awaitable()
        wake.await { _, _, _ -> resumeThread(coro, LuaValue.NONE, "$name callback", gen) }
        register { dispatch { wake.resolve() } }
        throw YieldContinuationException(self, args, wake)
    }

    private fun serverSend(): LuaFunction = object : LuaContinuableFunction<Awaitable>() {
        override fun invoke(args: Varargs, continuation: Awaitable?): Varargs {
            val (channel, value) = SharedLib.sendArgs(args)
            if (channel.trySend(value)) return LuaValue.NONE
            yieldUntil(this, args, "send") { channel.onSpace(it) }
        }
    }

    private fun serverReceive(lib: SharedLib): LuaFunction = object : LuaContinuableFunction<Awaitable>() {
        override fun invoke(args: Varargs, continuation: Awaitable?): Varargs {
            val channel = SharedLib.channelOf(args, "receive")
            return when (val item = channel.tryReceive()) {
                null -> yieldUntil(this, args, "receive") { channel.onItem(it) }
                SharedChannel.CLOSED -> LuaValue.NIL
                else -> lib.decode(item)
            }
        }
    }

    private val sharedLib = SharedLib({ serverSend() }, { serverReceive(it) })

    // ── Module table ───────────────────────────────────────────────────

    internal fun buildModule(stdlib: StdlibRegistry): LuaTable {
        this.stdlib = stdlib
        responseMetaReset()
        val module = LuaTable()
        module.set("task", luaVarFunction(::handleTask))
        module.set("run", runFn)
        module.set("promise", luaVarFunction(::handlePromise))
        module.set("resolve", luaVarFunction(::handleResolve))
        module.set("error", luaVarFunction(::handleError))
        module.set("all", allFn)
        module.set("sleep", luaVarFunction(::handleSleep))
        module.set("fetch", luaVarFunction(::handleFetch))
        module.set("parallel", luaVarFunction(::handleParallel))
        sharedLib.install(module)
        return module
    }

    // ── HTTP / JSON (companion) ────────────────────────────────────────

    private data class RequestConfig(
        val url: String,
        val method: String,
        val headers: Map<String, String>,
        val body: String?,
        val timeout: Long?
    )

    private fun parseRequest(arg: LuaValue): RequestConfig {
        if (arg.isstring()) {
            val url = arg.checkjstring()
            validateUrl(url)
            return RequestConfig(url, "GET", emptyMap(), null, DEFAULT_TIMEOUT)
        }

        val table = arg.checktable()
        val url = table.get("url").checkjstring()
        validateUrl(url)
        val method = table.get("method").optjstring("GET")

        val headers = mutableMapOf<String, String>()
        table.get("headers").opttable(null)?.forEach { k, v ->
            headers[k.checkjstring().lowercase()] = v.checkjstring()
        }

        val bodyVal = table.get("body")
        val jsonVal = table.get("json")
        val hasBody = !bodyVal.isnil()
        val hasJson = !jsonVal.isnil()

        if (hasBody && hasJson) throw LuaError("fetch: body and json are mutually exclusive")

        val body = when {
            hasBody -> bodyVal.checkjstring()
            hasJson -> {
                if (!headers.containsKey("content-type")) {
                    headers["content-type"] = "application/json"
                }
                luaToJsonString(jsonVal)
            }
            else -> null
        }

        val timeout = table.get("timeout").optlong(DEFAULT_TIMEOUT)
        if (timeout <= 0) throw LuaError("fetch: timeout must be a positive number of seconds, got $timeout")
        return RequestConfig(url, method, headers, body, timeout)
    }

    private fun buildResponse(response: HttpResponse<String>): LuaValue {
        val status = response.statusCode()
        val body = response.body()
        val t = LuaTable()
        t.setmetatable(RESPONSE_META)
        t.rawset("__body", LuaValue.valueOf(body))
        t.rawset("ok", LuaValue.valueOf(status in 200..299))
        t.rawset("status", LuaValue.valueOf(status))
        t.rawset("text", LuaValue.valueOf(body))
        t.rawset("headers", buildHeadersTable(response.headers().map()))
        return t
    }

    private fun buildError(error: Throwable): LuaValue {
        val t = LuaTable()
        t.setmetatable(RESPONSE_META)
        t.rawset("ok", LuaValue.FALSE)
        t.rawset("error", LuaValue.valueOf(error.message ?: "Unknown error"))
        return t
    }

    private fun buildHeadersTable(headers: Map<String, List<String>>): LuaTable {
        val t = LuaTable()
        for ((key, values) in headers) {
            if (values.isNotEmpty()) t.rawset(key, LuaValue.valueOf(values.first()))
        }
        return t
    }

    companion object {
        private const val DEFAULT_TIMEOUT = 10L

        /** Rounds of [drain] per tick. A wake-up chain is a few rounds long; this only bounds runaway loops. */
        private const val MAX_DRAIN_ROUNDS = 1024

        private fun validateUrl(url: String) {
            val uri = try {
                URI.create(url)
            } catch (e: IllegalArgumentException) {
                throw LuaError("fetch: invalid URL '$url': ${e.message}")
            }
            if (uri.scheme == null || uri.host == null) {
                throw LuaError("fetch: URL '$url' needs a scheme and host (http:// or https://)")
            }
        }

        private val httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(DEFAULT_TIMEOUT))
            .build()

        private val gson = Gson()

        var RESPONSE_META: LuaTable = LuaTable()
            private set

        private val responseKeys = listOf("ok", "status", "text", "headers", "json")

        fun responseMetaReset() {
            RESPONSE_META = LuaTable()
            metaInit(RESPONSE_META)
        }

        private fun metaInit(meta: LuaTable) {
            meta.rawset("__index", luaFunction { self, key ->
                val s = self.checktable()
                val k = key.checkjstring()
                if (k == "json") {
                    val cached = s.rawget("json")
                    if (!cached.isnil()) return@luaFunction cached
                    val body = s.rawget("__body")
                    if (body.isnil()) return@luaFunction LuaValue.NIL
                    val parsed = try {
                        jsonStringToLua(body.checkjstring())
                    } catch (e: JsonSyntaxException) {
                        throw LuaError("HTTP response body is not valid JSON: ${e.message}")
                    }
                    s.rawset("json", parsed)
                    parsed
                } else {
                    LuaValue.NIL
                }
            })

            meta.rawset("__pairs", luaVarFunction { args ->
                val self = args.arg(1)
                var i = 0
                val iterator = luaVarFunction { _: Varargs ->
                    if (i >= responseKeys.size) LuaValue.NIL as Varargs
                    else {
                        val key = responseKeys[i]; i++
                        LuaValue.varargsOf(LuaValue.valueOf(key), self.get(key))
                    }
                }
                LuaValue.varargsOf(iterator, self, LuaValue.NIL)
            })
        }

        private fun jsonStringToLua(json: String): LuaValue = LuaJson.fromJson(JsonParser.parseString(json))

        private fun luaToJsonString(value: LuaValue): String = gson.toJson(LuaJson.toJson(value, "json"))
    }
}
