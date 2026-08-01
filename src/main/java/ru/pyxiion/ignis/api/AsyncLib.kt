package ru.pyxiion.ignis.api

import com.google.gson.*
import org.luaj.vm2.*
import org.luaj.vm2.lib.LuaContinuableFunction
import ru.pyxiion.ignis.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class AsyncLib(
    private val executor: Executor,
    private val luaState: LuaState,
    private val scheduler: Scheduler
) {

    // ── Awaitable ──────────────────────────────────────────────────────

    class Awaitable {
        private val lock = ReentrantLock()
        private var _state = STATE_PENDING
        private var _result: Varargs = LuaValue.NONE
        private var _error: Throwable? = null
        private val waiters = mutableListOf<(String, Varargs, Throwable?) -> Unit>()

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
                STATE_RESOLVED -> _result
                STATE_REJECTED -> throw _error ?: LuaError("awaitable rejected")
                else -> throw LuaError("awaitable is still pending")
            }
        }

        fun getOrNull(): Varargs? = lock.withLock {
            when (_state) {
                STATE_RESOLVED -> _result
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
                    executor.execute { coro.resumeOrLog(LuaValue.NONE, "async.wait callback") }
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

                awaitable.await { state, _, error ->
                    executor.execute {
                        val result = if (state == Awaitable.STATE_RESOLVED) {
                            LuaValue.varargsOf(LuaValue.TRUE, awaitable.getOrNull() ?: LuaValue.NONE)
                        } else {
                            LuaValue.varargsOf(
                                LuaValue.FALSE,
                                LuaValue.valueOf(error?.message ?: "unknown error")
                            )
                        }
                        coro.resumeOrLog(result, "async.try callback")
                    }
                }
                throw YieldContinuationException(this, args, awaitable)
            }
        }
    }

    // ── Task ───────────────────────────────────────────────────────────

    private fun handleTask(args: Varargs): Varargs {
        val f = args.arg(1).asFunction()
            ?: throw LuaError("async.task: expected function, got ${args.arg(1).typename()}")
        val taskArgs = args.subargs(2)
        val awaitable = Awaitable()

        executor.execute {
            LuaState.setCurrent(luaState)
            try {
                val thread = LuaThread(luaState, f)

                thread.resumeHandler = LuaThread.ResumeHandler { t, value ->
                    val result = t.resume(value)
                    if (t.status == "dead") {
                        if (result.arg1().toboolean()) {
                            awaitable.resolve(result.subargs(2))
                        } else {
                            awaitable.reject(
                                LuaError(result.arg(2).optjstring("task error"))
                            )
                        }
                    }
                    result
                }

                val result = thread.resume(taskArgs)
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
                LuaState.setCurrent(null)
            }
        }

        return wrapObject(awaitable, "task")
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
            executor.execute { coro.resumeOrLog(LuaValue.NONE, "async.all callback") }
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
            executor.execute { coro.resumeOrLog(LuaValue.NONE, "async.allSettled callback") }
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

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenAccept { response ->
                resumeThread(co, buildResponse(response), "async.fetch callback")
                null
            }
            .exceptionally { error ->
                resumeThread(co, buildError(error), "async.fetch callback")
                null
            }

        luaState.yield(LuaValue.NIL)
        return LuaValue.NIL
    }

    // ── Resume helper (goes through handler if present) ─────────────────

    private fun resumeThread(thread: LuaThread, args: Varargs, context: String) {
        val handler = thread.resumeHandler
        if (handler != null) {
            handler.resume(thread, args)
        } else {
            thread.resumeOrLog(args, context)
        }
    }

    // ── Module table ───────────────────────────────────────────────────

    fun buildModule(): LuaTable {
        responseMetaReset()
        val module = LuaTable()
        module.set("task", luaVarFunction(::handleTask))
        module.set("promise", luaVarFunction(::handlePromise))
        module.set("resolve", luaVarFunction(::handleResolve))
        module.set("error", luaVarFunction(::handleError))
        module.set("all", allContinuable)
        module.set("allSettled", allSettledContinuable)
        module.set("sleep", luaVarFunction(::handleSleep))
        module.set("fetch", luaVarFunction(::handleFetch))
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

        private fun validateUrl(url: String) {
            try {
                URI.create(url)
            } catch (e: IllegalArgumentException) {
                throw LuaError("fetch: invalid URL '$url': ${e.message}")
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

        private fun jsonStringToLua(json: String): LuaValue {
            val element = gson.fromJson(json, JsonElement::class.java)
            return jsonToLua(element)
        }

        private fun jsonToLua(element: JsonElement): LuaValue {
            return when {
                element.isJsonNull -> LuaValue.NIL
                element.isJsonPrimitive -> {
                    val p = element.asJsonPrimitive
                    when {
                        p.isBoolean -> LuaValue.valueOf(p.asBoolean)
                        p.isNumber -> LuaValue.valueOf(p.asDouble)
                        p.isString -> LuaValue.valueOf(p.asString)
                        else -> LuaValue.NIL
                    }
                }
                element.isJsonArray -> element.asJsonArray.map(::jsonToLua).toLuaArray()
                element.isJsonObject -> {
                    val obj = element.asJsonObject
                    val t = LuaTable()
                    for (key in obj.keySet()) t.set(key, jsonToLua(obj.get(key)))
                    t
                }
                else -> LuaValue.NIL
            }
        }

        private fun luaToJsonString(value: LuaValue): String {
            return gson.toJson(luaToJsonElement(value))
        }

        private fun luaToJsonElement(value: LuaValue): JsonElement {
            return when {
                value.isnil() -> JsonNull.INSTANCE
                value.isboolean() -> JsonPrimitive(value.toboolean())
                value.isint() -> JsonPrimitive(value.toint())
                value.islong() -> JsonPrimitive(value.tolong())
                value.isnumber() -> JsonPrimitive(value.todouble())
                value.isstring() -> JsonPrimitive(value.tojstring())
                value.istable() -> tableToJson(value.checktable())
                else -> JsonNull.INSTANCE
            }
        }

        private fun tableToJson(table: LuaTable): JsonElement {
            var isSequence = true
            val keys = mutableSetOf<Int>()
            val len = table.length().toInt()

            table.forEach { k, v ->
                if (k.isint() && k.toint() >= 1) keys.add(k.toint())
                else isSequence = false
            }

            if (isSequence && len > 0) {
                isSequence = keys.size == len && keys.all { it in 1..len }
            }

            return if (isSequence) {
                val arr = JsonArray()
                for (i in 1..len) arr.add(luaToJsonElement(table.get(i)))
                arr
            } else {
                val obj = JsonObject()
                table.forEach { k, v ->
                    if (k.isstring()) obj.add(k.checkjstring(), luaToJsonElement(v))
                }
                obj
            }
        }
    }
}
