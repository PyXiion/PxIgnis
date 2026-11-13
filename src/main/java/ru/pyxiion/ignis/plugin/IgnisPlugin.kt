package ru.pyxiion.ignis.plugin

import kotlinx.coroutines.CoroutineScope
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3
import ru.pyxiion.ignis.api.util.BuiltMeta
import ru.pyxiion.ignis.runtime.ScriptErrors
import ru.pyxiion.luanova.vm2.LuaFunction
import ru.pyxiion.luanova.vm2.LuaTable
import ru.pyxiion.luanova.vm2.LuaValue
import ru.pyxiion.luanova.vm2.Varargs
import net.minecraft.server.level.ServerPlayer
import java.nio.file.Path

/**
 * A PxIgnis plugin: a Fabric mod with an entrypoint under the key `pxignis` that implements this interface, and
 * `depends: { "pxignis": "~0.N" }` (there is no API version handshake; the pin is the contract).
 * Design: `docs/plugins.md`.
 */
interface IgnisPlugin {
    /** `[a-z0-9_-]+`: the namespace of modules (`ext:<id>`), events (`<id>:*`) and extension methods (`<id>_*`). */
    val id: String

    /** Once per server start, before the first script load. */
    fun onServerStart(ctx: PluginContext) {}

    /**
     * On every [ru.pyxiion.ignis.runtime.ScriptEnvironment.rebuild], before scripts run. The Lua state is rebuilt on
     * each reload: never keep a LuaState or Lua values across calls, release what is allocated for this environment
     * through [PluginEnv.onClose]. An exception is reported for this plugin only; the other plugins and the scripts
     * still load.
     */
    fun onEnvironment(env: PluginEnv)

    /** Server stop, after the last [PluginEnv.onClose] ran. */
    fun onServerStop() {}
}

interface PluginContext {
    val server: MinecraftServer

    /** The mod scope; cancelled at server stop. Use it for background work (decoding, I/O). */
    val scope: CoroutineScope

    /** The script roots (`script_roots`, default `config/ignis`). */
    val scriptRoots: List<Path>

    val errors: ScriptErrors
}

/** What a plugin gets for one Lua environment. Valid until its [onClose] blocks have run. */
interface PluginEnv {
    val id: String
    val server: MinecraftServer
    val scope: CoroutineScope
    val convert: LuaConvert
    val errors: ScriptErrors

    /**
     * `require("ext:<id>")` for a null [name] (the main module) or `require("ext:<id>.<name>")`. [build] runs on the
     * first `require` in this environment. The function values of the returned table (nested plain tables too) are
     * called with the calling package known to [file]; objects with metatables are not wrapped.
     */
    fun module(name: String? = null, build: () -> LuaValue)

    /**
     * A fresh metatable filled from [meta], to build your own wrapper objects with [newObject]. The same
     * `__pxrp_object` / `__pxrp_type` layout as the core wrappers.
     */
    fun metaTable(meta: BuiltMeta): LuaTable

    /** A wrapper table `{ __pxrp_type = type, __pxrp_object = obj }` with metatable [meta]. */
    fun newObject(meta: LuaTable, type: String, obj: Any): LuaTable

    /**
     * Adds the method `<id>_<name>` to the core wrapper metatable [type] (`player`, `entity`, `world`, `item`, `vec`,
     * ... see `MetaTableRegistry`). It is applied to the metatable of exactly that type: `player` does not inherit
     * what is added to `entity`. A name already present is a load error ([IllegalStateException]). Call it from
     * [IgnisPlugin.onEnvironment]: the metatables are rebuilt on every reload.
     */
    fun extend(type: String, name: String, fn: LuaValue)

    /**
     * Posts the custom event `<id>:<name>` to the global bus; any other prefix is an [IllegalArgumentException].
     * Server thread only. Returns false when a handler cancelled it.
     */
    fun emit(event: String, vararg args: LuaValue): Boolean

    /** Runs on reload (before the new state is built) and on server stop. One failing block does not stop the others. */
    fun onClose(block: () -> Unit)

    /**
     * Resolves [path] against the folder of the package whose Lua code is calling the current module function, or
     * against the first script root for loose scripts. The result is an existing path inside that folder (symlinks
     * resolved); otherwise this throws [java.io.IOException]. Call it synchronously from the module function.
     */
    fun file(path: String): Path

    /** Queues [block] for the server thread (drained every tick). Safe from any thread. */
    fun runOnServer(block: () -> Unit)

    /** A Lua function whose Kotlin errors (including `IllegalArgumentException`) become Lua errors. */
    fun function(body: (Varargs) -> Varargs): LuaFunction

    /**
     * A Lua function that runs [block] on [scope] and yields the calling coroutine until it finishes, then resumes
     * it on the server thread with the result. Called from the main chunk (not a coroutine) it raises
     * "cannot yield from main thread". Same semantics as the core `async` functions.
     */
    fun suspendFunction(block: suspend (Varargs) -> Varargs): LuaFunction
}

/**
 * The value boundary between Lua and Minecraft. Errors name the argument and the expected type in core's format:
 * `bad argument 'player': player expected, got nil`. [arg] is the name shown in the message.
 */
interface LuaConvert {
    fun toPlayer(v: LuaValue, arg: String = "player"): ServerPlayer
    fun toEntity(v: LuaValue, arg: String = "entity"): Entity
    fun toLevel(v: LuaValue, arg: String = "world"): ServerLevel

    /** A `vec`, `{x=,y=,z=}` or `{x,y,z}`. */
    fun toVec(v: LuaValue, arg: String = "pos"): Vec3

    /** A copy of the wrapped stack. */
    fun toItem(v: LuaValue, arg: String = "item"): ItemStack

    fun wrap(player: ServerPlayer): LuaValue
    fun wrap(entity: Entity): LuaValue
    fun wrap(level: ServerLevel): LuaValue
    fun wrap(vec: Vec3): LuaValue
    fun wrap(stack: ItemStack): LuaValue

    /** Reads an options table argument; [v] nil means "no options". [fn] prefixes the errors, e.g. `voice.emitter`. */
    fun options(v: LuaValue, fn: String): Opts
}

/** Typed reads of an options table. A wrong type is an error naming the key. */
interface Opts {
    fun has(key: String): Boolean
    fun float(key: String, default: Float): Float
    fun double(key: String, default: Double): Double
    fun int(key: String, default: Int): Int
    fun boolean(key: String, default: Boolean): Boolean
    fun string(key: String, default: String): String
    fun stringOrNull(key: String): String?
    fun entity(key: String): Entity?
    fun player(key: String): ServerPlayer?
    fun level(key: String): ServerLevel?
    fun vec(key: String): Vec3?
    fun function(key: String): LuaFunction?

    /** The array part of the table under [key] (empty when absent); a non-table value is an error. */
    fun list(key: String): List<LuaValue>

    /** An error for any key not in [known], listing the known ones. */
    fun rejectUnknown(vararg known: String)
}
