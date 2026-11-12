package ru.pyxiion.ignis

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.server.MinecraftServer
import ru.pyxiion.ignis.api.LuaMcApi
import ru.pyxiion.ignis.api.manager.*
import ru.pyxiion.ignis.commands.CommandRegistrar
import ru.pyxiion.ignis.commands.LuaCommandManager
import ru.pyxiion.ignis.runtime.ScriptEnvironment
import ru.pyxiion.ignis.runtime.ScriptLoader
import ru.pyxiion.ignis.sandbox.Vfs
import ru.pyxiion.ignis.storage.JsonBackend
import ru.pyxiion.ignis.storage.StorageManager

class IgnisRuntime(private val server: MinecraftServer) {
    val storage = StorageManager(JsonBackend(FabricLoader.getInstance().configDir.resolve("ignis/storage")))
    private val commandManager = LuaCommandManager(server)
    private val vfs = Vfs(FabricLoader.getInstance().configDir.resolve("ignis").toAbsolutePath())
    private val environment = ScriptEnvironment(vfs)
    val modScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val eventManager = EventBus("root", PxIgnis.logger, { environment.luaStateOrNull })
    val containers = ContainerManager()
    val api: LuaMcApi = LuaMcApi(server, storage, vfs, containers, { environment.luaState }, eventManager, modScope)
    val scheduler: Scheduler get() = api.scheduler
    private val commandRegistrar: CommandRegistrar = CommandRegistrar(commandManager, { environment.luaState })
    private val scriptLoader = ScriptLoader()

    fun reload() {
        storage.saveAll()

        eventManager.fire("uninit")
        commandManager.clear()
        eventManager.clear()
        scheduler.clear()
        api.asyncLib.invalidate()
        containers.closeAll()
        SidebarManager.closeAll()
        MobAIManager.restoreAll()
        HologramManager.closeAll()
        RegionManager.closeAll()
        BossBarManager.closeAll()

        val state = environment.rebuild(api, commandRegistrar)

        for ((name, source) in scriptLoader.loadAll()) {
            state.load(source, name).call()
        }

        MobAIManager.scanAndReapply(server)
        eventManager.fire("init")

        commandManager.registerAll()
        PxIgnis.logger.info("PxIgnis зарегистрировал свои команды")
    }

    /** Остановка сервера: каждый шаг независим, сбой одного не мешает остальным. */
    fun shutdown() {
        step("modScope.cancel") { modScope.cancel() }
        step("scheduler.clear") { scheduler.clear() }
        step("uninit") { eventManager.fire("uninit") }
        step("server_stop") { eventManager.fire("server_stop") }
        step("async.invalidate") { api.asyncLib.invalidate() }
        step("holograms.closeAll") { HologramManager.closeAll() }
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
