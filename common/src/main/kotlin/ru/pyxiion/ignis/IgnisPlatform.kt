package ru.pyxiion.ignis

import net.minecraft.commands.CommandSource
import net.minecraft.server.MinecraftServer
import java.nio.file.Path

object IgnisPlatform {
    lateinit var instance: Platform

    interface Platform {
        val configDir: Path
        val server: MinecraftServer
        fun checkPermission(source: CommandSource, permission: String): Boolean
    }
}
