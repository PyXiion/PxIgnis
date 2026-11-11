package ru.pyxiion.ignis.types

import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.tree.ArgumentCommandNode
import net.minecraft.commands.CommandSourceStack

interface LuaArgumentType {
    fun getArg(ctx: CommandContext<CommandSourceStack>, name: String): Any
    fun getBrigadierArgument(name: String): ArgumentCommandNode<CommandSourceStack, *>
}