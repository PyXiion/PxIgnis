package ru.pyxiion.ignis.storage

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** JSON files under [root]: `global.json` and `players/<uuid>.json`. */
class JsonBackend(private val root: Path) {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    /**
     * An unreadable file is renamed to `<name>.corrupt-<millis>` (never overwritten) and treated as empty.
     */
    fun load(key: String): JsonObject {
        val file = resolve(key)
        if (!Files.exists(file)) return JsonObject()
        try {
            val element = Files.newBufferedReader(file).use { JsonParser.parseReader(it) }
            if (element.isJsonObject) return element.asJsonObject
            throw IllegalStateException("top-level JSON value is not an object")
        } catch (e: Exception) {
            val moved = file.resolveSibling("${file.fileName}.corrupt-${System.currentTimeMillis()}")
            try {
                Files.move(file, moved, StandardCopyOption.REPLACE_EXISTING)
                logger.error("Не удалось прочитать $file, файл перемещён в $moved, данные начаты заново", e)
            } catch (moveFailure: Exception) {
                e.addSuppressed(moveFailure)
                logger.error("Не удалось прочитать $file и переместить его; данные начаты заново", e)
            }
            return JsonObject()
        }
    }

    fun save(key: String, data: JsonObject) {
        val file = resolve(key)
        Files.createDirectories(file.parent)
        val temp = file.resolveSibling("${file.fileName}.tmp")
        try {
            Files.newBufferedWriter(temp).use { gson.toJson(data, it) }
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            try {
                Files.deleteIfExists(temp)
            } catch (_: Exception) {
            }
            throw e
        }
    }

    private fun resolve(key: String): Path =
        if (key == GLOBAL_KEY) root.resolve("global.json") else root.resolve("players").resolve("$key.json")

    companion object {
        const val GLOBAL_KEY = "__global__"
        private val logger: Logger = LoggerFactory.getLogger("PxRP/JsonBackend")
    }
}
