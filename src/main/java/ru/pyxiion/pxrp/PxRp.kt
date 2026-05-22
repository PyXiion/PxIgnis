package ru.pyxiion.pxrp

import me.lucko.fabric.api.permissions.v0.Permissions
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.command.permission.PermissionLevel
import net.minecraft.server.command.CommandManager
import net.minecraft.text.Text
import org.luaj.vm2.LuaError
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import ru.pyxiion.pxrp.storage.JsonBackend
import ru.pyxiion.pxrp.storage.StorageManager

class PxRp : ModInitializer {
    companion object {
        val logger: Logger = LoggerFactory.getLogger("PxRP")
        lateinit var instance: PxRp
        var storageManager: StorageManager? = null
    }

    lateinit var luaLoader: LuaCmdLoader


    override fun onInitialize() {
        instance = this
        ServerLifecycleEvents.SERVER_STARTED.register(fun(server) {
            try {
                val storagePath = FabricLoader.getInstance().configDir.resolve("pxrp/storage")
                storageManager = StorageManager(JsonBackend(storagePath))
                luaLoader = LuaCmdLoader(server, storageManager!!)
                luaLoader.reload()
            } catch (e: Throwable) {
                logger.error("Ошибка при запуске PxRP: ${e.message}", e)
            }
        })

        ServerLifecycleEvents.SERVER_STOPPING.register(fun(server) {
            storageManager?.close()
        })

        ServerPlayConnectionEvents.DISCONNECT.register(fun(handler, server) {
            storageManager?.removePlayerData(handler.player.uuid.toString())
        })

        CommandRegistrationCallback.EVENT.register(fun(dispatcher, reg, env) {
            dispatcher.register(
                CommandManager.literal("pxrp")
                    .requires(Permissions.require("pyxiion.pxrp", PermissionLevel.ADMINS))
                    .then(
                        CommandManager.literal("reload").executes { ctx ->
                            try {
                                luaLoader.reload()
                                ctx.source.sendFeedback({
                                    Text.literal("Перезагрузилось")
                                }, false)
                                return@executes 1
                            } catch (e: LuaError) {
                                logger.error("Ошибка при перезагрузке PxRP: ${e.message}")
                                ctx.source.sendFeedback({
                                    Text.literal("Ошибка при перезагрузке PxRP: ${e.message}")
                                }, false)
                            } catch (e: Throwable) {
                                logger.error("Ошибка при перезагрузке PxRP: ${e.message}", e)
                                ctx.source.sendFeedback({
                                    Text.literal("Ошибка при перезагрузке PxRP: ${e.message}")
                                }, false)
                            }
                            0
                        }
                    ))
        })
    }


}