package ru.pyxiion.ignis.commands

import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.FloatArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.tree.ArgumentCommandNode
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.commands.arguments.MessageArgument
import net.minecraft.commands.arguments.coordinates.BlockPosArgument
import ru.pyxiion.ignis.api.Vector
import ru.pyxiion.ignis.api.wrapper.EntityFactory
import ru.pyxiion.ignis.api.wrapper.PlayerWrap
import ru.pyxiion.ignis.types.LuaArgumentType

object ArgumentTypes {
    private fun type(
        brigadier: () -> ArgumentType<*>,
        get: (CommandContext<CommandSourceStack>, String) -> Any,
    ): LuaArgumentType = object : LuaArgumentType {
        override fun getArg(ctx: CommandContext<CommandSourceStack>, name: String): Any = get(ctx, name)

        override fun getBrigadierArgument(name: String): ArgumentCommandNode<CommandSourceStack, *> =
            Commands.argument(name, brigadier()).build()
    }

    val builtins: Map<String, LuaArgumentType> = mapOf(
        "text" to type({ MessageArgument.message() }) { ctx, name ->
            val msg = MessageArgument.getMessage(ctx, name)
            msg.tryCollapseToString() ?: msg.string
        },
        "word" to type({ StringArgumentType.word() }) { ctx, name -> StringArgumentType.getString(ctx, name) },
        "player" to type({ EntityArgument.player() }) { ctx, name ->
            PlayerWrap.wrap(EntityArgument.getPlayer(ctx, name))
        },
        "entity" to type({ EntityArgument.entity() }) { ctx, name ->
            EntityFactory.wrap(EntityArgument.getEntity(ctx, name))
        },
        "int" to type({ IntegerArgumentType.integer() }) { ctx, name -> IntegerArgumentType.getInteger(ctx, name) },
        "double" to type({ DoubleArgumentType.doubleArg() }) { ctx, name -> DoubleArgumentType.getDouble(ctx, name) },
        "float" to type({ FloatArgumentType.floatArg() }) { ctx, name -> FloatArgumentType.getFloat(ctx, name) },
        "bool" to type({ BoolArgumentType.bool() }) { ctx, name -> BoolArgumentType.getBool(ctx, name) },
        "block_pos" to type({ BlockPosArgument.blockPos() }) { ctx, name ->
            val pos = BlockPosArgument.getBlockPos(ctx, name)
            Vector.of(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble()).toLuaValue()
        },
    )

    /** Built-in types only: `choice=…` never gets here, [generateCommandPaths] turns it into literals. */
    fun resolveType(typeDef: String): LuaArgumentType =
        builtins[typeDef] ?: throw IllegalArgumentException("Unknown argument type '$typeDef'")
}
