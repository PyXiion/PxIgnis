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
import ru.pyxiion.pxrp.api.Context
import ru.pyxiion.pxrp.api.LuaMcApi
import ru.pyxiion.pxrp.storage.StorageManager
import ru.pyxiion.pxrp.types.LuaArgumentType
import ru.pyxiion.pxrp.api.Player
import java.io.FileOutputStream
import kotlin.io.path.exists

// Represents a parsed Lua command argument with its name and argument type identifier (e.g. "text", "target")
data class LuaCommandArgument(
    val name: String,
    val type: String
)

// Loads and manages Lua-based commands from the pxrp.lua configuration file.
// Bridges Lua script functions with Minecraft's Brigadier command system.
class LuaCmdLoader(
    private val server: MinecraftServer,
    private val storageManager: StorageManager
) {
    // Lua global environment shared across all script executions
    private lateinit var globals: Globals

    // Maps argument type names (used in Lua scripts) to their Brigadier node builders and runtime value extractors
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

    // Manages the registered Brigadier command tree for all Lua-defined commands
    private var commandManager: LuaCommandManager? = null

    // Provides the Minecraft API table exposed to Lua scripts
    private val api = LuaMcApi(server, storageManager)

    // Sets up the Lua globals environment: installs standard libraries and registers
    // the `register` function and `mc` API table for Lua scripts
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

    // (Re)loads the pxrp.lua script: reads the file, reinitializes globals,
    // executes the script, and registers all commands defined in it
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

    // Reads the pxrp.lua file from the config directory. If it doesn't exist,
    // copies the default bundled version from the mod JAR into the config directory
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


    // Called from Lua as `register(command, arguments, handler, permission)`.
    // Parses the argument definitions, builds a Brigadier executor that dispatches
    // to the Lua handler function, and registers the command with the command manager
    private fun register(args: Varargs): Varargs {
        require(args.narg() in 3..4) { "register(command, arguments, handler, permission = nil) requires 3..4 arguments" }
        val commandName = args.arg(1).checkjstring()
        val arguments = args.arg(2).checkstringlist()
        val handler = args.arg(3)
        val permission: String? = args.arg(4).optjstring(null)

        require(handler.isfunction()) { "Command handler must be a function" }

        val parsedArgs = parseArgs(arguments)

        val executor =
            fun(ctx: CommandContext<ServerCommandSource>): Int {
                try {
                    executeLuaCommand(ctx, parsedArgs, handler.checkfunction())
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

        commandManager!!.addCommand(commandName, parsedArgs.map { it.second }, executor, permission)

        return NIL
    }

    // Parses Lua argument type strings (e.g. "target" or "msg:text") into pairs of
    // [typeName, BrigadierArgumentNode]. Auto-generates unique names for unnamed arguments
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

    // Prepares the Lua call arguments: first argument is a Context object (wrapping the
    // executing player), followed by the resolved command argument values (e.g. strings or Players)
    private fun prepareLuaArgAndContext(ctx: CommandContext<ServerCommandSource>, argsInfo: List<Pair<String, ArgumentCommandNode<ServerCommandSource, *>>>): Array<LuaValue> {
        val args = argsInfo.map { LuaCommandArgument(it.second.name, it.first) }

        val player = Player.fromMcPlayer(ctx.source.playerOrThrow)
        val luaCtx = Context(player)

        return arrayOf(CoerceJavaToLua.coerce(luaCtx)) + args.map {
            CoerceJavaToLua.coerce(argumentTypes[it.type]!!.getArg(ctx, it.name))
        }.toTypedArray()
    }

    // Invokes the Lua handler function with the prepared context and arguments,
    // then clears temporary global state (`player` and `currentContext`)
    private fun executeLuaCommand(ctx: CommandContext<ServerCommandSource>, argsInfo: List<Pair<String, ArgumentCommandNode<ServerCommandSource, *>>>, function: LuaFunction) {
        try {
            val luaArgs = prepareLuaArgAndContext(ctx, argsInfo)
            function.invoke(luaArgs)
        } catch (e: LuaError) {
            throw e
        }
    }
}