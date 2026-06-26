package ru.pyxiion.ignis.runtime

import net.fabricmc.loader.api.FabricLoader
import org.luaj.vm2.LoadState
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaState
import org.luaj.vm2.LuaValue
import org.luaj.vm2.compiler.LuaC
import org.luaj.vm2.lib.Bit32Lib
import org.luaj.vm2.lib.CoroutineLib
import org.luaj.vm2.lib.StringLib
import org.luaj.vm2.lib.TableLib
import org.luaj.vm2.lib.jse.JseBaseLib
import org.luaj.vm2.lib.jse.JseMathLib
import org.luaj.vm2.lib.jse.NovaLib
import ru.pyxiion.ignis.api.LuaMcApi
import ru.pyxiion.ignis.api.vecTable
import ru.pyxiion.ignis.asFunction
import ru.pyxiion.ignis.commands.CommandRegistrar
import ru.pyxiion.ignis.luaVarFunction
import ru.pyxiion.ignis.sandbox.LuaPackage
import ru.pyxiion.ignis.sandbox.Vfs

class ScriptEnvironment {
    private var _state: LuaState? = null
    val luaState: LuaState get() = _state!!

    fun rebuild(api: LuaMcApi, commandRegistrar: CommandRegistrar): LuaState {
        val state = LuaState()
        LuaC.install(state)
        LoadState.install(state)

        val globals = state.globals

        // Default PackageLib is not safe, so we use our own
        val ignisDir = FabricLoader.getInstance().configDir.resolve("ignis").toAbsolutePath()
        val vfs = Vfs(ignisDir)
        LuaPackage(state, vfs).call(LuaValue.valueOf("package"), globals)

        globals.load(JseBaseLib())
        globals.load(Bit32Lib())
        globals.load(TableLib())
        globals.load(StringLib())
        globals.load(CoroutineLib())
        globals.load(JseMathLib())
        globals.load(NovaLib())


        // Remove unsafe things from JseBaseLib()
        globals.set("collectgarbage", LuaValue.NIL)
        globals.set("loadfile", LuaValue.NIL)
        globals.set("dofile", LuaValue.NIL)

        // Forbid loading bytecode
        globals.set("load", globals.get("load").asFunction()?.let {
            luaVarFunction { args ->
                if (args.arg(3).optjstring("t").contains("b")) {
                    throw LuaError("Loading bytecode is not allowed")
                }
                it.invoke(args)
            }
        })

        globals.set("register", commandRegistrar.registerFunction)

        val vecConstructor = luaVarFunction { args ->
            require(args.narg() == 3) { "vec(x, y, z) require 3 args" }
            val x = args.arg(1).checkdouble()
            val y = args.arg(2).checkdouble()
            val z = args.arg(3).checkdouble()

            vecTable(x, y, z)
        }
        globals.set("vec", vecConstructor)

        _state = state
        globals.set("mc", api.toTable())

        return state
    }
}
