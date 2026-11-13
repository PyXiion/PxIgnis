package ru.pyxiion.ignis.runtime

import ru.pyxiion.ignis.PxIgnis
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads the top-level `.lua` files of every root in [roots] (`script_roots` in `config/pxignis.json`, see
 * [ScriptRoots]): roots in the listed order, files sorted by name inside a root. A file name already taken by an
 * earlier root is skipped with a warning, so the first root wins. Subfolders are not scripts; they hold modules for
 * `require`. `*.preload.lua` files are not scripts (see [ScriptRoots.PRELOAD_SUFFIX]).
 *
 * When no root has any script, the first root gets the bundled `demo.lua`, so a fresh install has something to show.
 * A root that does not exist is skipped with a warning.
 */
class ScriptLoader(val roots: List<Path>) {
    fun loadAll(): List<Pair<String, String>> {
        val scripts = mutableListOf<Pair<String, String>>()
        val names = mutableSetOf<String>()
        val missing = mutableListOf<Path>()

        for (root in roots) {
            if (!Files.isDirectory(root)) {
                missing.add(root)
                continue
            }
            val files = root.toFile()
                .listFiles { f -> f.isFile && f.extension == "lua" && !ScriptRoots.isPreload(f.name) }
                .orEmpty()
                .sortedBy { it.name }
            for (file in files) {
                if (!names.add(file.name)) {
                    ScriptErrors.warn("script root", "${file.name} из $root пропущен: скрипт с таким именем уже загружен")
                    continue
                }
                scripts += file.name to file.readText()
            }
        }

        if (scripts.isNotEmpty()) {
            missing.forEach(::warnMissing)
            return scripts
        }

        val first = roots.first()
        Files.createDirectories(first)
        val resource = this::class.java.getResourceAsStream("/demo.lua")
            ?: throw IllegalStateException("Default demo.lua not found in mod JAR")
        val targetFile = first.resolve("demo.lua").toFile()
        resource.copyTo(FileOutputStream(targetFile))
        PxIgnis.logger.info("Создан конфигурационный файл demo.lua в {}", first)
        missing.filter { it != first }.forEach(::warnMissing)

        return listOf("demo.lua" to targetFile.readText())
    }

    private fun warnMissing(root: Path) {
        ScriptErrors.warn("script root", "папка скриптов $root не найдена, пропущена")
    }
}
