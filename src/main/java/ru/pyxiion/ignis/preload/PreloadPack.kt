package ru.pyxiion.ignis.preload

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.SharedConstants
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.repository.FolderRepositorySource
import net.minecraft.server.packs.repository.Pack
import net.minecraft.server.packs.repository.PackSource
import net.minecraft.server.packs.repository.RepositorySource
import net.minecraft.world.level.validation.DirectoryValidator
import ru.pyxiion.ignis.Compat
import ru.pyxiion.ignis.PxIgnis
import java.nio.file.Files
import java.nio.file.Path
import java.util.function.Consumer

/**
 * Offers the dimensions declared by preload scripts ([Preload]) as generated data packs, one pack per source file,
 * under `config/pxignis/preload-packs`. Hooked in by `PackRepositoryMixin`, like `MenuPack`: the packs come from
 * [PackSource.BUILT_IN], so vanilla enables them on a new world and also adds them to an existing world's
 * enabled list at its next start.
 */
object PreloadPack {
    private val packsDir: Path get() = FabricLoader.getInstance().configDir.resolve("pxignis/preload-packs")

    fun newSource(): RepositorySource = RepositorySource { consumer -> offer(consumer) }

    private fun offer(consumer: Consumer<Pack>) {
        try {
            val declarations = Preload.result.declarations
            deleteTree(packsDir)
            if (declarations.isEmpty()) return
            var n = 0
            for ((source, group) in declarations.groupBy { it.source }) {
                writePack(packsDir.resolve("pxignis_preload_${n++}_${slug(source.file)}"), source, group)
            }
            FolderRepositorySource(packsDir, PackType.SERVER_DATA, PackSource.BUILT_IN, DirectoryValidator { false })
                .loadPacks(consumer)
        } catch (e: Exception) {
            PxIgnis.logger.error("Could not set up the preload data packs", e)
        }
    }

    private fun slug(file: String): String =
        file.removeSuffix(".preload.lua").lowercase().replace(Regex("[^a-z0-9_.-]"), "_")

    private fun writePack(root: Path, source: Source, declarations: List<Declaration>) {
        val format = SharedConstants.getCurrentVersion().packVersion(PackType.SERVER_DATA)
        val version = JsonArray().apply { add(format.major()); add(format.minor()) }
        write(root.resolve("pack.mcmeta"), JsonObject().apply {
            add("pack", JsonObject().apply {
                addProperty("description", "PxIgnis preload: ${source.file}")
                add("min_format", version)
                add("max_format", version)
            })
        })
        for ((spec) in declarations) {
            val ns = spec.id.substringBefore(':')
            val path = spec.id.substringAfter(':')
            write(root.resolve("data/$ns/dimension/$path.json"), DimensionJson.dimension(spec))
            write(root.resolve("data/$ns/dimension_type/$path.json"), DimensionJson.dimensionType(spec, Compat.HAS_WORLD_CLOCKS))
        }
    }

    private fun write(file: Path, json: JsonObject) {
        Files.createDirectories(file.parent)
        Files.writeString(file, GSON.toJson(json) + "\n")
    }

    private fun deleteTree(dir: Path) {
        if (!Files.exists(dir)) return
        Files.walk(dir).use { s -> s.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    private val GSON = GsonBuilder().setPrettyPrinting().create()
}
