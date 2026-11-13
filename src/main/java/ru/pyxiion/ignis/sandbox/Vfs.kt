package ru.pyxiion.ignis.sandbox

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import ru.pyxiion.ignis.PxIgnis
import java.io.File
import java.io.IOException
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Module and file lookup for scripts, confined to [roots] (the configured script roots, in order).
 *
 * A module is looked up in the earlier root first: `<root>/?.lua` and `<root>/?/init.lua`. A path is accepted only
 * if, with symlinks resolved, it still lies inside the root it was resolved from.
 */
class Vfs(val roots: List<Path>) {
    class Module(val content: ByteArray, val sourceName: String)

    /** `package.path` as scripts see it: every root in order. Resolution itself goes through [resolve]. */
    val searchPath: String = roots.joinToString(";") { "$it/?.lua;$it/?/init.lua" }

    private val builtins: Map<String, Module> = mapOf(
        "format" to Module(loadResource("/format.lua"), "@format.lua"),
        "simple" to Module(loadResource("/simple.lua"), "@simple.lua"),
        "chestgui" to Module(loadResource("/chestgui.lua"), "@chestgui.lua"),
        "items" to Module(loadResource("/items.lua"), "@items.lua"),
        "demo" to Module(loadResource("/demo.lua"), "@demo.lua"),
        "particles" to Module(loadResource("/particles.lua"), "@particles.lua"),
        "dialog" to Module(loadResource("/dialog.lua"), "@dialog.lua"),
    )

    /** A built-in module, or null for an unknown name (the caller lists [builtinNames]). */
    fun resolveCore(name: String): Module? = builtins[name]

    val builtinNames: List<String> get() = builtins.keys.sorted()

    /** A plain module `a/b` of the script roots (`a/b.lua`, then `a/b/init.lua`), earlier root first. */
    fun resolvePlain(relPath: String): Module? = lookup(roots, relPath) { real, file -> "@${real.relativize(file)}" }

    /** A module `a/b` inside the package folder [dir]; the chunk name is `@pkg:<id>/a/b.lua`. */
    fun resolveInPackage(id: String, dir: Path, relPath: String): Module? =
        lookup(listOf(dir), relPath) { real, file -> "@pkg:$id/${real.relativize(file).joinToString("/")}" }

    /** The entry file of a package, confined to [dir]. Null when it does not exist. */
    fun resolveEntry(id: String, dir: Path, entry: String): Module? {
        val file = try {
            PathSandbox.resolveFile(dir, entry)
        } catch (e: IOException) {
            return null
        }
        return Module(java.nio.file.Files.readAllBytes(file), "@pkg:$id/$entry")
    }

    private fun lookup(searchRoots: List<Path>, relPath: String, chunkName: (Path, Path) -> String): Module? {
        for (root in searchRoots) {
            val real = realPath(root) ?: continue
            val file1 = safeFile(root, real, "$relPath.lua")
            val file2 = safeFile(root, real, "$relPath/init.lua")

            when {
                file1 != null && file2 != null -> {
                    logger.warn("Ambiguous module '$relPath': both $relPath.lua and $relPath/init.lua exist; preferring init.lua")
                    return Module(file2.readBytes(), chunkName(real, file2.toPath()))
                }

                file1 != null -> return Module(file1.readBytes(), chunkName(real, file1.toPath()))
                file2 != null -> return Module(file2.readBytes(), chunkName(real, file2.toPath()))
            }
        }
        return null
    }

    /** A regular file inside one of the roots (the first one that has it) or null. A path escaping its root is null. */
    fun safeFile(relPath: String): File? {
        for (root in roots) {
            val real = realPath(root) ?: continue
            safeFile(root, real, relPath)?.let { return it }
        }
        return null
    }

    /**
     * [relPath] resolved against [root], with symlinks resolved, must stay inside [realRoot]; otherwise null.
     */
    private fun safeFile(root: Path, realRoot: Path, relPath: String): File? {
        val resolved = try {
            root.resolve(relPath).toRealPath()
        } catch (e: IOException) {
            return null
        } catch (e: InvalidPathException) {
            return null
        }
        if (!resolved.startsWith(realRoot)) return null
        val f = resolved.toFile()
        return if (f.isFile) f else null
    }

    /**
     * The file to write for [relPath] inside [root] (one of [roots]), parent folders created. [relPath] must be a
     * plain relative path; with symlinks resolved, the parent folder and an already existing target must stay inside
     * the root, otherwise this throws [IOException]. A root that does not exist yet is created.
     */
    fun writeTarget(root: Path, relPath: String): Path {
        java.nio.file.Files.createDirectories(root)
        val realRoot = root.toRealPath()
        val target = realRoot.resolve(relPath).normalize()
        if (!target.startsWith(realRoot) || target == realRoot) throw IOException("path '$relPath' leaves the script root")
        java.nio.file.Files.createDirectories(target.parent)
        if (!target.parent.toRealPath().startsWith(realRoot)) throw IOException("path '$relPath' leaves the script root")
        if (java.nio.file.Files.exists(target) && !target.toRealPath().startsWith(realRoot)) {
            throw IOException("path '$relPath' leaves the script root")
        }
        return target
    }

    private fun realPath(path: Path): Path? = try {
        path.toRealPath()
    } catch (e: IOException) {
        null
    }

    private fun loadResource(path: String): ByteArray {
        return javaClass.getResourceAsStream(path)?.use { it.readAllBytes() }
            ?: error("Built-in resource $path not found in JAR")
    }

    companion object {
        private val logger: Logger = LoggerFactory.getLogger(PxIgnis.MOD_ID)
    }
}
