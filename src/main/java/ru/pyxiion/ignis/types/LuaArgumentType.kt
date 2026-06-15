package ru.pyxiion.ignis.types

import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.tree.ArgumentCommandNode
import net.minecraft.server.command.ServerCommandSource

interface LuaArgumentType {
    fun getArg(ctx: CommandContext<ServerCommandSource>, name: String): Any
    fun getBrigadierArgument(name: String): ArgumentCommandNode<ServerCommandSource, *>
}