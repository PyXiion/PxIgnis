package ru.pyxiion.ignis.runtime

import com.google.gson.JsonParser
import ru.pyxiion.ignis.PxIgnis
import java.nio.file.Files
import java.nio.file.Path

/**
 * The folders scripts are loaded from. `script_roots` in `config/pxignis.json` lists them; without it there is the
 * one default root, `config/ignis`.
 *
 * A relative entry is resolved against the server run directory (the working directory of the server process), an
 * absolute one is taken as is. Roots are used in the listed order: top-level `.lua` files run root by root, and
 * `require` looks a module up in the earlier root first.
 */
object ScriptRoots {
    const val KEY = "script_roots"

    /** Files with this suffix, at any depth of a root, are preload scripts: run once at startup, never as normal scripts. */
    const val PRELOAD_SUFFIX = ".preload.lua"

    fun isPreload(fileName: String): Boolean = fileName.endsWith(PRELOAD_SUFFIX)

    /** The roots from `config/pxignis.json` [KEY] (or the default one), see [read] and [resolve]. */
    fun configured(gameDir: Path, configDir: Path): List<Path> = resolve(read(configDir.resolve("pxignis.json")), gameDir, configDir)

    /** A preload script: its [root] and the path relative to it, with `/` separators. */
    data class PreloadFile(val root: Path, val relative: String) {
        val path: Path get() = root.resolve(relative)
    }

    /** Preload scripts of all [roots]: roots in order, then sorted relative path. Missing roots are skipped. */
    fun preloadFiles(roots: List<Path>): List<PreloadFile> = roots.flatMap { root ->
        if (!Files.isDirectory(root)) return@flatMap emptyList()
        Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) && isPreload(it.fileName.toString()) }
                .map { root.relativize(it).joinToString("/") }
                .sorted().map { PreloadFile(root, it) }.toList()
        }
    }

    /** The default root, relative to the config directory. */
    const val DEFAULT = "ignis"

    /**
     * Reads [KEY] from the file [configFile] (`config/pxignis.json`). Returns null when the key is absent (the
     * default root is used) or malformed; a missing or broken file also yields null, as it does for the menu settings.
     */
    fun read(configFile: Path): List<String>? {
        if (!Files.exists(configFile)) return null
        val json = try {
            JsonParser.parseString(Files.readString(configFile)).asJsonObject
        } catch (e: Exception) {
            PxIgnis.logger.warn("config/pxignis.json is not valid, using the default script root: ${e.message}")
            return null
        }
        val value = json.get(KEY) ?: return null
        return try {
            value.asJsonArray.map { it.asString }
        } catch (e: Exception) {
            PxIgnis.logger.warn("$KEY in config/pxignis.json must be a list of paths, using the default script root")
            null
        }
    }

    /**
     * Resolves the configured [entries] to absolute, normalized roots, keeping the listed order and dropping
     * duplicates (the first occurrence wins). A null or empty list, and a list of blank entries, gives the default
     * root [configDir]/[DEFAULT]. Pure: no filesystem access.
     */
    fun resolve(entries: List<String>?, gameDir: Path, configDir: Path): List<Path> {
        val paths = entries.orEmpty()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { Path.of(it) }
            .map { if (it.isAbsolute) it else gameDir.resolve(it) }
            .map { it.toAbsolutePath().normalize() }
        if (paths.isEmpty()) return listOf(configDir.resolve(DEFAULT).toAbsolutePath().normalize())
        return paths.distinct()
    }
}
