package ru.pyxiion.ignis.storage

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class JsonBackend(private val root: Path) : DataBackend {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val type = object : TypeToken<Map<String, Any?>>() {}.type

    override fun load(key: String): Map<String, Any?> {
        val file = resolve(key)
        if (!Files.exists(file)) return linkedMapOf()
        return try {
            Files.newBufferedReader(file).use { reader ->
                @Suppress("UNCHECKED_CAST")
                fixIntegers((gson.fromJson(reader, type) as? Map<String, Any?>) ?: linkedMapOf())
            }
        } catch (_: Exception) {
            logger.warn("Fauked to read: $file. The data will be emptied.")
            linkedMapOf()
        }
    }

    companion object {
        private val logger: Logger = LoggerFactory.getLogger("PxRP/JsonBackend")
    }

    @Suppress("UNCHECKED_CAST")
    private fun fixIntegers(map: Map<String, Any?>): Map<String, Any?> {
        return map.mapValues { entry ->
            val v = entry.value
            when (v) {
                is Double -> if (v == v.toLong().toDouble()) v.toLong() else v
                is Map<*, *> -> fixIntegers(v as Map<String, Any?>)
                is List<*> -> v.map { e ->
                    if (e is Double && e == e.toLong().toDouble()) e.toLong()
                    else e
                }
                else -> v
            }
        }
    }

    override fun save(key: String, data: Map<String, Any?>) {
        val file = resolve(key)
        Files.createDirectories(file.parent)
        val temp = file.resolveSibling("${file.fileName}.tmp")
        try {
            Files.newBufferedWriter(temp).use { writer ->
                gson.toJson(data, writer)
            }
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            try { Files.deleteIfExists(temp) } catch (_: Exception) {}
            throw e
        }
    }

    override fun close() {}

    private fun resolve(key: String): Path {
        return if (key == "__global__") {
            root.resolve("global.json")
        } else {
            root.resolve("players").resolve("$key.json")
        }
    }
}
