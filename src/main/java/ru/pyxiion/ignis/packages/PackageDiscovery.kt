package ru.pyxiion.ignis.packages

import java.nio.file.Files
import java.nio.file.Path

/** Reads `<root>/packages/<folder>/ignis.json` of every script root and builds the [PackagePlan]. */
object PackageDiscovery {
    const val DIR = "packages"

    class Result(val plan: PackagePlan, val problems: List<String>)

    /**
     * Folders without `ignis.json` are ignored silently only when they hold no files at all; an invalid manifest
     * is a problem and the folder is skipped (it cannot be depended on, so dependents report it as not installed).
     */
    fun scan(roots: List<Path>, plugins: Map<String, String>): Result {
        val manifests = mutableListOf<PackageManifest>()
        val problems = mutableListOf<String>()
        val pathsById = linkedMapOf<String, MutableList<String>>()

        for (root in roots) {
            val packages = root.resolve(DIR)
            if (!Files.isDirectory(packages)) continue
            val folders = Files.list(packages).use { s -> s.filter { Files.isDirectory(it) && !it.fileName.toString().startsWith(".") }.sorted().toList() }
            for (dir in folders) {
                val file = dir.resolve(PackageManifest.FILE)
                if (!Files.isRegularFile(file)) {
                    problems += "$dir: no ${PackageManifest.FILE}, folder skipped"
                    continue
                }
                try {
                    val m = PackageManifest.parse(Files.readString(file), dir)
                    pathsById.getOrPut(m.id) { mutableListOf() } += dir.toString()
                    manifests += m
                } catch (e: Exception) {
                    problems += "$dir: ${e.message}"
                }
            }
        }

        val duplicates = pathsById.filterValues { it.size > 1 }
        val unique = manifests.filter { it.id !in duplicates }
        // A duplicated id stays in the plan as a failure so that dependents are skipped with a clear reason.
        return Result(PackagePlan.build(unique, plugins, duplicates), problems)
    }
}
