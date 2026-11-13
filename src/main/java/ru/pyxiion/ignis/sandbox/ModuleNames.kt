package ru.pyxiion.ignis.sandbox

import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** What a `require` string means. Namespaces are a closed set: `core:`, `pkg:`, `ext:`. */
sealed interface ModuleRef {
    /** `core:name`, a built-in module. */
    data class Core(val name: String) : ModuleRef

    /** `pkg:id` (the entry, [path] empty) or `pkg:id.a.b` ([path] is `a/b`). */
    data class Pkg(val id: String, val path: String) : ModuleRef

    /** `ext:id` ([sub] null) or `ext:id.sub`. */
    data class Ext(val id: String, val sub: String?) : ModuleRef

    /** A plain module, `a.b` for `a/b.lua`; [path] uses `/`. Resolved against the script root, or the calling package. */
    data class Plain(val path: String) : ModuleRef
}

object ModuleNames {
    val NAMESPACES = listOf("core:", "pkg:", "ext:")
    private val ID = Regex("[a-z0-9_-]+")

    fun isValidId(id: String): Boolean = ID.matches(id)

    /** Parses a `require` argument. Throws [IllegalArgumentException] with a user-facing message. */
    fun parse(name: String): ModuleRef {
        val colon = name.indexOf(':')
        if (colon < 0) return ModuleRef.Plain(segments(name, name).joinToString("/"))
        val ns = name.substring(0, colon)
        val rest = name.substring(colon + 1)
        return when (ns) {
            "core" -> {
                require(rest.isNotEmpty()) { "empty module name in require '$name'" }
                ModuleRef.Core(rest)
            }
            "pkg" -> {
                val parts = segments(rest, name)
                requireId(parts[0], name)
                ModuleRef.Pkg(parts[0], parts.drop(1).joinToString("/"))
            }
            "ext" -> {
                val parts = segments(rest, name)
                requireId(parts[0], name)
                ModuleRef.Ext(parts[0], parts.drop(1).joinToString(".").ifEmpty { null })
            }
            else -> throw IllegalArgumentException(
                "unknown namespace '$ns:' in require '$name' (known: ${NAMESPACES.joinToString(", ")}); " +
                    "a script folder is required as '${name.replace(':', '.')}'"
            )
        }
    }

    private fun requireId(id: String, name: String) {
        require(isValidId(id)) { "bad id '$id' in require '$name': ids are [a-z0-9_-]+" }
    }

    private fun segments(rest: String, name: String): List<String> {
        val parts = rest.split('.')
        require(parts.none { it.isEmpty() || '/' in it || '\\' in it }) {
            "bad module name '$name': use '.' between segments, no empty segments and no '/'"
        }
        return parts
    }
}

/** Path confinement shared by modules and plugin file lookups. */
object PathSandbox {
    /**
     * [rel] resolved against [root] (a plain relative path with `/`) must stay inside [root] with symlinks resolved.
     * Returns the real path of an existing entry; throws [IOException] when it does not exist or leaves [root].
     */
    fun resolveExisting(root: Path, rel: String): Path {
        val realRoot = root.toRealPath()
        val resolved = try {
            realRoot.resolve(rel).toRealPath()
        } catch (e: InvalidPathException) {
            throw IOException("invalid path '$rel'")
        }
        if (!resolved.startsWith(realRoot)) throw IOException("path '$rel' leaves ${root.fileName}")
        return resolved
    }

    /** Like [resolveExisting] but a regular file is required. */
    fun resolveFile(root: Path, rel: String): Path {
        val p = resolveExisting(root, rel)
        if (!Files.isRegularFile(p)) throw IOException("'$rel' is not a file")
        return p
    }
}
