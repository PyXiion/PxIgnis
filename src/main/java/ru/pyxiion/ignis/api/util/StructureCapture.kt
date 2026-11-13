package ru.pyxiion.ignis.api.util

import java.nio.file.Path

/** Pure parts of `world:captureStructure` and `structure:save`: bounds, limits, save path and root choice. */
object StructureCapture {
    /**
     * Most blocks one capture may hold. A saved structure lists every block (air included) with its position, and
     * `mc.loadStructureFile` reads at most 32 MiB of NBT, so 500 000 blocks always load back.
     */
    const val MAX_VOLUME = 500_000L

    class Box(val minX: Int, val minY: Int, val minZ: Int, val sizeX: Int, val sizeY: Int, val sizeZ: Int) {
        val volume: Long get() = sizeX.toLong() * sizeY * sizeZ
    }

    /** The box spanned by two inclusive corners given in any order. Throws [IllegalArgumentException] over [MAX_VOLUME] blocks. */
    fun box(x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int): Box {
        val sx = Math.abs(x1.toLong() - x2) + 1
        val sy = Math.abs(y1.toLong() - y2) + 1
        val sz = Math.abs(z1.toLong() - z2) + 1
        require(sx <= MAX_VOLUME && sy <= MAX_VOLUME && sz <= MAX_VOLUME && sx * sy * sz <= MAX_VOLUME) {
            "captureStructure: the area is ${sx}x${sy}x$sz blocks, more than the limit of $MAX_VOLUME"
        }
        val box = Box(minOf(x1, x2), minOf(y1, y2), minOf(z1, z2), sx.toInt(), sy.toInt(), sz.toInt())
        return box
    }

    /** A `.nbt` path relative to a root, `/`-separated, no `..`. Throws [IllegalArgumentException] otherwise. */
    fun savePath(path: String): String {
        val p = path.replace('\\', '/')
        require(p.isNotEmpty() && !p.startsWith("/") && !Regex("^[A-Za-z]:").containsMatchIn(p)) {
            "structure:save: '$path' must be a path relative to the script root"
        }
        val parts = p.split('/')
        require(parts.none { it == ".." || it == "." || it.isEmpty() }) { "structure:save: '$path' must not contain '..', '.' or empty parts" }
        require(parts.last().endsWith(".nbt") && parts.last().length > ".nbt".length) { "structure:save: '$path' must end with .nbt" }
        return parts.joinToString("/")
    }

    /**
     * The root `opts.root` names: a 1-based index into [roots], or a path equal to one of them (a relative path
     * resolves against [gameDir]). Null means the first root. Throws [IllegalArgumentException] for anything else.
     */
    fun selectRoot(roots: List<Path>, index: Int?, path: String?, gameDir: Path): Path {
        if (index != null) {
            require(index in 1..roots.size) { "structure:save: root index $index is out of range (1..${roots.size})" }
            return roots[index - 1]
        }
        if (path != null) {
            val wanted = Path.of(path).let { if (it.isAbsolute) it else gameDir.resolve(it) }.toAbsolutePath().normalize()
            return roots.firstOrNull { it == wanted }
                ?: throw IllegalArgumentException("structure:save: '$path' is not one of the script roots (${roots.joinToString()})")
        }
        return roots.first()
    }
}
