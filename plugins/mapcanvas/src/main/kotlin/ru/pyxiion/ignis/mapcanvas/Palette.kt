package ru.pyxiion.ignis.mapcanvas

/** The vanilla map palette: byte = baseColorId * 4 + brightness; byte 0..3 is transparent. No Minecraft classes. */
object Palette {
    /** Base colour ids in MapColor order, with their RGB (brightness 2 = x255). */
    private val BASE: List<Triple<String, Int, Int>> = listOf(
        Triple("none", 0, 0),
        Triple("grass", 0x7FB238, 1), Triple("sand", 0xF7E9A3, 2), Triple("wool", 0xC7C7C7, 3),
        Triple("fire", 0xFF0000, 4), Triple("ice", 0xA0A0FF, 5), Triple("metal", 0xA7A7A7, 6),
        Triple("plant", 0x007C00, 7), Triple("snow", 0xFFFFFF, 8), Triple("clay", 0xA4A8B8, 9),
        Triple("dirt", 0x976D4D, 10), Triple("stone", 0x707070, 11), Triple("water", 0x4040FF, 12),
        Triple("wood", 0x8F7748, 13), Triple("quartz", 0xFFFCF5, 14),
        Triple("color_orange", 0xD87F33, 15), Triple("color_magenta", 0xB24CD8, 16),
        Triple("color_light_blue", 0x6699D8, 17), Triple("color_yellow", 0xE5E533, 18),
        Triple("color_light_green", 0x7FCC19, 19), Triple("color_pink", 0xF27FA5, 20),
        Triple("color_gray", 0x4C4C4C, 21), Triple("color_light_gray", 0x999999, 22),
        Triple("color_cyan", 0x4C7F99, 23), Triple("color_purple", 0x7F3FB2, 24),
        Triple("color_blue", 0x334CB2, 25), Triple("color_brown", 0x664C33, 26),
        Triple("color_green", 0x667F33, 27), Triple("color_red", 0x993333, 28),
        Triple("color_black", 0x191919, 29), Triple("gold", 0xFAEE4D, 30),
        Triple("diamond", 0x5CDBD5, 31), Triple("lapis", 0x4A80FF, 32), Triple("emerald", 0x00D93A, 33),
        Triple("podzol", 0x815631, 34), Triple("nether", 0x700200, 35),
        Triple("terracotta_white", 0xD1B1A1, 36), Triple("terracotta_orange", 0x9F5224, 37),
        Triple("terracotta_magenta", 0x95576C, 38), Triple("terracotta_light_blue", 0x706C8A, 39),
        Triple("terracotta_yellow", 0xBA8524, 40), Triple("terracotta_light_green", 0x677535, 41),
        Triple("terracotta_pink", 0xA04D4E, 42), Triple("terracotta_gray", 0x392923, 43),
        Triple("terracotta_light_gray", 0x876B62, 44), Triple("terracotta_cyan", 0x575C5C, 45),
        Triple("terracotta_purple", 0x7A4958, 46), Triple("terracotta_blue", 0x4C3E5C, 47),
        Triple("terracotta_brown", 0x4C3223, 48), Triple("terracotta_green", 0x4C522A, 49),
        Triple("terracotta_red", 0x8E3C2E, 50), Triple("terracotta_black", 0x251610, 51),
        Triple("crimson_nylium", 0xBD3031, 52), Triple("crimson_stem", 0x943F61, 53),
        Triple("crimson_hyphae", 0x5C191D, 54), Triple("warped_nylium", 0x167E86, 55),
        Triple("warped_stem", 0x3A8E8C, 56), Triple("warped_hyphae", 0x562C3E, 57),
        Triple("warped_wart_block", 0x14B485, 58), Triple("deepslate", 0x646464, 59),
        Triple("raw_iron", 0xD8AF93, 60), Triple("glow_lichen", 0x7FA796, 61),
    )

    /** Brightness id (vanilla order) to multiplier out of 255. */
    private val MULT = intArrayOf(180, 220, 255, 135)

    private val ids: Map<String, Int>

    init {
        val m = HashMap<String, Int>()
        BASE.forEachIndexed { i, (name, _, _) -> m[name] = i }
        // short aliases for the plain colours and their common synonyms
        for ((alias, full) in listOf(
            "orange" to "color_orange", "magenta" to "color_magenta", "light_blue" to "color_light_blue",
            "yellow" to "color_yellow", "light_green" to "color_light_green", "pink" to "color_pink",
            "gray" to "color_gray", "grey" to "color_gray", "light_gray" to "color_light_gray",
            "cyan" to "color_cyan", "purple" to "color_purple", "blue" to "color_blue",
            "brown" to "color_brown", "green" to "color_green", "red" to "color_red",
            "black" to "color_black", "white" to "snow",
        )) m.putIfAbsent(alias, m.getValue(full))
        ids = m
    }

    fun byId(base: Int, shade: Int): Int {
        require(base in 0 until BASE.size) { "base colour $base out of range" }
        require(shade in 0..3) { "shade must be 0..3, got $shade" }
        return base * 4 + shade
    }

    /** Palette byte for a MapColor [name] (case-insensitive) and brightness [shade] 0..3 (vanilla ids: 0 low, 1 normal, 2 high, 3 lowest). */
    fun color(name: String, shade: Int): Int {
        val base = ids[name.lowercase()] ?: throw IllegalArgumentException("unknown map colour '$name'")
        return byId(base, shade)
    }

    private val rgbTable: IntArray = IntArray(BASE.size * 4) { i ->
        val (_, rgb, _) = BASE[i / 4]
        val m = MULT[i % 4]
        val r = ((rgb shr 16) and 0xFF) * m / 255
        val g = ((rgb shr 8) and 0xFF) * m / 255
        val b = (rgb and 0xFF) * m / 255
        (r shl 16) or (g shl 8) or b
    }

    /** The palette byte whose colour is nearest to (r, g, b), each 0..255 (redmean distance). Never transparent. */
    fun rgb(r: Int, g: Int, b: Int): Int {
        require(r in 0..255 && g in 0..255 && b in 0..255) { "rgb components must be 0..255" }
        var best = 4
        var bestD = Long.MAX_VALUE
        for (i in 4 until rgbTable.size) {
            val c = rgbTable[i]
            val pr = (c shr 16) and 0xFF
            val dr = (r - pr).toLong()
            val dg = (g - ((c shr 8) and 0xFF)).toLong()
            val db = (b - (c and 0xFF)).toLong()
            val rm = (r + pr) / 2
            val d = (512 + rm) * dr * dr + 1024 * dg * dg + (767 - rm) * db * db
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        return best
    }

    fun rgb(packed: Int): Int = rgb((packed shr 16) and 0xFF, (packed shr 8) and 0xFF, packed and 0xFF)
}
