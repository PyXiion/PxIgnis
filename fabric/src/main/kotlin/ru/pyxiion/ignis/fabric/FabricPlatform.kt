package ru.pyxiion.ignis.fabric

import me.lucko.fabric.api.permissions.v0.Permissions
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.commands.CommandSource
import net.minecraft.server.MinecraftServer
import ru.pyxiion.ignis.IgnisPlatform
import java.nio.file.Path

class FabricPlatform(server: MinecraftServer) : IgnisPlatform.Platform {
    override val configDir: Path = FabricLoader.getInstance().configDir

    override val server: MinecraftServer get() = server

    override fun checkPermission(source: CommandSource, permission: String): Boolean {
        return Permissions.check(source as? net.minecraft.commands.SharedSuggestionProvider ?: return false, permission)
    }
}
