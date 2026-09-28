package ru.pyxiion.ignis.api

import com.google.gson.JsonSyntaxException
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import ru.pyxiion.ignis.api.util.LuaJson
import ru.pyxiion.ignis.forEach
import ru.pyxiion.ignis.luaFunction
import ru.pyxiion.ignis.luaVarFunction
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * HTTP side of `async.fetch`: turns the Lua argument into an [HttpRequest] and the result into a
 * Lua response table. Yielding/resuming the coroutine stays in [AsyncLib].
 */
internal object LuaHttp {
    private const val DEFAULT_TIMEOUT = 10L

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(DEFAULT_TIMEOUT))
        .build()

    private val responseKeys = listOf("ok", "status", "text", "headers", "json")

    var RESPONSE_META: LuaTable = LuaTable()
        private set

    fun resetResponseMeta() {
        RESPONSE_META = LuaTable()
        initResponseMeta(RESPONSE_META)
    }

    fun send(request: HttpRequest): CompletableFuture<HttpResponse<String>> =
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())

    // ── Request ────────────────────────────────────────────────────────

    data class RequestConfig(
        val url: String,
        val method: String,
        val headers: Map<String, String>,
        val body: String?,
        val timeout: Long?,
    )

    /** Accepts either a URL string or a `{ url, method, headers, body | json, timeout }` table. */
    fun parseRequest(arg: LuaValue): RequestConfig {
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
                LuaJson.encode(jsonVal)
            }
            else -> null
        }

        val timeout = table.get("timeout").optlong(DEFAULT_TIMEOUT)
        return RequestConfig(url, method, headers, body, timeout)
    }

    fun buildRequest(config: RequestConfig): HttpRequest {
        val builder = HttpRequest.newBuilder().uri(URI.create(config.url))
        config.headers.forEach { (k, v) -> builder.header(k, v) }

        val bodyPublisher = config.body
            ?.let { HttpRequest.BodyPublishers.ofString(it) }
            ?: HttpRequest.BodyPublishers.noBody()

        when (val method = config.method.uppercase()) {
            "GET" -> builder.GET()
            "DELETE" -> builder.DELETE()
            "HEAD" -> builder.method("HEAD", HttpRequest.BodyPublishers.noBody())
            else -> builder.method(method, bodyPublisher)
        }

        config.timeout?.let { builder.timeout(Duration.ofSeconds(it)) }
        return builder.build()
    }

    private fun validateUrl(url: String) {
        try {
            URI.create(url)
        } catch (e: IllegalArgumentException) {
            throw LuaError("fetch: invalid URL '$url': ${e.message}")
        }
    }

    // ── Response ───────────────────────────────────────────────────────

    fun buildResponse(response: HttpResponse<String>): LuaValue =
        buildResponse(response.statusCode(), response.body(), response.headers().map())

    fun buildResponse(status: Int, body: String, headers: Map<String, List<String>>): LuaValue {
        val t = LuaTable()
        t.setmetatable(RESPONSE_META)
        t.rawset("__body", LuaValue.valueOf(body))
        t.rawset("ok", LuaValue.valueOf(status in 200..299))
        t.rawset("status", LuaValue.valueOf(status))
        t.rawset("text", LuaValue.valueOf(body))
        t.rawset("headers", buildHeadersTable(headers))
        return t
    }

    fun buildError(error: Throwable): LuaValue {
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

    private fun initResponseMeta(meta: LuaTable) {
        // `json` is parsed lazily on first access and cached on the response table.
        meta.rawset("__index", luaFunction { self, key ->
            val s = self.checktable()
            val k = key.checkjstring()
            if (k == "json") {
                val cached = s.rawget("json")
                if (!cached.isnil()) return@luaFunction cached
                val body = s.rawget("__body")
                if (body.isnil()) return@luaFunction LuaValue.NIL
                val parsed = try {
                    LuaJson.decode(body.checkjstring())
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
}
