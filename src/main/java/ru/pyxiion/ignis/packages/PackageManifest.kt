package ru.pyxiion.ignis.packages

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.loader.api.Version
import net.fabricmc.loader.api.metadata.version.VersionPredicate
import ru.pyxiion.ignis.sandbox.ModuleNames
import java.nio.file.Path

/** `pkg:` or `ext:` dependency with its semver range. */
data class Dependency(val kind: Kind, val id: String, val range: String, val optional: Boolean) {
    enum class Kind(val prefix: String) { PKG("pkg"), EXT("ext") }

    val ref: String get() = "${kind.prefix}:$id"

    /** Throws nothing: false when [version] is not accepted by [range]. */
    fun accepts(version: String): Boolean = try {
        VersionPredicate.parse(range).test(Version.parse(version))
    } catch (_: Exception) {
        false
    }
}

/** A parsed `ignis.json`. [dir] is the package folder. */
data class PackageManifest(
    val id: String,
    val version: String,
    val entry: String,
    val dependencies: List<Dependency>,
    val dir: Path,
) {
    val depends: List<Dependency> get() = dependencies.filter { !it.optional }

    companion object {
        const val FILE = "ignis.json"
        const val DEFAULT_ENTRY = "init.lua"

        /** Throws [IllegalArgumentException] with a message for the log when the manifest is invalid. */
        fun parse(text: String, dir: Path): PackageManifest {
            val root = try {
                JsonParser.parseString(text).asJsonObject
            } catch (e: Exception) {
                throw IllegalArgumentException("$FILE is not a valid JSON object: ${e.message}")
            }
            val id = string(root, "id") ?: throw IllegalArgumentException("$FILE: 'id' is required")
            require(ModuleNames.isValidId(id)) { "$FILE: id '$id' is invalid, ids are [a-z0-9_-]+" }
            val version = string(root, "version") ?: throw IllegalArgumentException("$FILE: 'version' is required")
            try {
                Version.parse(version)
            } catch (e: Exception) {
                throw IllegalArgumentException("$FILE: version '$version' is not a valid version: ${e.message}")
            }
            val entry = string(root, "entry") ?: DEFAULT_ENTRY
            require(entry.endsWith(".lua") && !entry.startsWith("/") && ".." !in entry.split('/', '\\')) {
                "$FILE: entry '$entry' must be a relative .lua file inside the package"
            }
            val deps = deps(root, "depends", false) + deps(root, "optional", true)
            val seen = HashSet<String>()
            for (d in deps) require(seen.add(d.ref)) { "$FILE: '${d.ref}' is listed twice" }
            return PackageManifest(id, version, entry, deps, dir)
        }

        private fun string(o: JsonObject, key: String): String? {
            val e: JsonElement = o.get(key) ?: return null
            require(e.isJsonPrimitive && e.asJsonPrimitive.isString) { "$FILE: '$key' must be a string" }
            return e.asString
        }

        private fun deps(root: JsonObject, key: String, optional: Boolean): List<Dependency> {
            val e = root.get(key) ?: return emptyList()
            require(e.isJsonObject) { "$FILE: '$key' must be an object of \"pkg:id\" / \"ext:id\" to a version range" }
            return e.asJsonObject.entrySet().map { (name, range) ->
                val kind = Dependency.Kind.entries.firstOrNull { name.startsWith(it.prefix + ":") }
                    ?: throw IllegalArgumentException("$FILE: '$key' entry '$name' must start with pkg: or ext:")
                val id = name.substring(kind.prefix.length + 1)
                require(ModuleNames.isValidId(id)) { "$FILE: '$key' entry '$name' has an invalid id" }
                require(range.isJsonPrimitive && range.asJsonPrimitive.isString) { "$FILE: '$key' range of '$name' must be a string" }
                try {
                    VersionPredicate.parse(range.asString)
                } catch (ex: Exception) {
                    throw IllegalArgumentException("$FILE: '$key' range '${range.asString}' of '$name' is invalid: ${ex.message}")
                }
                Dependency(kind, id, range.asString, optional)
            }
        }
    }
}
