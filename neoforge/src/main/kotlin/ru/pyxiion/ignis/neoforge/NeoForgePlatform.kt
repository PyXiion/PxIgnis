package ru.pyxiion.ignis.neoforge

import net.minecraft.commands.CommandSource
import net.minecraft.server.MinecraftServer
import net.minecraft.commands.CommandSourceStack
import net.minecraft.server.level.ServerPlayer
import net.minecraft.network.chat.Component
import ru.pyxiion.ignis.IgnisPlatform
import java.nio.file.Path
import net.neoforged.fml.loading.FMLPaths

class NeoForgePlatform(server: MinecraftServer) : IgnisPlatform.Platform {
    override val configDir: Path = FMLPaths.CONFIGDIR.get()
    override val server: MinecraftServer get() = server

    override fun checkPermission(source: CommandSource, permission: String): Boolean {
        return true
    }
}
