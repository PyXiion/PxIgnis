package ru.pyxiion.ignis.plugin

import kotlinx.coroutines.CoroutineScope
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.server.MinecraftServer
import ru.pyxiion.ignis.EventBus
import ru.pyxiion.ignis.PxIgnis
import ru.pyxiion.ignis.api.MetaTableRegistry
import ru.pyxiion.ignis.api.util.BuiltMeta
import ru.pyxiion.ignis.luaVarFunction
import ru.pyxiion.ignis.luaSuspendFunction
import ru.pyxiion.ignis.packages.PackagePlan
import ru.pyxiion.ignis.runtime.ScriptErrors
import ru.pyxiion.ignis.sandbox.ModuleNames
import ru.pyxiion.ignis.sandbox.PathSandbox
import ru.pyxiion.ignis.sandbox.PluginModules
import ru.pyxiion.ignis.wrapObject
import ru.pyxiion.luanova.vm2.LuaError
import ru.pyxiion.luanova.vm2.LuaFunction
import ru.pyxiion.luanova.vm2.LuaTable
import ru.pyxiion.luanova.vm2.LuaValue
import ru.pyxiion.luanova.vm2.Varargs
import ru.pyxiion.luanova.vm2.lib.LuaContinuableFunction
import ru.pyxiion.luanova.vm2.lib.VarArgFunction
import java.io.IOException
import java.nio.file.Path

/**
 * Discovers plugins (entrypoint key `pxignis`) and drives their lifecycle. One instance per server start;
 * [beginEnvironment] runs per script reload. [modules] is the `ext:` source of the Lua states.
 */
class PluginManager(
    private val server: MinecraftServer,
    private val scope: CoroutineScope,
    private val scriptRoots: List<Path>,
    private val eventBus: EventBus,
    private val dispatch: (Runnable) -> Unit,
    private val plugins: List<Loaded>,
) {
    class Loaded(val plugin: IgnisPlugin, val version: String, val types: Path?)

    /** Installed plugin id to its Fabric mod version. */
    val versions: Map<String, String> = plugins.associate { it.plugin.id to it.version }

    /** Plugin id to the `lua-types` folder in its jar, for plugins that ship one. */
    val typeStubs: Map<String, Path> = plugins.mapNotNull { p -> p.types?.let { p.plugin.id to it } }.toMap()

    private val envs = linkedMapOf<String, EnvImpl>()
    private var plan: PackagePlan? = null

    val modules = PluginModules { id, sub, caller ->
        val env = envs[id] ?: throw LuaError("plugin '$id' is not installed")
        env.require(sub, caller)
    }

    fun onServerStart() {
        val ctx = object : PluginContext {
            override val server = this@PluginManager.server
            override val scope = this@PluginManager.scope
            override val scriptRoots = this@PluginManager.scriptRoots
            override val errors = ScriptErrors
        }
        for (p in plugins) isolated(p.plugin.id, "onServerStart") { p.plugin.onServerStart(ctx) }
    }

    /**
     * Calls [IgnisPlugin.onEnvironment] of every plugin for the Lua environment that is being built. Call after the
     * previous environment was closed with [closeEnvironment].
     */
    fun beginEnvironment(plan: PackagePlan) {
        this.plan = plan
        envs.clear()
        for (p in plugins) {
            val env = EnvImpl(p.plugin.id)
            envs[p.plugin.id] = env
            try {
                p.plugin.onEnvironment(env)
            } catch (t: Throwable) {
                ScriptErrors.error("plugin '${p.plugin.id}'", t)
                // A half-built environment must not leave a callable module behind.
                env.close()
                envs.remove(p.plugin.id)
            }
        }
    }

    /** Runs the `onClose` blocks of the current environment, newest first. Isolated per plugin and per block. */
    fun closeEnvironment() {
        envs.values.toList().asReversed().forEach { it.close() }
        envs.clear()
    }

    fun onServerStop() {
        for (p in plugins.asReversed()) isolated(p.plugin.id, "onServerStop") { p.plugin.onServerStop() }
    }

    private inline fun isolated(id: String, what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            ScriptErrors.error("plugin '$id' ($what)", t)
        }
    }

    private inner class EnvImpl(override val id: String) : PluginEnv {
        override val server get() = this@PluginManager.server
        override val scope get() = this@PluginManager.scope
        override val convert: LuaConvert = LuaConvertImpl
        override val errors = ScriptErrors

        private val closers = mutableListOf<() -> Unit>()
        private val builders = linkedMapOf<String, () -> LuaValue>()
        private val built = HashMap<String, LuaValue>()
        private val views = HashMap<Pair<String?, String>, LuaValue>()
        private val caller = ThreadLocal<String?>()
        private var closed = false

        override fun module(name: String?, build: () -> LuaValue) {
            val key = name ?: ""
            require(name == null || name.split('.').none { it.isEmpty() }) { "bad module name '$name'" }
            check(key !in builders) { "plugin '$id' already registered module '${name ?: "(main)"}'" }
            builders[key] = build
        }

        /** The module [sub] as seen from package [callerPackage]. */
        fun require(sub: String?, callerPackage: String?): LuaValue {
            val key = sub ?: ""
            val builder = builders[key]
                ?: throw LuaError(if (sub == null) "plugin '$id' has no main module" else "plugin '$id' has no module '$sub'")
            return views.getOrPut(callerPackage to key) {
                val raw = built.getOrPut(key) {
                    try {
                        builder()
                    } catch (e: LuaError) {
                        throw e
                    } catch (t: Throwable) {
                        ScriptErrors.error("plugin '$id'", t)
                        throw LuaError("plugin '$id': module '${sub ?: id}' failed to build: ${t.message ?: t.javaClass.simpleName}")
                    }
                }
                view(raw, callerPackage, java.util.IdentityHashMap())
            }
        }

        /** A copy of [v] whose plain functions run with [scope] as the calling package. */
        private fun view(v: LuaValue, pkg: String?, seen: java.util.IdentityHashMap<LuaValue, LuaValue>): LuaValue = when {
            v is LuaTable && v.getmetatable() == null -> seen[v] ?: LuaTable().also { copy ->
                seen[v] = copy
                var k: LuaValue = LuaValue.NIL
                while (true) {
                    val n = v.next(k)
                    k = n.arg1()
                    if (k.isnil()) break
                    copy.rawset(k, view(n.arg(2), pkg, seen))
                }
            }
            v is LuaFunction && v !is LuaContinuableFunction<*> -> object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val prev = caller.get()
                    caller.set(pkg)
                    try {
                        return v.invoke(args)
                    } finally {
                        caller.set(prev)
                    }
                }
            }
            else -> v
        }

        override fun metaTable(meta: BuiltMeta): LuaTable = LuaTable().also { meta.apply(it) }

        override fun newObject(meta: LuaTable, type: String, obj: Any): LuaTable = wrapObject(meta, type, obj)

        override fun extend(type: String, name: String, fn: LuaValue) {
            require(fn.isfunction()) { "extend: '$name' must be a function" }
            require(name.isNotEmpty() && name.all { it.isLetterOrDigit() || it == '_' }) { "extend: bad method name '$name'" }
            val meta = MetaTableRegistry.get(type)
            val full = "${id}_$name"
            check(meta.rawget(full).isnil()) { "plugin '$id' cannot add '$full' to '$type': the name is taken" }
            meta.rawset(full, fn)
        }

        override fun emit(event: String, vararg args: LuaValue): Boolean {
            require(event.startsWith("$id:") && event.length > id.length + 1) { "plugin '$id' may only emit '$id:*' events, not '$event'" }
            check(server.isSameThread) { "emit must run on the server thread (use runOnServer)" }
            return eventBus.emit(event, LuaValue.varargsOf(args))
        }

        override fun onClose(block: () -> Unit) {
            check(!closed) { "environment of plugin '$id' is already closed" }
            closers += block
        }

        fun close() {
            if (closed) return
            closed = true
            for (c in closers.asReversed()) {
                try {
                    c()
                } catch (t: Throwable) {
                    ScriptErrors.error("plugin '$id' (onClose)", t)
                }
            }
            closers.clear()
        }

        override fun file(path: String): Path {
            val pkg = caller.get()
            if (pkg != null) {
                val dir = plan?.manifests?.get(pkg)?.dir ?: throw IOException("package '$pkg' is not installed")
                return PathSandbox.resolveExisting(dir, path)
            }
            for (root in scriptRoots) {
                try {
                    return PathSandbox.resolveExisting(root, path)
                } catch (_: IOException) {
                }
            }
            throw IOException("file '$path' not found in the script roots")
        }

        override fun runOnServer(block: () -> Unit) = dispatch(Runnable {
            try {
                block()
            } catch (t: Throwable) {
                ScriptErrors.error("plugin '$id'", t)
            }
        })

        override fun function(body: (Varargs) -> Varargs): LuaFunction = luaVarFunction(body)

        override fun suspendFunction(block: suspend (Varargs) -> Varargs): LuaFunction = luaSuspendFunction(scope, block)
    }

    companion object {
        /** Loads the `pxignis` entrypoints of all installed mods. A broken or duplicate plugin is reported and skipped. */
        fun discover(): List<Loaded> {
            val found = mutableListOf<Loaded>()
            for (c in FabricLoader.getInstance().getEntrypointContainers("pxignis", IgnisPlugin::class.java)) {
                val mod = c.provider.metadata.id
                try {
                    val p = c.entrypoint
                    when {
                        !ModuleNames.isValidId(p.id) -> ScriptErrors.error("plugin from mod '$mod'", "invalid plugin id '${p.id}': ids are [a-z0-9_-]+")
                        found.any { it.plugin.id == p.id } -> ScriptErrors.error("plugin from mod '$mod'", "duplicate plugin id '${p.id}'")
                        else -> found += Loaded(p, c.provider.metadata.version.friendlyString, c.provider.findPath("lua-types").orElse(null))
                    }
                } catch (t: Throwable) {
                    ScriptErrors.error("plugin from mod '$mod'", t)
                }
            }
            PxIgnis.logger.info("PxIgnis plugins: {}", found.joinToString { "${it.plugin.id} ${it.version}" }.ifEmpty { "none" })
            return found
        }
    }
}
