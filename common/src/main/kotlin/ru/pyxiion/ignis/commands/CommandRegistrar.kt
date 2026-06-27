package ru.pyxiion.ignis.commands

import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.exceptions.CommandSyntaxException
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import org.luaj.vm2.*
import org.luaj.vm2.LuaValue.NIL
import ru.pyxiion.ignis.PxIgnis
import ru.pyxiion.ignis.api.wrapper.PlayerWrap
import ru.pyxiion.ignis.luaTableOf
import ru.pyxiion.ignis.luaVarFunction
import ru.pyxiion.ignis.resumeOrThrow
import ru.pyxiion.ignis.types.toLuaValue

class CommandRegistrar(
    private val commandManager: LuaCommandManager,
    private val stateProvider: () -> LuaState,
) {
    val registerFunction = luaVarFunction(::register)

    private fun register(args: Varargs): Varargs {
        require(args.narg() in 2..3) { "register(syntax, handler, permission = nil) requires 2..3 arguments" }
        val syntax = args.arg(1).checkjstring()
        val handler = args.arg(2)
        val permission: String? = args.arg(3).optjstring(null)

        require(handler.isfunction()) { "Command handler must be a function" }

        val (variants, argDefs) = parseSyntax(syntax)
        val function = handler.checkfunction()

        for (variant in variants) {
            val executor = fun(ctx: CommandContext<CommandSourceStack>): Int {
                try {
                    executeLuaCommand(ctx, variant, argDefs, function)
                    return 1
                } catch (e: CommandSyntaxException) {
                    throw e
                } catch (e: LuaError) {
                    ctx.source.sendFailure(Component.literal("При выполнении команды произошла ошибка в скрипте. Сообщите об этом администратору."))
                    PxIgnis.logger.error("Ошибка при выполнении команды \"${ctx.command}\": ${e.message}", e)
                } catch (e: Throwable) {
                    ctx.source.sendFailure(Component.literal("При выполнении команды произошла неизвестная ошибка. Сообщите об этом администратору."))
                    PxIgnis.logger.error("Ошибка при выполнении команды \"${ctx.command}\": ${e.message}", e)
                }
                return 0
            }
            commandManager.addCommand(variant, argDefs, executor, permission)
        }

        return NIL
    }

    private fun parseSyntax(syntax: String): Pair<List<List<SyntaxNode>>, Map<String, ArgDef>> {
        val variants = generateCommandPaths(syntax)
        val allArgNodes = variants.flatten().filterIsInstance<SyntaxNode.Argument>().distinctBy { it.name }
        val argDefs = allArgNodes.associate { arg ->
            arg.name to ArgDef(
                luaType = ArgumentTypes.resolveType(arg.typeDef),
                isOptional = arg.isOptional
            )
        }
        return Pair(variants, argDefs)
    }

    private fun prepareLuaArgAndContext(
        ctx: CommandContext<CommandSourceStack>,
        variant: List<SyntaxNode>,
        argDefs: Map<String, ArgDef>
    ): Array<LuaValue> {
        val player = PlayerWrap.wrap(ctx.source.getPlayerOrException())
        val ctxTable = luaTableOf("player" to player)

        val result = mutableListOf<LuaValue>()
        result.add(ctxTable)
        for (node in variant) {
            if (node is SyntaxNode.Argument) {
                val def = argDefs[node.name]!!
                val arg = def.luaType.getArg(ctx, node.name)
                result.add(toLuaValue(arg))
            }
        }
        return result.toTypedArray()
    }

    private fun executeLuaCommand(
        ctx: CommandContext<CommandSourceStack>,
        variant: List<SyntaxNode>,
        argDefs: Map<String, ArgDef>,
        function: LuaFunction
    ) {
        val luaArgs = prepareLuaArgAndContext(ctx, variant, argDefs)
        val luaState = stateProvider()
        val thread = LuaThread(luaState, function)
        thread.resumeOrThrow(LuaValue.varargsOf(luaArgs))
    }
}
