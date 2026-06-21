package ru.pyxiion.ignis.sandbox

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.nio.file.Path

class Vfs(private val ignisDir: Path) {
    class Module(val content: ByteArray, val sourceName: String)

    private val builtins: Map<String, Module> = mapOf(
        "format" to Module(loadResource("/format.lua"), "@format.lua"),
        "simple" to Module(loadResource("/simple.lua"), "@simple.lua"),
        "chestgui" to Module(loadResource("/chestgui.lua"), "@chestgui.lua"),
        "demo" to Module(loadResource("/demo.lua"), "@demo.lua"),
    )

    private val canonicalIgnisDir: Path = try {
        ignisDir.toRealPath()
    } catch (e: IOException) {
        ignisDir.toAbsolutePath().normalize()
    }

    fun resolve(name: String): Module? {
        val parts = name.split(":", limit = 2)
        if (parts.size == 2) {
            val (ns, rest) = parts
            if (ns == "core") {
                return builtins[rest]
                    ?: error("Built-in module 'core:$rest' not found. Available: ${builtins.keys.sorted().joinToString(", ")}")
            }
            val relPath = "$ns/${rest.replace('.', '/')}"
            return resolveUserFile(relPath)
        }
        val relPath = name.replace('.', '/')
        return resolveUserFile(relPath)
    }

    private fun resolveUserFile(relPath: String): Module? {
        val file1 = safeFile("$relPath.lua")
        val file2 = safeFile("$relPath/init.lua")
        return when {
            file1 != null && file2 != null -> {
                logger.warn("Ambiguous module '$relPath': both $relPath.lua and $relPath/init.lua exist; preferring init.lua")
                Module(file2.readBytes(), "@${canonicalIgnisDir.relativize(file2.toPath())}")
            }

            file1 != null -> Module(file1.readBytes(), "@${canonicalIgnisDir.relativize(file1.toPath())}")
            file2 != null -> Module(file2.readBytes(), "@${canonicalIgnisDir.relativize(file2.toPath())}")
            else -> null
        }
    }

    private fun safeFile(relPath: String): File? {
        val resolved = try {
            ignisDir.resolve(relPath).toRealPath()
        } catch (e: IOException) {
            return null
        }
        if (!resolved.startsWith(canonicalIgnisDir)) return null
        val f = resolved.toFile()
        return if (f.isFile) f else null
    }

    private fun loadResource(path: String): ByteArray {
        return javaClass.getResourceAsStream(path)?.use { it.readAllBytes() }
            ?: error("Built-in resource $path not found in JAR")
    }

    companion object {
        private val logger: Logger = LoggerFactory.getLogger(Vfs::class.java)
    }
}
