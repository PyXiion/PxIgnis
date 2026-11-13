package ru.pyxiion.ignis.runtime

import ru.pyxiion.luanova.vm2.LuaError
import ru.pyxiion.luanova.vm2.LuaState
import ru.pyxiion.luanova.vm2.LuaTable
import ru.pyxiion.luanova.vm2.LuaValue
import ru.pyxiion.luanova.vm2.compiler.LuaC
import ru.pyxiion.luanova.vm2.lib.Bit32Lib
import ru.pyxiion.luanova.vm2.lib.CoroutineLib
import ru.pyxiion.luanova.vm2.lib.StringLib
import ru.pyxiion.luanova.vm2.lib.TableLib
import ru.pyxiion.luanova.vm2.lib.jse.JseBaseLib
import ru.pyxiion.luanova.vm2.lib.jse.JseMathLib
import ru.pyxiion.ignis.api.LuaMcApi
import ru.pyxiion.ignis.api.vecTable
import ru.pyxiion.ignis.asFunction
import ru.pyxiion.ignis.commands.CommandRegistrar
import ru.pyxiion.ignis.luaFunction
import ru.pyxiion.ignis.luaVarFunction
import ru.pyxiion.ignis.packages.PackagePlan
import ru.pyxiion.ignis.plugin.PluginManager
import ru.pyxiion.ignis.sandbox.LuaPackage
import ru.pyxiion.ignis.sandbox.PluginModules
import ru.pyxiion.ignis.sandbox.Vfs
import ru.pyxiion.ignis.parallel.StdlibRegistry
import ru.pyxiion.ignis.parallel.installFreeze

/**
 * Убирает из окружения то, что исполняет байткод или читает файлы мимо [Vfs].
 * `load` всегда получает режим `"t"`: у базовой библиотеки умолчание `"bt"`.
 */
internal fun restrictGlobals(globals: LuaTable) {
    globals.set("collectgarbage", LuaValue.NIL)
    globals.set("loadfile", LuaValue.NIL)
    globals.set("dofile", LuaValue.NIL)
    globals.get("string").takeIf { it.istable() }?.set("dump", LuaValue.NIL)

    val original = globals.get("load").checkfunction()
    globals.set("load", luaVarFunction { args ->
        val mode = args.arg(3)
        if (!mode.isnil() && mode.checkjstring().contains('b')) {
            throw LuaError("Loading bytecode is not allowed")
        }
        original.invoke(LuaValue.varargsOf(arrayOf(args.arg(1), args.arg(2), LuaValue.valueOf("t")), args.subargs(4)))
    })
}

/** A read of a global that was never assigned is an error; `rawget(_G, name)` tests for one. */
internal fun installStrictGlobals(globals: LuaTable) {
    globals.setmetatable(LuaTable().also {
        it.rawset("__index", luaFunction { _, key ->
            throw LuaError("global '${key.tojstring()}' is not defined")
        })
    })
}

/**
 * The part of the environment that the main state and every parallel worker share: the compiler, the sandboxed
 * `package`, the standard libraries, `table.freeze` and `vec`. The returned registry describes exactly these globals.
 */
internal fun installStdlib(
    state: LuaState,
    vfs: Vfs,
    packages: PackagePlan? = null,
    plugins: PluginModules? = null,
): StdlibRegistry {
    LuaC.install(state)
    val globals = state.globals

    // Default PackageLib is not safe, so we use our own
    LuaPackage(state, vfs, packages, plugins).call(LuaValue.valueOf("package"), globals)

    globals.load(JseBaseLib())
    globals.load(Bit32Lib())
    globals.load(TableLib())
    globals.load(StringLib())
    globals.load(CoroutineLib())
    globals.load(JseMathLib())

    restrictGlobals(globals)
    installFreeze(globals)

    val vecConstructor = luaVarFunction { args ->
        require(args.narg() == 3) { "vec(x, y, z) require 3 args" }
        val x = args.arg(1).checkdouble()
        val y = args.arg(2).checkdouble()
        val z = args.arg(3).checkdouble()

        vecTable(x, y, z)
    }
    globals.set("vec", vecConstructor)

    return StdlibRegistry(globals)
}

class ScriptEnvironment(private val vfs: Vfs) {
    private var _state: LuaState? = null
    val luaState: LuaState get() = _state!!
    val luaStateOrNull: LuaState? get() = _state

    fun rebuild(api: LuaMcApi, commandRegistrar: CommandRegistrar, plugins: PluginManager, packages: PackagePlan): LuaState {
        val state = LuaState.builder()
            .checkpointHandler(ScriptWatchdog::checkpoint)
            .build()
        val stdlib = installStdlib(state, vfs, packages, plugins.modules)

        val globals = state.globals
        globals.set("register", commandRegistrar.registerFunction)
        globals.set("particle", ru.pyxiion.ignis.api.ParticleLib.constructor { api.registries })

        _state = state
        globals.set("mc", api.toTable())

        val asyncModule = api.asyncLib.buildModule(stdlib)
        globals.get("package").checktable().get("loaded").checktable().set("async", asyncModule)

        // After `mc`, before any script: plugins add their modules and wrapper methods.
        plugins.beginEnvironment(packages)

        installStrictGlobals(globals)
        return state
    }
}
