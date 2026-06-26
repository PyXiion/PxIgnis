package ru.pyxiion.ignis.api

import com.google.gson.*
import net.minecraft.server.MinecraftServer
import org.luaj.vm2.*
import ru.pyxiion.ignis.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class AsyncLib(
    private val server: MinecraftServer,
    private val luaState: LuaState,
    private val scheduler: Scheduler
) {
    fun install(mcTable: LuaTable) {
        responseMetaReset()
        mcTable.set("fetch", luaVarFunction(::handleFetch))
        mcTable.set("sleep", luaVarFunction(::handleSleep))
    }

    private fun handleSleep(args: Varargs): Varargs {
        val ticks = args.arg(1).checkint()
        require(ticks >= 0) { "sleep(ticks) requires non-negative ticks" }

        val co = luaState.currentThread
        scheduler.schedule(ticks, luaVarFunctionNil { _ ->
            co.resumeOrLog(LuaValue.NIL, "mc.sleep callback")
        })
        luaState.yield(LuaValue.NIL)
        return LuaValue.NIL
    }

    private fun handleFetch(args: Varargs): Varargs {
        require(args.narg() >= 1) { "fetch(url) or fetch({...}) requires 1 argument" }

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

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenAccept { response ->
                server.execute { co.resumeOrLog(buildResponse(response), "mc.fetch callback") }
                null
            }
            .exceptionally { error ->
                server.execute { co.resumeOrLog(buildError(error), "mc.fetch callback") }
                null
            }

        luaState.yield(LuaValue.NIL)
        return LuaValue.NIL
    }

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
            return RequestConfig(
                url = url,
                method = "GET",
                headers = emptyMap(),
                body = null,
                timeout = DEFAULT_TIMEOUT
            )
        }

        val table = arg.checktable()
        val url = table.get("url").checkjstring()
        validateUrl(url)
        val method = table.get("method").optjstring("GET")

        // All headers are lower cased
        val headers = mutableMapOf<String, String>()
        table.get("headers").opttable(null)?.forEach { k, v ->
            headers[k.checkjstring().lowercase()] = v.checkjstring()
        }

        val bodyVal = table.get("body")
        val jsonVal = table.get("json")
        val hasBody = !bodyVal.isnil()
        val hasJson = !jsonVal.isnil()

        if (hasBody && hasJson) {
            throw LuaError("fetch: body and json are mutually exclusive")
        }

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
            if (values.isNotEmpty()) {
                t.rawset(key, LuaValue.valueOf(values.first()))
            }
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

                element.isJsonArray -> {
                    element.asJsonArray.map(::jsonToLua).toLuaArray()
                }

                element.isJsonObject -> {
                    val obj = element.asJsonObject
                    val t = LuaTable()
                    for (key in obj.keySet()) {
                        t.set(key, jsonToLua(obj.get(key)))
                    }
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
                if (k.isint() && k.toint() >= 1) {
                    keys.add(k.toint())
                } else {
                    isSequence = false
                }
            }

            if (isSequence && len > 0) {
                isSequence = keys.size == len && keys.all { it in 1..len }
            }

            return if (isSequence) {
                val arr = JsonArray()
                for (i in 1..len) {
                    arr.add(luaToJsonElement(table.get(i)))
                }
                arr
            } else {
                val obj = JsonObject()
                table.forEach { k, v ->
                    if (k.isstring()) {
                        obj.add(k.checkjstring(), luaToJsonElement(v))
                    }
                }
                obj
            }
        }
    }
}
