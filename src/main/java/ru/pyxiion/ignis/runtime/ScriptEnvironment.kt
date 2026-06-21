package ru.pyxiion.ignis.runtime

import net.fabricmc.loader.api.FabricLoader
import org.luaj.vm2.LuaState
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.LoadState
import org.luaj.vm2.compiler.LuaC
import org.luaj.vm2.lib.*
import org.luaj.vm2.lib.jse.JseBaseLib
import org.luaj.vm2.lib.jse.JseMathLib
import org.luaj.vm2.lib.jse.NovaLib
import org.luaj.vm2.lib.VarArgFunction
import ru.pyxiion.ignis.api.LuaMcApi
import ru.pyxiion.ignis.api.vecTable
import ru.pyxiion.ignis.commands.CommandRegistrar
import ru.pyxiion.ignis.sandbox.LuaRequire
import ru.pyxiion.ignis.sandbox.Vfs

class ScriptEnvironment {
    private var _state: LuaState? = null
    val luaState: LuaState get() = _state!!

    fun rebuild(api: LuaMcApi, commandRegistrar: CommandRegistrar): LuaState {
        val state = LuaState()
        LuaC.install(state)
        LoadState.install(state)

        val globals = state.globals
        globals.load(JseBaseLib())
        globals.load(Bit32Lib())
        globals.load(TableLib())
        globals.load(StringLib())
        globals.load(CoroutineLib())
        globals.load(JseMathLib())
        globals.load(NovaLib())

        // Default PackageLib is not safe, so we use our own
        val ignisDir = FabricLoader.getInstance().configDir.resolve("ignis").toAbsolutePath()
        val vfs = Vfs(ignisDir)
        globals.set("require", LuaRequire(state, vfs))

        // Remove unsafe things from JseBaseLib()
        globals.set("collectgarbage", LuaValue.NIL)
        globals.set("loadfile", LuaValue.NIL)
        globals.set("dofile", LuaValue.NIL)

        globals.set("register", commandRegistrar.registerFunction)

        val vecConstructor = object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                require(args.narg() != 3) { "vec(x, y, z) require 3 args" }
                val x = args.arg(1).checkdouble()
                val y = args.arg(2).checkdouble()
                val z = args.arg(3).checkdouble()
                return vecTable(x, y, z)
            }
        }
        globals.set("vec", vecConstructor)

        _state = state
        globals.set("mc", api.toTable())

        return state
    }
}
