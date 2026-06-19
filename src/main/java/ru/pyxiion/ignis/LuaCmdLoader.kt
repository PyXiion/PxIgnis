package ru.pyxiion.ignis

import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.FloatArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.exceptions.CommandSyntaxException
import com.mojang.brigadier.tree.ArgumentCommandNode
import kotlinx.coroutines.future.await
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.command.argument.BlockPosArgumentType
import net.minecraft.command.argument.EntityArgumentType
import net.minecraft.command.argument.MessageArgumentType
import net.minecraft.server.MinecraftServer
import net.minecraft.server.command.CommandManager
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import org.luaj.vm2.*
import org.luaj.vm2.LuaValue.NIL
import org.luaj.vm2.compiler.LuaC
import org.luaj.vm2.lib.*
import org.luaj.vm2.lib.jse.JseBaseLib
import org.luaj.vm2.lib.jse.JseMathLib
import org.luaj.vm2.lib.jse.JsePlatform
import org.luaj.vm2.lib.jse.NovaLib
import ru.pyxiion.ignis.PxIgnis.Companion.logger
import ru.pyxiion.ignis.api.ItemStackWrapper
import ru.pyxiion.ignis.api.LuaMcApi
import ru.pyxiion.ignis.api.MobAIManager
import ru.pyxiion.ignis.api.PlayerWrapper
import ru.pyxiion.ignis.api.RegionManager
import ru.pyxiion.ignis.api.SidebarManager
import ru.pyxiion.ignis.api.SidebarWrapper
import ru.pyxiion.ignis.api.HologramManager
import ru.pyxiion.ignis.api.vecTable
import ru.pyxiion.ignis.api.ContainerManager
import ru.pyxiion.ignis.storage.StorageManager
import ru.pyxiion.ignis.types.ChoiceArgumentType
import ru.pyxiion.ignis.types.LuaArgumentType
import ru.pyxiion.ignis.api.AsyncLib
import ru.pyxiion.ignis.types.toLuaValue
import java.io.FileOutputStream
import kotlin.io.path.exists

// Loads and manages Lua-based commands from the config/ignis/ directory.
// Bridges Lua script functions with Minecraft's Brigadier command system.
class LuaCmdLoader(
    private val server: MinecraftServer,
    private val storageManager: StorageManager
) {
    private lateinit var luaState: LuaState

    // Maps argument type names (used in Lua scripts) to their Brigadier node builders and runtime value extractors
    private val argumentTypes = mapOf(
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
                return PlayerWrapper(EntityArgumentType.getPlayer(ctx, name)!!).toLuaValue()
            }

            override fun getBrigadierArgument(name: String): ArgumentCommandNode<ServerCommandSource, *> {
                return CommandManager.argument(name, EntityArgumentType.player()).build()
            }
        },
        "target" to object : LuaArgumentType {
            override fun getArg(ctx: CommandContext<ServerCommandSource>, name: String): Any {
                return PlayerWrapper(EntityArgumentType.getPlayer(ctx, name)!!).toLuaValue()
            }

            override fun getBrigadierArgument(name: String): ArgumentCommandNode<ServerCommandSource, *> {
                return CommandManager.argument(name, EntityArgumentType.player()).build()
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

    // Manages the registered Brigadier command tree for all Lua-defined commands
    private var commandManager: LuaCommandManager? = null

    // Provides the Minecraft API table exposed to Lua scripts
    val api = LuaMcApi(server, storageManager)

    // Manages Lua event handlers registered via mc.on()
    val eventManager = EventBus("", logger)

    // Task scheduler for mc.schedule / mc.scheduleRepeating
    val scheduler: Scheduler get() = api.scheduler

    // Sets up the Lua globals environment: installs standard libraries and registers
    // the `register` function and `mc` API table for Lua scripts
    fun prepareGlobals() {
        luaState = LuaState()
        LuaC.install(luaState)
        LoadState.install(luaState)

        val globals = luaState.globals
        globals.load(JseBaseLib())
        globals.load(PackageLib())
        globals.load(Bit32Lib())
        globals.load(TableLib())
        globals.load(StringLib())
        globals.load(CoroutineLib())
        globals.load(JseMathLib())
        globals.load(NovaLib())

        val ignisDir = FabricLoader.getInstance().configDir.resolve("ignis").toAbsolutePath()
        globals.get("package").set("path", LuaValue.valueOf("${ignisDir}/?.lua;${ignisDir}/?/init.lua;?.lua"))

        globals.set("register", this::register.asVarArgFunction())
        val vecConstructor = object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                require(args.narg() >= 3) { "Vec(x, y, z) требует 3 аргумента" }
                val x = args.arg(1).checkdouble()
                val y = args.arg(2).checkdouble()
                val z = args.arg(3).checkdouble()
                return vecTable(x, y, z)
            }
        }
        globals.set("vec", vecConstructor)
        val mcTable = api.toTable()
        val onHandler: (Varargs) -> Varargs = { args: Varargs ->
            require(args.narg() == 2) { "on(event, handler) requires 2 arguments" }
            val eventName = args.checkjstring(1)
            val handler = args.checkfunction(2)
            val id = eventManager.on(eventName, handler)
            LuaValue.valueOf(id)
        }
        mcTable.set("on", onHandler.asVarArgFunction())

        val offHandler: (Varargs) -> Varargs = { args: Varargs ->
            require(args.narg() == 1) { "off(id) requires 1 argument" }
            val id = args.checkint(1)
            LuaValue.valueOf(eventManager.off(id))
        }
        mcTable.set("off", offHandler.asVarArgFunction())
        val emitHandler: (Varargs) -> Varargs = { args ->
            require(args.narg() >= 1) { "emit(event, ...) requires at least 1 argument" }
            val eventName = args.checkjstring(1)
            val eventArgs = if (args.narg() >= 2) {
                (2..args.narg()).map { args.arg(it) }.toTypedArray()
            } else emptyArray<LuaValue>()
            eventManager.fire(eventName, *eventArgs)
            LuaValue.NIL
        }
        mcTable.set("emit", emitHandler.asVarArgFunction())
        mcTable.set("createItem", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val id = args.arg(1).checkjstring()
                val countOrTable = if (args.narg() >= 2) args.arg(2) else null
                val stack = ItemStackWrapper.createItem(id, countOrTable)
                return ItemStackWrapper.wrap(stack)
            }
        })
        AsyncLib(server, luaState, api.scheduler).install(mcTable)
        globals.set("mc", mcTable)
    }

    // (Re)loads all Lua scripts from config/ignis/ (or legacy config/ignis.lua):
    // reinitializes globals, executes each script, registers all commands
    fun reload() {
        storageManager.saveAll()
        if (commandManager == null)
            commandManager = LuaCommandManager(server)

        eventManager.fire("uninit")
        commandManager!!.clear()
        eventManager.clear()
        scheduler.clear()
        ContainerManager.closeAll()
        SidebarManager.closeAll()
        MobAIManager.restoreAll()
        HologramManager.closeAll()
        RegionManager.closeAll()

        prepareGlobals()

        val sources = getLuaSources()
        for ((name, source) in sources) {
            luaState.load(source, name).call()
        }

        MobAIManager.scanAndReapply(server)
        eventManager.fire("init")

        commandManager!!.registerAll()
        logger.info("PxIgnis зарегистрировал свои команды")
    }

    // Collects Lua sources to load, using the first matching strategy:
    //   1) config/ignis/ directory — all .lua files, sorted alphabetically
    //   2) config/ignis.lua       — legacy single-file fallback
    //   3) Neither exists         — creates config/ignis/ with bundled demo.lua
    private fun getLuaSources(): List<Pair<String, String>> {
        val ignisDir = FabricLoader.getInstance().configDir.resolve("ignis")

        if (ignisDir.toFile().isDirectory()) {
            val files = ignisDir.toFile()
                .listFiles { f -> f.extension == "lua" }
                .orEmpty()
                .sortedBy { it.name }
            if (files.isNotEmpty()) {
                return files.map { it.name to it.readText() }
            }
        }

        val legacyFile = FabricLoader.getInstance().configDir.resolve("ignis.lua")
        if (legacyFile.exists()) {
            return listOf("ignis.lua" to legacyFile.toFile().readText())
        }

        ignisDir.toFile().mkdirs()
        val resource = this::class.java.getResourceAsStream("/demo.lua")
            ?: throw IllegalStateException("Default demo.lua not found in mod JAR")
        val targetFile = ignisDir.resolve("demo.lua").toFile()
        resource.copyTo(FileOutputStream(targetFile))
        logger.info("Создан конфигурационный файл demo.lua в config/ignis/")

        return listOf("demo.lua" to targetFile.readText())
    }


    // Called from Lua as `register("command <arg:type> ...", handler, permission?)`.
    // Parses the syntax string with a minimal character-by-character parser.
    // Supports:
    //   <name:type>       — required argument
    //   [name:type]       — optional argument (trailing only)
    //   <name:type=opts>  — parameterized type (e.g. choice=a,b,c)
    //   literal           — command path node
    // Optional trailing arguments generate multiple Brigadier command variants —
    // missing optional args become nil in the Lua handler.
    // Parameterized types (type=options) resolve to dynamic LuaArgumentType instances.
    private fun register(args: Varargs): Varargs {
        require(args.narg() in 2..3) { "register(syntax, handler, permission = nil) requires 2..3 arguments" }
        val syntax = args.arg(1).checkjstring()
        val handler = args.arg(2)
        val permission: String? = args.arg(3).optjstring(null)

        require(handler.isfunction()) { "Command handler must be a function" }

        val (variants, argDefs) = parseSyntax(syntax)
        val function = handler.checkfunction()

        for (variant in variants) {
            val executor = fun(ctx: CommandContext<ServerCommandSource>): Int {
                try {
                    executeLuaCommand(ctx, variant, argDefs, function)
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
            commandManager!!.addCommand(variant, argDefs, executor, permission)
        }

        return NIL
    }

    private fun parseSyntax(syntax: String): Pair<List<List<SyntaxNode>>, Map<String, ArgDef>> {
        val variants = generateCommandPaths(syntax)
        val allArgNodes = variants.flatten().filterIsInstance<SyntaxNode.Argument>().distinctBy { it.name }
        val argDefs = allArgNodes.associate { arg ->
            arg.name to ArgDef(
                luaType = resolveType(arg.typeDef),
                isOptional = arg.isOptional
            )
        }
        return Pair(variants, argDefs)
    }

    private fun resolveType(typeDef: String): LuaArgumentType {
        val eqIdx = typeDef.indexOf('=')
        if (eqIdx == -1) {
            require(argumentTypes.containsKey(typeDef)) { "Unknown argument type '$typeDef'" }
            return argumentTypes[typeDef]!!
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

    private fun prepareLuaArgAndContext(
        ctx: CommandContext<ServerCommandSource>,
        variant: List<SyntaxNode>,
        argDefs: Map<String, ArgDef>
    ): Array<LuaValue> {
        val player = PlayerWrapper(ctx.source.playerOrThrow).toLuaValue()
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

    // Invokes the Lua handler function with the prepared context and resolved arguments.
    // Missing optional arguments are simply not passed — Lua sees nil for those params.
    private fun executeLuaCommand(
        ctx: CommandContext<ServerCommandSource>,
        variant: List<SyntaxNode>,
        argDefs: Map<String, ArgDef>,
        function: LuaFunction
    ) {
        try {
            val luaArgs = prepareLuaArgAndContext(ctx, variant, argDefs)

            val thread = LuaThread(luaState, function)
            thread.resume(LuaValue.varargsOf(luaArgs))
        } catch (e: LuaError) {
            throw e
        }
    }
}