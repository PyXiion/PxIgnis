package ru.pyxiion.ignis.runtime

import net.fabricmc.loader.api.FabricLoader
import ru.pyxiion.ignis.PxIgnis
import java.io.FileOutputStream
import java.nio.file.Path

class ScriptLoader {
    val dir: Path = FabricLoader.getInstance().configDir.resolve("ignis")

    fun loadAll(): List<Pair<String, String>> {
        val ignisDir = dir

        if (ignisDir.toFile().isDirectory) {
            val files = ignisDir.toFile()
                .listFiles { f -> f.extension == "lua" }
                .orEmpty()
                .sortedBy { it.name }
            if (files.isNotEmpty()) {
                return files.map { it.name to it.readText() }
            }
        }

        ignisDir.toFile().mkdirs()
        val resource = this::class.java.getResourceAsStream("/demo.lua")
            ?: throw IllegalStateException("Default demo.lua not found in mod JAR")
        val targetFile = ignisDir.resolve("demo.lua").toFile()
        resource.copyTo(FileOutputStream(targetFile))
        PxIgnis.logger.info("Создан конфигурационный файл demo.lua в config/ignis/")

        return listOf("demo.lua" to targetFile.readText())
    }
}
