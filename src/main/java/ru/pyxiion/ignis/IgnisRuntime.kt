package ru.pyxiion.ignis

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import net.minecraft.server.MinecraftServer
import org.luaj.vm2.Prototype
import org.luaj.vm2.compiler.LuaC
import ru.pyxiion.ignis.api.LuaMcApi
import ru.pyxiion.ignis.api.manager.*
import ru.pyxiion.ignis.commands.CommandRegistrar
import ru.pyxiion.ignis.commands.LuaCommandManager
import ru.pyxiion.ignis.runtime.ReloadResult
import ru.pyxiion.ignis.runtime.ScriptEnvironment
import ru.pyxiion.ignis.runtime.ScriptLoader
import ru.pyxiion.ignis.runtime.ScriptWatchdog
import ru.pyxiion.ignis.storage.StorageManager
import java.io.ByteArrayInputStream

class IgnisRuntime(
    private val server: MinecraftServer,
    private val storageManager: StorageManager,
) {
    private val commandManager = LuaCommandManager(server)
    private val environment = ScriptEnvironment()
    val modScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val eventManager = EventBus("root", PxIgnis.logger, { environment.luaStateOrNull })
    val api: LuaMcApi = LuaMcApi(server, storageManager, { environment.luaState }, eventManager, modScope)
    val scheduler: Scheduler get() = api.scheduler
    private val commandRegistrar: CommandRegistrar = CommandRegistrar(commandManager, { environment.luaState })
    private val scriptLoader = ScriptLoader()

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
                PxIgnis.logger.error("Ошибка при выполнении скрипта $name: ${e.message}", e)
            }
        }

        MobAIManager.scanAndReapply(server)
        eventManager.fire("init")

        commandManager.registerAll()
        PxIgnis.logger.info("PxIgnis зарегистрировал свои команды")
        return ReloadResult(total = compiled.size + compileErrors.size, errors = errors, applied = true)
    }
}
