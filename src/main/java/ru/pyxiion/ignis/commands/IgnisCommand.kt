package ru.pyxiion.ignis.commands

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import me.lucko.fabric.api.permissions.v0.Permissions
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import ru.pyxiion.luanova.vm2.LuaError
import ru.pyxiion.ignis.IgnisRuntime
import ru.pyxiion.ignis.PxIgnis

/** `/ignis reload | status | watch [on|off] | eval <lua>`. */
object IgnisCommand {
    const val PERM = "px.ignis"
    const val PERM_EVAL = "px.ignis.eval"

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("ignis")
                .requires(Permissions.require(PERM, 4))
                .then(Commands.literal("reload").executes { ctx -> withRuntime(ctx) { reload(ctx, it) } })
                .then(Commands.literal("status").executes { ctx -> withRuntime(ctx) { status(ctx, it) } })
                .then(
                    Commands.literal("watch")
                        .executes { ctx -> withRuntime(ctx) { watch(ctx, it, null) } }
                        .then(Commands.literal("on").executes { ctx -> withRuntime(ctx) { watch(ctx, it, true) } })
                        .then(Commands.literal("off").executes { ctx -> withRuntime(ctx) { watch(ctx, it, false) } })
                )
                .then(
                    Commands.literal("eval")
                        .requires(Permissions.require(PERM_EVAL, 4))
                        .then(
                            Commands.argument("code", StringArgumentType.greedyString())
                                .executes { ctx -> withRuntime(ctx) { eval(ctx, it) } }
                        )
                )
        )
    }

    private inline fun withRuntime(ctx: CommandContext<CommandSourceStack>, block: (IgnisRuntime) -> Int): Int {
        val runtime = PxIgnis.runtimeOrNull
        if (runtime == null) {
            ctx.source.sendFailure(Component.literal("PxIgnis не запущен"))
            return 0
        }
        return block(runtime)
    }

    private fun reply(ctx: CommandContext<CommandSourceStack>, text: String, color: ChatFormatting = ChatFormatting.WHITE) {
        ctx.source.sendSuccess({ Component.literal(text).withStyle(color) }, false)
    }

    private fun reload(ctx: CommandContext<CommandSourceStack>, runtime: IgnisRuntime): Int = try {
        val result = runtime.reload()
        reply(ctx, result.summary(), if (result.ok) ChatFormatting.GREEN else ChatFormatting.RED)
        if (result.ok) 1 else 0
    } catch (e: Throwable) {
        PxIgnis.logger.error("Ошибка при перезагрузке PxIgnis: ${e.message}", e)
        ctx.source.sendFailure(Component.literal("Ошибка при перезагрузке PxIgnis: ${e.message}"))
        0
    }

    private fun status(ctx: CommandContext<CommandSourceStack>, runtime: IgnisRuntime): Int {
        runtime.status().forEach { reply(ctx, it) }
        return 1
    }

    private fun watch(ctx: CommandContext<CommandSourceStack>, runtime: IgnisRuntime, enable: Boolean?): Int {
        if (enable != null) {
            try {
                runtime.setWatching(enable)
            } catch (e: Exception) {
                ctx.source.sendFailure(Component.literal("Не удалось включить слежение за файлами: ${e.message}"))
                return 0
            }
        }
        reply(
            ctx,
            if (runtime.watching) "Автоперезагрузка включена: скрипты перезагрузятся при сохранении файла"
            else "Автоперезагрузка выключена",
        )
        return 1
    }

    private fun eval(ctx: CommandContext<CommandSourceStack>, runtime: IgnisRuntime): Int {
        val code = StringArgumentType.getString(ctx, "code")
        return try {
            reply(ctx, "= " + runtime.eval(code, ctx.source.player), ChatFormatting.GRAY)
            1
        } catch (e: LuaError) {
            ctx.source.sendFailure(Component.literal(e.message ?: e.toString()))
            0
        } catch (e: Exception) {
            PxIgnis.logger.error("/ignis eval failed", e)
            ctx.source.sendFailure(Component.literal("${e.javaClass.simpleName}: ${e.message}"))
            0
        }
    }
}
