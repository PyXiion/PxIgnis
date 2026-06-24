package ru.pyxiion.ignis.sandbox

import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaState
import org.luaj.vm2.LuaString
import org.luaj.vm2.LuaValue
import org.luaj.vm2.lib.OneArgFunction
import org.luaj.vm2.lib.TwoArgFunction
import ru.pyxiion.ignis.PxIgnis
import java.io.ByteArrayInputStream

class LuaPackage(
    private val luaState: LuaState,
    private val vfs: Vfs
) : TwoArgFunction() {
    private val loadedTable = tableOf()
    private val packageTable = tableOf().apply { set("loaded", loadedTable) }
    private val loading = mutableSetOf<LuaString>()


    private fun getOrPutLib(name: LuaString, f: () -> LuaValue): LuaValue {
        val existing = loadedTable.get(name)
        if (!existing.isnil()) return existing

        val newLib = f()
        loadedTable.set(name, newLib)
        return newLib
    }

    class Require(private val lib: LuaPackage) : OneArgFunction() {
        override fun call(arg: LuaValue): LuaValue {
            val name = arg.checkstring()

            return lib.getOrPutLib(name) {
                if (!lib.loading.add(name)) {
                    PxIgnis.logger.error("circular require detected: module '$name' is already being loaded")
                    throw LuaError("circular require detected: module '$name' is already being loaded")
                }

                try {
                    val module = lib.vfs.resolve(name.checkjstring())
                        ?: throw LuaError("module '$name' not found")

                    val moduleF = lib.luaState.load(
                        ByteArrayInputStream(module.content),
                        module.sourceName,
                        "t",
                        lib.luaState.globals
                    )

                    val result = moduleF.call()
                    return@getOrPutLib if (result.isnil()) LuaValue.TRUE else result
                } catch (e: LuaError) {
                    PxIgnis.logger.error("error loading module: '$name': ${e.message}", e)
                    throw LuaError("error loading module '$name': ${e.message}")
                } catch (t: Throwable) {
                    PxIgnis.logger.error("unexpected error loading module: '$name'", t)
                    throw LuaError("unexpected error loading module '$name': ${t.javaClass.simpleName}: ${t.message}")
                } finally {
                    lib.loading.remove(name)
                }
            }
        }
    }

    override fun call(name: LuaValue, env: LuaValue): LuaValue {
        env.set("package", packageTable)
        env.set("require", Require(this))

        return NIL
    }
}
