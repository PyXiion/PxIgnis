package ru.pyxiion.ignis

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.server.MinecraftServer
import me.lucko.fabric.api.permissions.v0.Permissions
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import ru.pyxiion.luanova.vm2.LuaError
import ru.pyxiion.luanova.vm2.LuaState
import ru.pyxiion.luanova.vm2.LuaTable
import ru.pyxiion.luanova.vm2.LuaThread
import ru.pyxiion.luanova.vm2.LuaValue
import ru.pyxiion.luanova.vm2.Prototype
import ru.pyxiion.luanova.vm2.compiler.LuaC
import ru.pyxiion.ignis.api.LuaMcApi
import ru.pyxiion.ignis.api.manager.*
import ru.pyxiion.ignis.commands.CommandRegistrar
import ru.pyxiion.ignis.commands.LuaCommandManager
import ru.pyxiion.ignis.api.wrapper.PlayerWrap
import ru.pyxiion.ignis.events.GlobalEvents
import ru.pyxiion.ignis.runtime.EditorSupport
import ru.pyxiion.ignis.runtime.ReloadResult
import ru.pyxiion.ignis.runtime.ScriptErrors
import ru.pyxiion.ignis.runtime.ScriptEnvironment
import ru.pyxiion.ignis.runtime.ScriptLoader
import ru.pyxiion.ignis.runtime.ScriptRoots
import ru.pyxiion.ignis.runtime.ScriptWatchdog
import ru.pyxiion.ignis.runtime.ScriptWatcher
import ru.pyxiion.ignis.packages.PackageDiscovery
import ru.pyxiion.ignis.packages.PackageLoader
import ru.pyxiion.ignis.plugin.PluginManager
import ru.pyxiion.ignis.preload.Preload
import ru.pyxiion.ignis.sandbox.Vfs
import ru.pyxiion.ignis.storage.JsonBackend
import ru.pyxiion.ignis.storage.StorageManager
import java.io.ByteArrayInputStream
import java.nio.file.Path

class IgnisRuntime(private val server: MinecraftServer) {
    /** Registries of the running server (data component codecs need them). */
    val registries: net.minecraft.core.HolderLookup.Provider get() = server.registryAccess()

    private val configDir = FabricLoader.getInstance().configDir
    private val gameDir = FabricLoader.getInstance().gameDir

    /** Folders scripts load from, in order; `script_roots` in `config/pxignis.json`, read once per server start. */
    private val scriptRoots: List<Path> = ScriptRoots.configured(gameDir, configDir)

    val storage = StorageManager(JsonBackend(configDir.resolve("ignis/storage")))
    private val commandManager = LuaCommandManager(server)
    private val vfs = Vfs(scriptRoots)
    private val environment = ScriptEnvironment(vfs)
    val modScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val eventManager = EventBus("", GlobalEvents.CATALOG, { environment.luaStateOrNull })
    val containers = ContainerManager()
    val api: LuaMcApi = LuaMcApi(server, storage, vfs, containers, { environment.luaState }, eventManager, modScope)
    val scheduler: Scheduler get() = api.scheduler
    private val commandRegistrar: CommandRegistrar = CommandRegistrar(commandManager, { environment.luaState })
    val plugins = PluginManager(server, modScope, scriptRoots, eventManager, api.asyncLib.dispatch, PluginManager.discover())
    private val scriptLoader = ScriptLoader(scriptRoots)
    private val watcher = ScriptWatcher(scriptRoots) { server.execute(::reloadFromWatcher) }

    /** Outcome of the last reload, for `/ignis status`. */
    var lastReload: ReloadResult? = null
        private set

    init {
        ScriptErrors.notifier = { msg ->
            val color = if (msg.severity == ScriptErrors.Severity.ERROR) ChatFormatting.RED else ChatFormatting.YELLOW
            notifyOperators(Component.literal(msg.context + ": ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(msg.text.take(400)).withStyle(color)))
        }
        if (System.getProperty("pxignis.watch").toBoolean()) {
            runCatching { setWatching(true) }.onFailure { PxIgnis.logger.warn("Could not watch the scripts folder", it) }
        }
        EditorSupport.installPluginTypes(configDir.resolve("ignis"), plugins.typeStubs)
        plugins.onServerStart()
    }

    /**
     * Sends [text] to online players with the [PERM_NOTIFY] permission (operators by default).
     * Safe to call from any thread.
     */
    fun notifyOperators(text: Component) {
        val line = Component.literal("[Ignis] ").withStyle(ChatFormatting.GOLD).append(text)
        val send = {
            server.playerList.players
                .filter { Permissions.check(it, PERM_NOTIFY, SCRIPT_PERMISSION_LEVEL) }
                .forEach { it.sendSystemMessage(line) }
        }
        if (server.isSameThread) send() else server.execute(send)
    }

    val watching: Boolean get() = watcher.running

    fun setWatching(enabled: Boolean) {
        if (enabled) watcher.start() else watcher.stop()
    }

    private fun reloadFromWatcher() {
        val result = reload()
        val color = if (result.ok) ChatFormatting.GREEN else ChatFormatting.RED
        notifyOperators(Component.literal("файлы изменились: ${result.summary()}").withStyle(color))
    }

    /**
     * Re-runs all scripts. Everything is compiled before the old state is torn down: if a script has a
     * syntax error, the reload is cancelled and the scripts that are already running stay in place (on the very
     * first load there is nothing to keep, so broken files are skipped instead). A script that fails at runtime
     * is reported and the remaining ones still load.
     */
    fun reload(): ReloadResult {
        Preload.warnIfChanged()
        val discovered = PackageDiscovery.scan(scriptRoots, plugins.versions)
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

        storage.saveAll()
        ScriptErrors.resetRateLimit()

        eventManager.fire("uninit")
        plugins.closeEnvironment()
        discovered.problems.forEach { ScriptErrors.warn("packages", it) }
        commandManager.clear()
        eventManager.clear()
        scheduler.clear()
        api.asyncLib.invalidate()
        containers.closeAll()
        SidebarManager.closeAll()
        MobAIManager.restoreAll()
        HologramManager.closeAll()
        DisplayManager.closeAll()
        RegionManager.closeAll()
        BossBarManager.closeAll()

        val state = environment.rebuild(api, commandRegistrar, plugins, discovered.plan)

        val errors = compileErrors.toMutableList()
        val packagesRun = PackageLoader.runAll(state, discovered.plan)
        errors += packagesRun.errors
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
        val result = ReloadResult(
            compiled.size + compileErrors.size + packagesRun.total, errors, applied = true,
            files = packagesRun.loaded.map { "pkg:$it" } + compiled.map { it.first },
        )
        lastReload = result
        return result
    }

    /**
     * Runs [code] (an expression or statements) in the scripts' state, with `me` bound to [player].
     * Globals it assigns stay in the scripts' state. Returns the results as text; script errors are thrown.
     */
    fun eval(code: String, player: ServerPlayer?): String {
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

    /** Остановка сервера: каждый шаг независим, сбой одного не мешает остальным. */
    fun shutdown() {
        step("watcher.stop") { watcher.stop() }
        step("notifier") { ScriptErrors.notifier = null }
        step("modScope.cancel") { modScope.cancel() }
        step("scheduler.clear") { scheduler.clear() }
        step("uninit") { eventManager.fire("uninit") }
        step("server_stop") { eventManager.fire("server_stop") }
        step("plugins.close") { plugins.closeEnvironment() }
        step("plugins.stop") { plugins.onServerStop() }
        step("async.invalidate") { api.asyncLib.invalidate() }
        step("holograms.closeAll") { HologramManager.closeAll() }
        step("displays.closeAll") { DisplayManager.closeAll() }
        step("storage.close") { storage.close() }
    }

    private inline fun step(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            PxIgnis.logger.error("Ошибка при остановке PxIgnis ($name)", e)
        }
    }
}
