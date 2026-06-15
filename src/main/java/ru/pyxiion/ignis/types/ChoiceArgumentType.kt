package ru.pyxiion.ignis.types

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.exceptions.CommandSyntaxException
import com.mojang.brigadier.suggestion.SuggestionProvider
import com.mojang.brigadier.tree.ArgumentCommandNode
import net.minecraft.server.command.CommandManager
import net.minecraft.server.command.ServerCommandSource

class ChoiceArgumentType(private val choices: List<String>) : LuaArgumentType {
    init {
        require(choices.isNotEmpty()) { "choice type requires at least one option" }
        require(choices.all { it.isNotBlank() }) { "choice options must not be blank" }
    }

    override fun getArg(ctx: CommandContext<ServerCommandSource>, name: String): Any {
        val value = StringArgumentType.getString(ctx, name)
        if (value !in choices) {
            throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownArgument().create()
        }
        return value
    }

    override fun getBrigadierArgument(name: String): ArgumentCommandNode<ServerCommandSource, *> {
        return CommandManager.argument(name, StringArgumentType.word())
            .suggests(SuggestionProvider { _, builder ->
                choices.forEach { builder.suggest(it) }
                builder.buildFuture()
            })
            .build()
    }
}
