package ru.pyxiion.pxrp

import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.exceptions.CommandSyntaxException
import com.mojang.brigadier.tree.ArgumentCommandNode
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.command.argument.EntityArgumentType
import net.minecraft.command.argument.MessageArgumentType
import net.minecraft.server.MinecraftServer
import net.minecraft.server.command.CommandManager
import net.minecraft.server.command.ReloadCommand
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.text.Text
import org.luaj.vm2.*
import org.luaj.vm2.LuaValue.NIL
import org.luaj.vm2.compiler.LuaC
import org.luaj.vm2.lib.*
import org.luaj.vm2.lib.jse.CoerceJavaToLua
import org.luaj.vm2.lib.jse.JseBaseLib
import org.luaj.vm2.lib.jse.JseMathLib
import ru.pyxiion.pxrp.PxRp.Companion.logger
import ru.pyxiion.pxrp.api.LuaMcApi
import ru.pyxiion.pxrp.storage.StorageManager
import ru.pyxiion.pxrp.types.LuaArgumentType
import ru.pyxiion.pxrp.api.Player
import java.io.FileOutputStream
import kotlin.io.path.exists

data class LuaCommandArgument(
    val name: String,
    val type: String
)

class LuaCmdLoader(
    private val server: MinecraftServer,
    private val storageManager: StorageManager
) {
    private lateinit var globals: Globals
    private val argumentTypes = mapOf(
        "text" to object : LuaArgumentType {
            override fun getArg(ctx: CommandContext<ServerCommandSource>, name: String): String {
                val msg = MessageArgumentType.getMessage(ctx, name)
                return msg.literalString ?: msg.string
            }

            override fun getBrigadierArgument(name: String): ArgumentCommandNode<ServerCommandSource, *> {
                return CommandManager.argument(name, MessageArgumentType.message()).build()
            }
        },
        "target" to object : LuaArgumentType {
            override fun getArg(ctx: CommandContext<ServerCommandSource>, name: String): Player {
                return Player.fromMcPlayer(EntityArgumentType.getPlayer(ctx, name)!!)
            }

            override fun getBrigadierArgument(name: String): ArgumentCommandNode<ServerCommandSource, *> {
                return CommandManager.argument(name, EntityArgumentType.player()).build()
            }
        }
    )

    private var commandManager: LuaCommandManager? = null

    private var currentContext: CommandContext<ServerCommandSource>? = null

    private val api = LuaMcApi(server, storageManager)

    fun prepareGlobals() {
        globals = Globals()
        LuaC.install(globals)
        LoadState.install(globals)
        globals.load(JseBaseLib())
        globals.load(PackageLib())
        globals.load(Bit32Lib())
        globals.load(TableLib())
        globals.load(StringLib())
//        globals.load(CoroutineLib())
        globals.load(JseMathLib())

        globals.set("register", this::register.asVarArgFunction())
        globals.set("mc", api.toTable())
    }

    fun reload() {
        if (commandManager == null)
            commandManager = LuaCommandManager(server)
        val lua = getLuaFile()

        commandManager!!.clear()
        prepareGlobals()
        globals.load(lua, "pxrp.lua").call()
        commandManager!!.registerAll()
        logger.info("PxRP зарегистрировал свои команды")
    }

    private fun getLuaFile(): String {
        val path = FabricLoader.getInstance().configDir.resolve("pxrp.lua")
        if (!path.exists()) {
            logger.info("Файл pxrp.lua не найден, копируем файл по умолчанию...")
            val resource = this::class.java.getResourceAsStream("/pxrp.lua")
                ?: throw IllegalStateException("Default pxrp.lua not found in mod JAR")
            resource.copyTo(FileOutputStream(path.toFile()))
        }

        return path.toFile().bufferedReader().use { it.readText() }
    }


    private fun register(args: Varargs): Varargs {
        require(args.narg() in 3..4) { "register(command, arguments, handler, permission = nil) requires 3..4 arguments" }
        val commandName = args.arg(1).checkjstring()
        val arguments = args.arg(2).checkstringlist()
        val handler = args.arg(3)
        val permission: String? = args.arg(4).optjstring(null)

        require(handler.isfunction()) { "Command handler must be a function" }

        val argsInfo = parseArgs(arguments)
        val executor =
            fun(ctx: CommandContext<ServerCommandSource>): Int {
                try {
                    val luaArgs = getLuaArgs(ctx, argsInfo.map { LuaCommandArgument(it.second.name, it.first) })
                    executeLuaWithCommandContext(ctx, luaArgs, handler.checkfunction())
                    return 1
                } catch (e: CommandSyntaxException) {
                    throw e
                } catch (e: LuaError) {
                    ctx.source.sendError(Text.literal("При выполнении команды произошла ошибка в скрипте. Сообщите об этом администратору."))
                    logger.error("Ошибка при выполнении команды \"${ctx.command}\": ${e.message}")
                } catch (e: Throwable) {
                    ctx.source.sendError(Text.literal("При выполнении команды произошла неизвестная ошибка. Сообщите об этом администратору."))
                    logger.error("Ошибка при выполнении команды \"${ctx.command}\": ${e.message}", e)
                }
                return 0
            }

        commandManager!!.addCommand(commandName, argsInfo.map { it.second }, executor, permission)

        return NIL
    }

    private fun parseArgs(args: List<String>): List<Pair<String, ArgumentCommandNode<ServerCommandSource, *>>> {
        val result = mutableListOf<Pair<String, ArgumentCommandNode<ServerCommandSource, *>>>()
        val usedNames = mutableMapOf<String, Int>()

        for (arg in args) {
            if (arg.contains(':')) {
                val (name, type) = arg.split(':')
                require(argumentTypes.containsKey(type)) { "Unknown argument type '$type'" }
                result.add(Pair(type, argumentTypes[type]!!.getBrigadierArgument(name)))
            } else {
                require(argumentTypes.containsKey(arg)) { "Unknown argument type '$arg'" }
                val name = "$arg${usedNames.getOrPut(arg) { 0 } + 1}"
                usedNames[arg] = usedNames[arg]!!.plus(1)
                result.add(Pair(arg, argumentTypes[arg]!!.getBrigadierArgument(name)))
            }
        }

        return result
    }

    private fun getLuaArgs(ctx: CommandContext<ServerCommandSource>, argsInfo: List<LuaCommandArgument>): Array<LuaValue> {
        return argsInfo.map {
            CoerceJavaToLua.coerce(argumentTypes[it.type]!!.getArg(ctx, it.name))
        }.toTypedArray()
    }

    private fun executeLuaWithCommandContext(ctx: CommandContext<ServerCommandSource>, args: Array<LuaValue>, function: LuaFunction) {
        val player = Player.fromMcPlayer(ctx.source.playerOrThrow)

        // prepare vars
        currentContext = ctx
        globals.set("player", CoerceJavaToLua.coerce(player))

        // call
        try {
            function.invoke(args)
        } catch (e: LuaError) {
//            error(e.message!!)
            throw e
        }

        // unset vars
        globals.set("player", NIL)
        currentContext = null
    }
}