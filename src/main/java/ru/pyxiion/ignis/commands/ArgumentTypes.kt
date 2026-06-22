package ru.pyxiion.ignis.commands

import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.FloatArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.tree.ArgumentCommandNode
import net.minecraft.command.argument.BlockPosArgumentType
import net.minecraft.command.argument.EntityArgumentType
import net.minecraft.command.argument.MessageArgumentType
import net.minecraft.server.command.CommandManager
import net.minecraft.server.command.ServerCommandSource
import ru.pyxiion.ignis.api.wrapper.PlayerWrap
import ru.pyxiion.ignis.luaTableOf
import ru.pyxiion.ignis.types.ChoiceArgumentType
import ru.pyxiion.ignis.types.LuaArgumentType
import org.luaj.vm2.LuaValue
import ru.pyxiion.ignis.api.wrapper.EntityFactory

object ArgumentTypes {
    val builtins: Map<String, LuaArgumentType> = mapOf(
        "text" to object : LuaArgumentType {
            override fun getArg(ctx: CommandContext<ServerCommandSource>, name: String): Any {
                val msg = MessageArgumentType.getMessage(ctx, name)
                return msg.literalString ?: msg.string
            }

            override fun getBrigadierArgument(name: String): ArgumentCommandNode<ServerCommandSource, *> {
                return CommandManager.argument(name, MessageArgumentType.message()).build()
            }
        },
        "word" to object : LuaArgumentType {
            override fun getArg(ctx: CommandContext<ServerCommandSource>, name: String): Any {
                return StringArgumentType.getString(ctx, name)
            }

            override fun getBrigadierArgument(name: String): ArgumentCommandNode<ServerCommandSource, *> {
                return CommandManager.argument(name, StringArgumentType.word()).build()
            }
        },
        "player" to object : LuaArgumentType {
            override fun getArg(ctx: CommandContext<ServerCommandSource>, name: String): Any {
                return PlayerWrap.wrap(EntityArgumentType.getPlayer(ctx, name)!!)
            }

            override fun getBrigadierArgument(name: String): ArgumentCommandNode<ServerCommandSource, *> {
                return CommandManager.argument(name, EntityArgumentType.player()).build()
            }
        },
        "target" to object : LuaArgumentType {
            override fun getArg(ctx: CommandContext<ServerCommandSource>, name: String): Any {
                return PlayerWrap.wrap(EntityArgumentType.getPlayer(ctx, name)!!)
            }

            override fun getBrigadierArgument(name: String): ArgumentCommandNode<ServerCommandSource, *> {
                return CommandManager.argument(name, EntityArgumentType.player()).build()
            }
        },
        "entity" to object : LuaArgumentType {
            override fun getArg(ctx: CommandContext<ServerCommandSource>, name: String): Any {
                return EntityFactory.wrap(EntityArgumentType.getEntity(ctx, name)!!)
            }

            override fun getBrigadierArgument(name: String): ArgumentCommandNode<ServerCommandSource, *> {
                return CommandManager.argument(name, EntityArgumentType.entity()).build()
            }
        },
        "int" to object : LuaArgumentType {
            override fun getArg(ctx: CommandContext<ServerCommandSource>, name: String): Any {
                return IntegerArgumentType.getInteger(ctx, name)
            }

            override fun getBrigadierArgument(name: String): ArgumentCommandNode<ServerCommandSource, *> {
                return CommandManager.argument(name, IntegerArgumentType.integer()).build()
            }
        },
        "double" to object : LuaArgumentType {
            override fun getArg(ctx: CommandContext<ServerCommandSource>, name: String): Any {
                return DoubleArgumentType.getDouble(ctx, name)
            }

            override fun getBrigadierArgument(name: String): ArgumentCommandNode<ServerCommandSource, *> {
                return CommandManager.argument(name, DoubleArgumentType.doubleArg()).build()
            }
        },
        "float" to object : LuaArgumentType {
            override fun getArg(ctx: CommandContext<ServerCommandSource>, name: String): Any {
                return FloatArgumentType.getFloat(ctx, name)
            }

            override fun getBrigadierArgument(name: String): ArgumentCommandNode<ServerCommandSource, *> {
                return CommandManager.argument(name, FloatArgumentType.floatArg()).build()
            }
        },
        "bool" to object : LuaArgumentType {
            override fun getArg(ctx: CommandContext<ServerCommandSource>, name: String): Any {
                return BoolArgumentType.getBool(ctx, name)
            }

            override fun getBrigadierArgument(name: String): ArgumentCommandNode<ServerCommandSource, *> {
                return CommandManager.argument(name, BoolArgumentType.bool()).build()
            }
        },
        "block_pos" to object : LuaArgumentType {
            override fun getArg(ctx: CommandContext<ServerCommandSource>, name: String): Any {
                val pos = BlockPosArgumentType.getBlockPos(ctx, name)
                return luaTableOf(
                    "x" to LuaValue.valueOf(pos.x),
                    "y" to LuaValue.valueOf(pos.y),
                    "z" to LuaValue.valueOf(pos.z)
                )
            }

            override fun getBrigadierArgument(name: String): ArgumentCommandNode<ServerCommandSource, *> {
                return CommandManager.argument(name, BlockPosArgumentType.blockPos()).build()
            }
        }
    )

    fun resolveType(typeDef: String): LuaArgumentType {
        val eqIdx = typeDef.indexOf('=')
        if (eqIdx == -1) {
            require(builtins.containsKey(typeDef)) { "Unknown argument type '$typeDef'" }
            return builtins[typeDef]!!
        }

        val baseType = typeDef.substring(0, eqIdx)
        val params = typeDef.substring(eqIdx + 1)

        return when (baseType) {
            "choice" -> {
                val choices = params.split(",").map { it.trim() }
                ChoiceArgumentType(choices)
            }

            else -> throw IllegalArgumentException("Unknown argument type '$baseType' with parameters")
        }
    }
}
