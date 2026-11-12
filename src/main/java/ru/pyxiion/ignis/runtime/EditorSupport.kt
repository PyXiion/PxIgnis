package ru.pyxiion.ignis.runtime

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.name

/**
 * Sets up `config/ignis` as a Lua Language Server workspace, so an editor with the Lua extension
 * (VS Code "Lua" by sumneko, or any LuaLS client) completes the PxIgnis API and flags typos:
 *
 * - `.types/` gets the `---@meta` stubs bundled in the jar (`lua-types/`); it is rewritten on every start so it
 *   always matches the installed version.
 * - `.luarc.json` is created once and then left alone, so server owners can edit it.
 */
object EditorSupport {
    private val logger = LoggerFactory.getLogger("pxignis")

    const val TYPES_DIR = ".types"

    val LUARC = """
        {
          "${'$'}schema": "https://raw.githubusercontent.com/LuaLS/vscode-lua/master/setting/schema.json",
          "runtime.version": "Lua 5.2",
          "runtime.builtin": { "io": "disable", "os": "disable", "debug": "disable" },
          "workspace.library": ["$TYPES_DIR"],
          "workspace.ignoreDir": ["storage"]
        }
    """.trimIndent() + "\n"

    /** [bundledTypes] is the `lua-types` folder inside the mod jar (null when it is missing, e.g. in tests). */
    fun install(ignisDir: Path, bundledTypes: Path?) {
        try {
            Files.createDirectories(ignisDir)
            if (bundledTypes != null) copyTypes(bundledTypes, ignisDir.resolve(TYPES_DIR))
            val luarc = ignisDir.resolve(".luarc.json")
            if (!luarc.exists()) Files.writeString(luarc, LUARC)
        } catch (e: Exception) {
            logger.warn("Could not set up editor support in {}: {}", ignisDir, e.toString())
        }
    }

    private fun copyTypes(from: Path, to: Path) {
        Files.createDirectories(to)
        Files.list(to).use { old -> old.filter { it.extension == "lua" }.forEach(Files::delete) }
        Files.list(from).use { files ->
            files.filter { it.extension == "lua" }.forEach {
                Files.copy(it, to.resolve(it.name), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}
