package ru.pyxiion.ignis

import net.minecraft.server.MinecraftServer
import ru.pyxiion.ignis.api.LuaMcApi
import ru.pyxiion.ignis.api.manager.*
import ru.pyxiion.ignis.commands.CommandRegistrar
import ru.pyxiion.ignis.commands.LuaCommandManager
import ru.pyxiion.ignis.runtime.ScriptEnvironment
import ru.pyxiion.ignis.runtime.ScriptLoader
import ru.pyxiion.ignis.storage.StorageManager

class IgnisRuntime(
    private val server: MinecraftServer,
    private val storageManager: StorageManager,
) {
    val eventManager = EventBus("root", PxIgnis.logger)

    private val commandManager = LuaCommandManager(server)
    private val environment = ScriptEnvironment()
    val api: LuaMcApi = LuaMcApi(server, storageManager, { environment.luaState }, eventManager)
    val scheduler: Scheduler get() = api.scheduler
    private val commandRegistrar: CommandRegistrar = CommandRegistrar(commandManager, { environment.luaState })
    private val scriptLoader = ScriptLoader()

    fun reload() {
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

        for ((name, source) in scriptLoader.loadAll()) {
            state.load(source, name).call()
        }

        MobAIManager.scanAndReapply(server)
        eventManager.fire("init")

        commandManager.registerAll()
        PxIgnis.logger.info("PxIgnis зарегистрировал свои команды")
    }
}
