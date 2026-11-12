package ru.pyxiion.ignis

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.SharedConstants
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.repository.FolderRepositorySource
import net.minecraft.server.packs.repository.PackSource
import net.minecraft.server.packs.repository.RepositorySource
import net.minecraft.world.level.validation.DirectoryValidator
import java.nio.file.Files
import java.nio.file.Path

/**
 * The opt-in pause-menu / quick-actions dialog `pxignis:menu`.
 *
 * Dialog registries and tags are synced at join, so they cannot change from scripts. Instead, while
 * `menu.enabled` in `config/pxignis.json` is true, a data pack is generated under `config/pxignis/datapacks`
 * and offered to the server's pack repository ([source], hooked in by `PackRepositoryMixin`). The pack adds the
 * dialog to `pause_screen_additions` and `quick_actions` (not replacing them). Its button sends the custom click
 * `pxignis:menu`, which scripts handle with `player.custom_click`. The config is read once per server start.
 */
object MenuPack {
    const val CLICK_ID = "pxignis:menu"
    private const val PACK = "pxignis_menu"

    data class Config(val enabled: Boolean, val title: String, val button: String)

    private val DEFAULTS = Config(enabled = false, title = "Server menu", button = "Open")

    private val configFile: Path get() = FabricLoader.getInstance().configDir.resolve("pxignis.json")
    private val packsDir: Path get() = FabricLoader.getInstance().configDir.resolve("pxignis/datapacks")

    /** Reads `config/pxignis.json`; a missing file is created with the defaults. */
    fun readConfig(): Config {
        val file = configFile
        if (!Files.exists(file)) {
            val menu = JsonObject().apply {
                addProperty("enabled", DEFAULTS.enabled)
                addProperty("title", DEFAULTS.title)
                addProperty("button", DEFAULTS.button)
            }
            Files.createDirectories(file.parent)
            Files.writeString(file, GSON.toJson(JsonObject().apply { add("menu", menu) }) + "\n")
            return DEFAULTS
        }
        val menu = try {
            JsonParser.parseString(Files.readString(file)).asJsonObject.getAsJsonObject("menu")
        } catch (e: Exception) {
            PxIgnis.logger.warn("config/pxignis.json is not valid, using defaults: ${e.message}")
            null
        } ?: return DEFAULTS
        return Config(
            enabled = menu.get("enabled")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: DEFAULTS.enabled,
            title = menu.get("title")?.takeIf { it.isJsonPrimitive }?.asString ?: DEFAULTS.title,
            button = menu.get("button")?.takeIf { it.isJsonPrimitive }?.asString ?: DEFAULTS.button,
        )
    }

    /**
     * A source for one pack repository, i.e. one server start. It reads the config now and keeps it: the dialog
     * registry is loaded at server start only, while `/reload` reloads the tags, so a pack switched on by `/reload`
     * would have tags pointing at a dialog that does not exist (logged as errors). Config changes apply on restart.
     * Offers the generated pack when the menu is enabled; offers nothing otherwise.
     */
    fun newSource(): RepositorySource {
        val config = readConfig()
        return RepositorySource { consumer -> offer(config, consumer) }
    }

    private fun offer(config: Config, consumer: java.util.function.Consumer<net.minecraft.server.packs.repository.Pack>) {
        if (!config.enabled) return
        try {
            writePack(packsDir.resolve(PACK), config)
            FolderRepositorySource(packsDir, PackType.SERVER_DATA, PackSource.BUILT_IN, DirectoryValidator { false })
                .loadPacks(consumer)
        } catch (e: Exception) {
            PxIgnis.logger.error("Could not set up the pxignis:menu data pack", e)
        }
    }

    private fun writePack(root: Path, config: Config) {
        val format = SharedConstants.getCurrentVersion().packVersion(PackType.SERVER_DATA)
        val version = JsonArray().apply { add(format.major()); add(format.minor()) }
        write(root.resolve("pack.mcmeta"), JsonObject().apply {
            add("pack", JsonObject().apply {
                addProperty("description", "PxIgnis pause menu")
                add("min_format", version)
                add("max_format", version)
            })
        })

        val button = JsonObject().apply {
            addProperty("label", config.button)
            add("action", JsonObject().apply {
                addProperty("type", "minecraft:dynamic/custom")
                addProperty("id", CLICK_ID)
            })
        }
        write(root.resolve("data/pxignis/dialog/menu.json"), JsonObject().apply {
            addProperty("type", "minecraft:multi_action")
            addProperty("title", config.title)
            addProperty("external_title", config.title)
            addProperty("after_action", "close")
            addProperty("columns", 1)
            add("actions", JsonArray().apply { add(button) })
        })

        val tag = JsonObject().apply {
            addProperty("replace", false)
            add("values", JsonArray().apply { add("pxignis:menu") })
        }
        write(root.resolve("data/minecraft/tags/dialog/pause_screen_additions.json"), tag)
        write(root.resolve("data/minecraft/tags/dialog/quick_actions.json"), tag)
    }

    private fun write(file: Path, json: JsonObject) {
        Files.createDirectories(file.parent)
        Files.writeString(file, GSON.toJson(json) + "\n")
    }

    private val GSON = GsonBuilder().setPrettyPrinting().create()
}
