package ru.pyxiion.ignis.commands

import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.FloatArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.tree.ArgumentCommandNode
import net.minecraft.commands.arguments.coordinates.BlockPosArgument
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.commands.arguments.MessageArgument
import net.minecraft.commands.Commands
import net.minecraft.commands.CommandSourceStack
import ru.pyxiion.ignis.api.wrapper.PlayerWrap
import ru.pyxiion.ignis.luaTableOf
import ru.pyxiion.ignis.types.ChoiceArgumentType
import ru.pyxiion.ignis.types.LuaArgumentType
import org.luaj.vm2.LuaValue
import ru.pyxiion.ignis.api.wrapper.EntityFactory

object ArgumentTypes {
    val builtins: Map<String, LuaArgumentType> = mapOf(
        "text" to object : LuaArgumentType {
            override fun getArg(ctx: CommandContext<CommandSourceStack>, name: String): Any {
                val msg = MessageArgument.getMessage(ctx, name)
                return msg.getString()
            }

            override fun getBrigadierArgument(name: String): ArgumentCommandNode<CommandSourceStack, *> {
                return Commands.argument(name, MessageArgument.message()).build()
            }
        },
        "word" to object : LuaArgumentType {
            override fun getArg(ctx: CommandContext<CommandSourceStack>, name: String): Any {
                return StringArgumentType.getString(ctx, name)
            }

            override fun getBrigadierArgument(name: String): ArgumentCommandNode<CommandSourceStack, *> {
                return Commands.argument(name, StringArgumentType.word()).build()
            }
        },
        "player" to object : LuaArgumentType {
            override fun getArg(ctx: CommandContext<CommandSourceStack>, name: String): Any {
                return PlayerWrap.wrap(EntityArgument.getPlayer(ctx, name)!!)
            }

            override fun getBrigadierArgument(name: String): ArgumentCommandNode<CommandSourceStack, *> {
                return Commands.argument(name, EntityArgument.player()).build()
            }
        },
        "target" to object : LuaArgumentType {
            override fun getArg(ctx: CommandContext<CommandSourceStack>, name: String): Any {
                return PlayerWrap.wrap(EntityArgument.getPlayer(ctx, name)!!)
            }

            override fun getBrigadierArgument(name: String): ArgumentCommandNode<CommandSourceStack, *> {
                return Commands.argument(name, EntityArgument.player()).build()
            }
        },
        "entity" to object : LuaArgumentType {
            override fun getArg(ctx: CommandContext<CommandSourceStack>, name: String): Any {
                return EntityFactory.wrap(EntityArgument.getEntity(ctx, name)!!)
            }

            override fun getBrigadierArgument(name: String): ArgumentCommandNode<CommandSourceStack, *> {
                return Commands.argument(name, EntityArgument.entity()).build()
            }
        },
        "int" to object : LuaArgumentType {
            override fun getArg(ctx: CommandContext<CommandSourceStack>, name: String): Any {
                return IntegerArgumentType.getInteger(ctx, name)
            }

            override fun getBrigadierArgument(name: String): ArgumentCommandNode<CommandSourceStack, *> {
                return Commands.argument(name, IntegerArgumentType.integer()).build()
            }
        },
        "double" to object : LuaArgumentType {
            override fun getArg(ctx: CommandContext<CommandSourceStack>, name: String): Any {
                return DoubleArgumentType.getDouble(ctx, name)
            }

            override fun getBrigadierArgument(name: String): ArgumentCommandNode<CommandSourceStack, *> {
                return Commands.argument(name, DoubleArgumentType.doubleArg()).build()
            }
        },
        "float" to object : LuaArgumentType {
            override fun getArg(ctx: CommandContext<CommandSourceStack>, name: String): Any {
                return FloatArgumentType.getFloat(ctx, name)
            }

            override fun getBrigadierArgument(name: String): ArgumentCommandNode<CommandSourceStack, *> {
                return Commands.argument(name, FloatArgumentType.floatArg()).build()
            }
        },
        "bool" to object : LuaArgumentType {
            override fun getArg(ctx: CommandContext<CommandSourceStack>, name: String): Any {
                return BoolArgumentType.getBool(ctx, name)
            }

            override fun getBrigadierArgument(name: String): ArgumentCommandNode<CommandSourceStack, *> {
                return Commands.argument(name, BoolArgumentType.bool()).build()
            }
        },
        "block_pos" to object : LuaArgumentType {
            override fun getArg(ctx: CommandContext<CommandSourceStack>, name: String): Any {
                val pos = BlockPosArgument.getBlockPos(ctx, name)
                return luaTableOf(
                    "x" to LuaValue.valueOf(pos.x),
                    "y" to LuaValue.valueOf(pos.y),
                    "z" to LuaValue.valueOf(pos.z)
                )
            }

            override fun getBrigadierArgument(name: String): ArgumentCommandNode<CommandSourceStack, *> {
                return Commands.argument(name, BlockPosArgument.blockPos()).build()
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
