package ru.pyxiion.ignis.sandbox

import ru.pyxiion.ignis.PxIgnis
import ru.pyxiion.ignis.packages.PackagePlan
import ru.pyxiion.ignis.runtime.ScriptErrors
import ru.pyxiion.luanova.vm2.LuaError
import ru.pyxiion.luanova.vm2.LuaState
import ru.pyxiion.luanova.vm2.LuaString
import ru.pyxiion.luanova.vm2.LuaTable
import ru.pyxiion.luanova.vm2.LuaValue
import ru.pyxiion.luanova.vm2.lib.OneArgFunction
import ru.pyxiion.luanova.vm2.lib.TwoArgFunction
import java.io.ByteArrayInputStream

/** Source of `ext:` modules. [load] throws [LuaError] when the plugin or the submodule does not exist. */
fun interface PluginModules {
    /** @param callerPackage the package whose code asks (null for loose scripts); plugins resolve files against it. */
    fun load(id: String, sub: String?, callerPackage: String?): LuaValue
}

/**
 * The sandboxed `package` table and `require`.
 *
 * Names: `core:x` (built-in), `pkg:id[.a.b]` (script package, see [PackagePlan]), `ext:id[.sub]` (plugin), and a plain
 * `a.b` for `a/b.lua` of the script roots. A plain name inside a package resolves in that package's folder only: its
 * code runs in an environment with its own `require`. Modules are cached in `package.loaded` under a canonical name,
 * so `pkg:arena.util` and arena's own `require("util")` are one module.
 */
/**
 * The `_ENV` of a package's code: reads and writes fall through to the globals, and `require` is the package's own.
 * `async.parallel` treats it as the globals (a worker has plain globals and the loose `require`).
 */
class PackageEnv : LuaTable()

class LuaPackage(
    private val luaState: LuaState,
    private val vfs: Vfs,
    private val packages: PackagePlan? = null,
    private val plugins: PluginModules? = null,
) : TwoArgFunction() {
    private val loadedTable = tableOf()
    private val packageTable = tableOf().apply {
        set("loaded", loadedTable)
        // Informational: require() resolves through the Vfs, which searches the same roots in the same order.
        set("path", vfs.searchPath)
    }
    private val loading = ArrayList<String>()
    private val packageEnvs = HashMap<String, LuaTable>()

    /** `require` for code running in package [scope] (null: loose scripts and built-ins). */
    fun requireFunction(scope: String?): LuaValue = Require(this, scope)

    private fun envFor(scope: String?): LuaValue {
        if (scope == null) return luaState.globals
        return packageEnvs.getOrPut(scope) {
            val env = PackageEnv()
            val meta = LuaTable()
            meta.rawset("__index", luaState.globals)
            meta.rawset("__newindex", luaState.globals)
            env.setmetatable(meta)
            env.rawset("require", Require(this, scope))
            env
        }
    }

    private class Require(private val lib: LuaPackage, private val scope: String?) : OneArgFunction() {
        override fun call(arg: LuaValue): LuaValue = lib.require(scope, arg.checkstring())
    }

    private fun require(scope: String?, name: LuaString): LuaValue {
        val jname = name.checkjstring()
        val ref = try {
            ModuleNames.parse(jname)
        } catch (e: IllegalArgumentException) {
            throw LuaError(e.message)
        }
        if (ref is ModuleRef.Ext) {
            // Not cached in package.loaded: the plugin hands out one view per calling package, and keeps its own cache.
            val host = plugins ?: throw LuaError("plugin '${ref.id}' is not installed")
            return host.load(ref.id, ref.sub, scope)
        }
        if (ref is ModuleRef.Plain) {
            // Names that are in `package.loaded` as given (`async`, anything a script put there) win.
            loadedTable.get(name).takeUnless { it.isnil() }?.let { return it }
        }
        val key = canonicalName(scope, ref)
        val luaKey = LuaValue.valueOf(key)
        loadedTable.get(luaKey).takeUnless { it.isnil() }?.let { return it }

        if (key in loading) {
            val chain = (loading.subList(loading.indexOf(key), loading.size) + key).joinToString(" -> ")
            throw LuaError("circular require: $chain")
        }
        loading.add(key)
        try {
            val value = load(scope, ref, key)
            loadedTable.set(luaKey, value)
            return value
        } finally {
            loading.removeLast()
        }
    }

    private fun canonicalName(scope: String?, ref: ModuleRef): String = when (ref) {
        is ModuleRef.Core -> "core:${ref.name}"
        is ModuleRef.Ext -> "ext:${ref.id}" + (ref.sub?.let { ".$it" } ?: "")
        is ModuleRef.Pkg -> "pkg:${ref.id}" + (if (ref.path.isEmpty()) "" else "." + ref.path.replace('/', '.'))
        is ModuleRef.Plain -> if (scope == null) ref.path.replace('/', '.') else "pkg:$scope." + ref.path.replace('/', '.')
    }

    private fun load(scope: String?, ref: ModuleRef, key: String): LuaValue = when (ref) {
        is ModuleRef.Core -> {
            val module = vfs.resolveCore(ref.name)
                ?: throw LuaError("Built-in module 'core:${ref.name}' not found. Available: ${vfs.builtinNames.joinToString(", ")}")
            run(key, module, luaState.globals)
        }

        is ModuleRef.Ext -> error("ext modules are resolved before the cache")

        is ModuleRef.Plain -> {
            if (scope == null) {
                val module = vfs.resolvePlain(ref.path) ?: throw LuaError("module '${ref.path.replace('/', '.')}' not found")
                run(key, module, luaState.globals)
            } else {
                val plan = packages ?: throw LuaError("module '${ref.path.replace('/', '.')}' not found")
                val dir = plan.manifests.getValue(scope).dir
                val module = vfs.resolveInPackage(scope, dir, ref.path)
                    ?: throw LuaError("module '${ref.path.replace('/', '.')}' not found in package '$scope'")
                run(key, module, envFor(scope))
            }
        }

        is ModuleRef.Pkg -> loadPackageModule(ref)
    }

    private fun loadPackageModule(ref: ModuleRef.Pkg): LuaValue {
        val plan = packages ?: throw LuaError("package '${ref.id}' is not installed")
        plan.failure(ref.id)?.let { throw LuaError("package '${ref.id}' is not available: $it") }
        val manifest = plan.manifests[ref.id] ?: throw LuaError("package '${ref.id}' is not installed")
        val key = "pkg:${ref.id}" + (if (ref.path.isEmpty()) "" else "." + ref.path.replace('/', '.'))
        if (ref.path.isNotEmpty()) {
            val module = vfs.resolveInPackage(ref.id, manifest.dir, ref.path)
                ?: throw LuaError("module '${ref.path.replace('/', '.')}' not found in package '${ref.id}'")
            return run(key, module, envFor(ref.id))
        }
        val module = vfs.resolveEntry(ref.id, manifest.dir, manifest.entry)
            ?: throw LuaError("entry '${manifest.entry}' of package '${ref.id}' not found")
        try {
            return run(key, module, envFor(ref.id))
        } catch (e: LuaError) {
            // Everything that listed this package in `depends` is skipped; the eager loader reports them as it reaches them.
            plan.fail(ref.id, e.message ?: e.toString())
            ScriptErrors.error("package '${ref.id}'", e)
            throw e
        }
    }

    private fun run(name: String, module: Vfs.Module, env: LuaValue): LuaValue {
        try {
            val moduleF = luaState.load(ByteArrayInputStream(module.content), module.sourceName, "t", env)
            val result = moduleF.call()
            return if (result.isnil()) LuaValue.TRUE else result
        } catch (e: LuaError) {
            PxIgnis.logger.error("error loading module: '$name': ${e.message}", e)
            throw LuaError("error loading module '$name': ${e.message}")
        } catch (t: Throwable) {
            PxIgnis.logger.error("unexpected error loading module: '$name'", t)
            throw LuaError("unexpected error loading module '$name': ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    override fun call(name: LuaValue, env: LuaValue): LuaValue {
        env.set("package", packageTable)
        env.set("require", Require(this, null))

        return NIL
    }
}
