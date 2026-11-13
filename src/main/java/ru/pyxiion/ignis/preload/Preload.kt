package ru.pyxiion.ignis.preload

import net.fabricmc.loader.api.FabricLoader
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaState
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import ru.pyxiion.ignis.PxIgnis
import ru.pyxiion.ignis.luaFunction
import ru.pyxiion.ignis.runtime.ScriptErrors
import ru.pyxiion.ignis.runtime.ScriptRoots
import ru.pyxiion.ignis.runtime.installStdlib
import ru.pyxiion.ignis.runtime.installStrictGlobals
import ru.pyxiion.ignis.sandbox.Vfs
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Where a declaration came from: the script root and the preload file in it (path relative to the root). */
data class Source(val root: Path, val file: String) {
    override fun toString() = "$file ($root)"
}

data class Declaration(val spec: DimensionSpec, val source: Source)

/**
 * Preload scripts: `*.preload.lua` files at any depth of a script root. They run once, when the server's data pack
 * repository is first built (before any world exists), in their own Lua state per file with `preload`, `print`, the
 * plain Lua standard library and `require` from the roots, but no `mc`. `/ignis reload` does not run them again.
 *
 * What they declare is turned into data packs by [PreloadPack]. A file that fails is reported through
 * [ScriptErrors] and contributes nothing.
 */
object Preload {
    class Result(val declarations: List<Declaration>, private val fingerprints: Map<Source, String>) {
        val sources: List<Source> get() = fingerprints.keys.toList()

        fun changedSince(now: Map<Source, String>): Boolean = now != fingerprints
    }

    private val roots: List<Path>
        get() = FabricLoader.getInstance().let { ScriptRoots.configured(it.gameDir, it.configDir) }

    /** Runs the preload scripts on first access; later accesses return the same result. */
    val result: Result by lazy { run(roots, ::blockExists) }

    private fun blockExists(id: String): Boolean =
        Identifier.tryParse(id)?.let { BuiltInRegistries.BLOCK.containsKey(it) } == true

    /** Logs a warning when a preload file was added, removed or edited since [result] was computed. */
    fun warnIfChanged() {
        val current = fingerprints(ScriptRoots.preloadFiles(roots))
        if (result.changedSince(current)) {
            ScriptErrors.warn("preload", "preload scripts changed since the server started: restart required to apply them")
        }
    }

    private fun fingerprints(files: List<ScriptRoots.PreloadFile>): Map<Source, String> =
        files.associate { Source(it.root, it.relative) to hash(it.path) }

    private fun hash(file: Path): String = try {
        MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(file)).joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        "unreadable"
    }

    internal fun run(roots: List<Path>, blockExists: (String) -> Boolean): Result {
        val files = ScriptRoots.preloadFiles(roots)
        val declared = LinkedHashMap<String, Declaration>()
        for (file in files) {
            val source = Source(file.root, file.relative)
            val own = mutableListOf<Declaration>()
            try {
                runFile(file, source, roots, declared, own, blockExists)
            } catch (e: Throwable) {
                ScriptErrors.error("preload ${file.relative}", e)
                continue
            }
            own.forEach { declared[it.spec.id] = it }
            if (own.isNotEmpty()) PxIgnis.logger.info("preload {}: {}", source, own.joinToString { it.spec.id })
        }
        return Result(declared.values.toList(), fingerprints(files))
    }

    private fun runFile(
        file: ScriptRoots.PreloadFile,
        source: Source,
        roots: List<Path>,
        declared: Map<String, Declaration>,
        own: MutableList<Declaration>,
        blockExists: (String) -> Boolean,
    ) {
        val state = LuaState.builder().build()
        installStdlib(state, Vfs(roots))
        val table = LuaTable()
        table.set("dimension", luaFunction { id, options ->
            val spec = try {
                DimensionSpec.parse(id, options, blockExists)
            } catch (e: SpecError) {
                throw LuaError(e.message)
            }
            val clash = declared[spec.id] ?: own.firstOrNull { it.spec.id == spec.id }
            if (clash != null) {
                throw LuaError("dimension '${spec.id}' is declared twice: first in ${clash.source}, again in $source")
            }
            own += Declaration(spec, source)
            LuaValue.NIL
        })
        state.globals.set("preload", table)
        installStrictGlobals(state.globals)

        val previous = LuaState.current()
        LuaState.setCurrent(state)
        try {
            state.load(ByteArrayInputStream(Files.readAllBytes(file.path)), "@${file.relative}", "t", state.globals).call()
        } finally {
            LuaState.setCurrent(previous)
        }
    }
}
