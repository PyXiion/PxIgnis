package ru.pyxiion.ignis.sandbox

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import ru.pyxiion.ignis.PxIgnis
import java.io.File
import java.io.IOException
import java.nio.file.Path

class Vfs(private val ignisDir: Path) {
    class Module(val content: ByteArray, val sourceName: String)

    private val builtins: Map<String, Module> = mapOf(
        "format" to Module(loadResource("/format.lua"), "@format.lua"),
        "simple" to Module(loadResource("/simple.lua"), "@simple.lua"),
        "chestgui" to Module(loadResource("/chestgui.lua"), "@chestgui.lua"),
        "items" to Module(loadResource("/items.lua"), "@items.lua"),
        "demo" to Module(loadResource("/demo.lua"), "@demo.lua"),
        "particles" to Module(loadResource("/particles.lua"), "@particles.lua"),
        "dialog" to Module(loadResource("/dialog.lua"), "@dialog.lua"),
    )

    /** Корень и проверяемый путь приводятся одним способом — через `toRealPath`. Нет каталога — нет файлов. */
    private fun realRoot(): Path? = try {
        ignisDir.toRealPath()
    } catch (e: IOException) {
        null
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
        val root = realRoot() ?: return null
        val file1 = safeFile("$relPath.lua")
        val file2 = safeFile("$relPath/init.lua")

        return when {
            file1 != null && file2 != null -> {
                logger.warn("Ambiguous module '$relPath': both $relPath.lua and $relPath/init.lua exist; preferring init.lua")
                Module(file2.readBytes(), "@${root.relativize(file2.toPath())}")
            }

            file1 != null -> Module(file1.readBytes(), "@${root.relativize(file1.toPath())}")
            file2 != null -> Module(file2.readBytes(), "@${root.relativize(file2.toPath())}")
            else -> null
        }
    }

    /** Обычный файл внутри `config/ignis` либо null. Выход за корень (в том числе симлинком) — null. */
    fun safeFile(relPath: String): File? {
        val root = realRoot() ?: return null
        val resolved = try {
            ignisDir.resolve(relPath).toRealPath()
        } catch (e: IOException) {
            return null
        } catch (e: java.nio.file.InvalidPathException) {
            return null
        }
        if (!resolved.startsWith(root)) return null
        val f = resolved.toFile()
        return if (f.isFile) f else null
    }

    private fun loadResource(path: String): ByteArray {
        return javaClass.getResourceAsStream(path)?.use { it.readAllBytes() }
            ?: error("Built-in resource $path not found in JAR")
    }

    companion object {
        private val logger: Logger = LoggerFactory.getLogger(PxIgnis.MOD_ID)
    }
}
