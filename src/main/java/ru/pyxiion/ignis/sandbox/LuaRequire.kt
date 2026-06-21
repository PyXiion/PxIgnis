package ru.pyxiion.ignis.sandbox

import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaState
import org.luaj.vm2.LuaValue
import org.luaj.vm2.lib.OneArgFunction
import java.io.ByteArrayInputStream

class LuaRequire(
    private val luaState: LuaState,
    private val vfs: Vfs
) : OneArgFunction() {
    private val loaded = mutableMapOf<String, LuaValue>()
    private val loading = mutableSetOf<String>()

    override fun call(arg: LuaValue): LuaValue {
        val name = arg.checkjstring()

        loaded[name]?.let { return it }

        if (!loading.add(name)) {
            throw LuaError("circular require detected: module '$name' is already being loaded")
        }

        try {
            val module = vfs.resolve(name)
                ?: throw LuaError("module '$name' not found")

            val result = luaState.load(
                ByteArrayInputStream(module.content),
                module.sourceName,
                "bt",
                luaState.globals
            )

            if (!result.arg1().isfunction()) {
                throw LuaError("error loading module '$name': ${result.arg(2).tojstring()}")
            }

            val ret = result.arg1().call(name)
            val finalValue = if (ret.isnil()) LuaValue.TRUE else ret.arg1()
            loaded[name] = finalValue
            return finalValue
        } finally {
            loading.remove(name)
        }
    }
}
