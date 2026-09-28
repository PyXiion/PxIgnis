package ru.pyxiion.ignis

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import net.minecraft.server.MinecraftServer
import me.lucko.fabric.api.permissions.v0.Permissions
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaState
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaThread
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Prototype
import org.luaj.vm2.compiler.LuaC
import ru.pyxiion.ignis.api.LuaMcApi
import ru.pyxiion.ignis.api.manager.*
import ru.pyxiion.ignis.commands.CommandRegistrar
import ru.pyxiion.ignis.commands.LuaCommandManager
import ru.pyxiion.ignis.api.wrapper.PlayerWrap
import ru.pyxiion.ignis.events.GlobalEvents
import ru.pyxiion.ignis.runtime.ReloadResult
import ru.pyxiion.ignis.runtime.ScriptErrors
import ru.pyxiion.ignis.runtime.ScriptEnvironment
import ru.pyxiion.ignis.runtime.ScriptLoader
import ru.pyxiion.ignis.runtime.ScriptWatchdog
import ru.pyxiion.ignis.runtime.ScriptWatcher
import ru.pyxiion.ignis.storage.StorageManager
import java.io.ByteArrayInputStream

class IgnisRuntime(
    private val server: MinecraftServer,
    private val storageManager: StorageManager,
) {
    private val commandManager = LuaCommandManager(server)
    private val environment = ScriptEnvironment()
    val modScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val eventManager = EventBus("", PxIgnis.logger, { environment.luaStateOrNull }, GlobalEvents.CATALOG)
    val api: LuaMcApi = LuaMcApi(server, storageManager, { environment.luaState }, eventManager, modScope)
    val scheduler: Scheduler get() = api.scheduler
    private val commandRegistrar: CommandRegistrar = CommandRegistrar(commandManager, { environment.luaState })
    private val scriptLoader = ScriptLoader()
    private val watcher = ScriptWatcher(scriptLoader.dir) { server.execute(::reloadFromWatcher) }

    /** Outcome of the last reload, for `/ignis status`. */
    var lastReload: ReloadResult? = null
        private set

    init {
        ScriptErrors.notifier = { msg ->
            val color = if (msg.severity == ScriptErrors.Severity.ERROR) Formatting.RED else Formatting.YELLOW
            notifyOperators(Text.literal(msg.context + ": ").formatted(Formatting.GRAY)
                .append(Text.literal(msg.text.take(400)).formatted(color)))
        }
        if (System.getProperty("pxignis.watch").toBoolean()) {
            runCatching { setWatching(true) }.onFailure { PxIgnis.logger.warn("Could not watch the scripts folder", it) }
        }
    }

    /**
     * Sends [text] to online players with the [PERM_NOTIFY] permission (operators by default).
     * Safe to call from any thread.
     */
    fun notifyOperators(text: Text) {
        val line = Text.literal("[Ignis] ").formatted(Formatting.GOLD).append(text)
        val send = {
            server.playerManager.playerList
                .filter { Permissions.check(it, PERM_NOTIFY, 4) }
                .forEach { it.sendMessage(line) }
        }
        if (server.isOnThread) send() else server.execute(send)
    }

    val watching: Boolean get() = watcher.running

    fun setWatching(enabled: Boolean) {
        if (enabled) watcher.start() else watcher.stop()
    }

    private fun reloadFromWatcher() {
        val result = reload()
        val color = if (result.ok) Formatting.GREEN else Formatting.RED
        notifyOperators(Text.literal("файлы изменились: ${result.summary()}").formatted(color))
    }

    fun shutdown() {
        watcher.stop()
        ScriptErrors.notifier = null
    }

    /**
     * Re-runs all scripts. Everything is compiled before the old state is torn down: if a script has a
     * syntax error, the reload is cancelled and the scripts that are already running stay in place (on the very
     * first load there is nothing to keep, so broken files are skipped instead). A script that fails at runtime
     * is reported and the remaining ones still load.
     */
    fun reload(): ReloadResult {
        val compiled = mutableListOf<Pair<String, Prototype>>()
        val compileErrors = mutableListOf<String>()
        for ((name, source) in scriptLoader.loadAll()) {
            try {
                compiled += name to LuaC.instance.compile(ByteArrayInputStream(source.toByteArray()), name)
            } catch (e: Exception) {
                compileErrors += "$name: ${e.message}"
            }
        }
        compileErrors.forEach { PxIgnis.logger.error("Ошибка компиляции скрипта {}", it) }
        if (compileErrors.isNotEmpty() && environment.luaStateOrNull != null) {
            return ReloadResult(total = compiled.size + compileErrors.size, errors = compileErrors, applied = false)
        }

        storageManager.saveAll()
        ScriptErrors.resetRateLimit()

        eventManager.fire("uninit")
        api.clearPlayerCache()
        commandManager.clear()
        eventManager.clear()
        scheduler.clear()
        ContainerManager.closeAll()
        SidebarManager.closeAll()
        MobAIManager.restoreAll()
        HologramManager.closeAll()
        RegionManager.closeAll()
        BossBarManager.closeAll()

        val state = environment.rebuild(api, commandRegistrar)

        val errors = compileErrors.toMutableList()
        for ((name, prototype) in compiled) {
            try {
                ScriptWatchdog.guard { state.loader.load(prototype, name, state.globals).call() }
            } catch (e: Exception) {
                errors += "$name: ${e.message}"
                // Not sent to chat: the reload summary already lists it.
                PxIgnis.logger.error("Ошибка при выполнении скрипта $name: ${e.message}", e)
            }
        }

        MobAIManager.scanAndReapply(server)
        eventManager.fire("init")

        commandManager.registerAll()
        PxIgnis.logger.info("PxIgnis зарегистрировал свои команды")
        val result = ReloadResult(compiled.size + compileErrors.size, errors, applied = true, files = compiled.map { it.first })
        lastReload = result
        return result
    }

    /**
     * Runs [code] (an expression or statements) in the scripts' state, with `me` bound to [player].
     * Globals it assigns stay in the scripts' state. Returns the results as text; script errors are thrown.
     */
    fun eval(code: String, player: ServerPlayerEntity?): String {
        val state = environment.luaStateOrNull ?: throw LuaError("scripts are not loaded")
        val env = LuaTable()
        env.setmetatable(LuaTable().apply {
            rawset("__index", state.globals)
            rawset("__newindex", state.globals)
        })
        if (player != null) env.rawset("me", PlayerWrap.wrap(player))
        val fn = try {
            state.load("return $code", "=eval", env)
        } catch (_: LuaError) {
            state.load(code, "=eval", env)
        }
        val thread = LuaThread(state, fn)
        val r = ScriptWatchdog.guard { thread.resume(LuaValue.NONE) }
        if (!r.arg1().toboolean()) throw thread.lastError ?: LuaError(r.arg(2).tojstring())
        if (thread.status != "dead") return "(продолжается асинхронно)"
        val results = r.subargs(2)
        if (results.narg() == 0) return "ok"
        return (1..results.narg()).joinToString(", ") { describe(state, results.arg(it)) }
    }

    private fun describe(state: LuaState, v: LuaValue): String {
        val toString = v.metatag(LuaValue.TOSTRING)
        return when {
            !toString.isnil() -> toString.call(v).tojstring()
            v.istable() && !v.rawget("__pxrp_type").isnil() -> "<${v.rawget("__pxrp_type").tojstring()}>"
            v.istable() -> state.globals.get("mc").get("dump").takeIf { it.isfunction() }?.call(v, LuaValue.valueOf(2))?.tojstring()
                ?: v.tojstring()
            v.isstring() -> "\"${v.tojstring()}\""
            else -> v.tojstring()
        }
    }

    /** Human-readable state for `/ignis status`. */
    fun status(): List<String> {
        val lines = mutableListOf<String>()
        val r = lastReload
        lines += if (r == null) "Скрипты не загружены" else
            "Скрипты (${r.files.size}): ${r.files.joinToString().ifEmpty { "-" }}" +
                if (r.errors.isEmpty()) "" else ", ошибок: ${r.errors.size}"
        val handlers = eventManager.stats()
        lines += "Обработчики событий: " +
            if (handlers.isEmpty()) "-" else handlers.entries.joinToString { (k, v) -> if (v == 1) k else "$k ×$v" }
        lines += "Команды: " + commandManager.commandNames.joinToString { "/$it" }.ifEmpty { "-" }
        lines += "Задачи планировщика: ${scheduler.pendingCount()}, регионов: ${RegionManager.count}"
        lines += "Автоперезагрузка: ${if (watching) "вкл" else "выкл"}, " +
            "лимит времени скрипта: ${ScriptWatchdog.timeoutMillis.takeIf { it > 0 }?.let { "$it мс" } ?: "нет"}"
        return lines
    }

    companion object {
        /** Who gets script errors, warnings and auto-reload results in chat. */
        const val PERM_NOTIFY = "px.ignis.prompt_errors"
    }
}
