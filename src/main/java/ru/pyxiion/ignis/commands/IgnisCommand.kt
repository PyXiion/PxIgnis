package ru.pyxiion.ignis.commands

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import me.lucko.fabric.api.permissions.v0.Permissions
import net.minecraft.server.command.CommandManager
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import org.luaj.vm2.LuaError
import ru.pyxiion.ignis.IgnisRuntime
import ru.pyxiion.ignis.PxIgnis

/** `/ignis reload | status | watch [on|off] | eval <lua>`. */
object IgnisCommand {
    const val PERM = "px.ignis"
    const val PERM_EVAL = "px.ignis.eval"

    fun register(dispatcher: CommandDispatcher<ServerCommandSource>) {
        dispatcher.register(
            CommandManager.literal("ignis")
                .requires(Permissions.require(PERM, 4))
                .then(CommandManager.literal("reload").executes { ctx -> withRuntime(ctx) { reload(ctx, it) } })
                .then(CommandManager.literal("status").executes { ctx -> withRuntime(ctx) { status(ctx, it) } })
                .then(
                    CommandManager.literal("watch")
                        .executes { ctx -> withRuntime(ctx) { watch(ctx, it, null) } }
                        .then(CommandManager.literal("on").executes { ctx -> withRuntime(ctx) { watch(ctx, it, true) } })
                        .then(CommandManager.literal("off").executes { ctx -> withRuntime(ctx) { watch(ctx, it, false) } })
                )
                .then(
                    CommandManager.literal("eval")
                        .requires(Permissions.require(PERM_EVAL, 4))
                        .then(
                            CommandManager.argument("code", StringArgumentType.greedyString())
                                .executes { ctx -> withRuntime(ctx) { eval(ctx, it) } }
                        )
                )
        )
    }

    private inline fun withRuntime(ctx: CommandContext<ServerCommandSource>, block: (IgnisRuntime) -> Int): Int {
        if (!PxIgnis.instance.hasRuntime()) {
            ctx.source.sendError(Text.literal("PxIgnis ещё не запущен"))
            return 0
        }
        return block(PxIgnis.instance.runtime)
    }

    private fun reply(ctx: CommandContext<ServerCommandSource>, text: String, color: Formatting = Formatting.WHITE) {
        ctx.source.sendFeedback({ Text.literal(text).formatted(color) }, false)
    }

    private fun reload(ctx: CommandContext<ServerCommandSource>, runtime: IgnisRuntime): Int = try {
        val result = runtime.reload()
        reply(ctx, result.summary(), if (result.ok) Formatting.GREEN else Formatting.RED)
        if (result.ok) 1 else 0
    } catch (e: Throwable) {
        PxIgnis.logger.error("Ошибка при перезагрузке PxIgnis: ${e.message}", e)
        ctx.source.sendError(Text.literal("Ошибка при перезагрузке PxIgnis: ${e.message}"))
        0
    }

    private fun status(ctx: CommandContext<ServerCommandSource>, runtime: IgnisRuntime): Int {
        runtime.status().forEach { reply(ctx, it) }
        return 1
    }

    private fun watch(ctx: CommandContext<ServerCommandSource>, runtime: IgnisRuntime, enable: Boolean?): Int {
        if (enable != null) {
            try {
                runtime.setWatching(enable)
            } catch (e: Exception) {
                ctx.source.sendError(Text.literal("Не удалось включить слежение за файлами: ${e.message}"))
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

    private fun eval(ctx: CommandContext<ServerCommandSource>, runtime: IgnisRuntime): Int {
        val code = StringArgumentType.getString(ctx, "code")
        return try {
            reply(ctx, "= " + runtime.eval(code, ctx.source.player), Formatting.GRAY)
            1
        } catch (e: LuaError) {
            ctx.source.sendError(Text.literal(e.message ?: e.toString()))
            0
        } catch (e: Exception) {
            PxIgnis.logger.error("/ignis eval failed", e)
            ctx.source.sendError(Text.literal("${e.javaClass.simpleName}: ${e.message}"))
            0
        }
    }
}
