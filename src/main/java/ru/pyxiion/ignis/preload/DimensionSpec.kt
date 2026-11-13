package ru.pyxiion.ignis.preload

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue

/** A rejected `preload.dimension` call. The message is what the script author sees. */
class SpecError(message: String) : IllegalArgumentException(message)

/** One layer of a flat generator: a block and how many blocks thick it is. */
data class FlatLayer(val block: String, val thickness: Int)

sealed interface Generator {
    /** An empty world: one layer of air. */
    data object Void : Generator

    /** Layers bottom-up, starting at the dimension's `min_y`. */
    data class Flat(val layers: List<FlatLayer>) : Generator
}

/** A sky stuck at one time of day. */
enum class SkyTime(val option: String) { DAY("day"), NIGHT("night") }

data class Sky(
    /** The time the sky is stuck at; null follows the normal overworld day cycle. */
    val time: SkyTime?,
    val ambientLight: Float,
    val skylight: Boolean,
    val ceiling: Boolean,
)

/**
 * A validated `preload.dimension(id, options)` call. Build it with [parse] (from Lua) or the constructor plus
 * [validate] (pure Kotlin); [DimensionJson] turns it into the data pack files.
 */
data class DimensionSpec(
    val id: String,
    val generator: Generator = DEFAULT_GENERATOR,
    val biome: String = DEFAULT_BIOME,
    val minY: Int = DEFAULT_MIN_Y,
    val height: Int = DEFAULT_HEIGHT,
    val sky: Sky = DEFAULT_SKY,
) {
    /** Throws [SpecError] naming the offending field. [blockExists] tells whether a block id is registered. */
    fun validate(blockExists: (String) -> Boolean) {
        validateId(id)
        validateId(biome, "biome")
        if (minY % 16 != 0 || minY < MIN_Y_LIMIT || minY > MAX_Y_LIMIT) {
            throw SpecError("min_y must be a multiple of 16 between $MIN_Y_LIMIT and $MAX_Y_LIMIT, got $minY")
        }
        if (height % 16 != 0 || height < 16 || height > HEIGHT_LIMIT) {
            throw SpecError("height must be a multiple of 16 between 16 and $HEIGHT_LIMIT, got $height")
        }
        if (minY + height > MAX_Y_LIMIT + 1) {
            throw SpecError("min_y + height must not exceed ${MAX_Y_LIMIT + 1}, got ${minY + height}")
        }
        if (sky.ambientLight.isNaN() || sky.ambientLight < 0f || sky.ambientLight > 1f) {
            throw SpecError("sky.ambient_light must be between 0 and 1, got ${sky.ambientLight}")
        }
        val flat = generator as? Generator.Flat ?: return
        if (flat.layers.isEmpty()) throw SpecError("generator.flat needs at least one layer")
        var total = 0
        for ((block, thickness) in flat.layers) {
            validateId(block, "flat layer block")
            if (!blockExists(block)) throw SpecError("flat layer block '$block' does not exist")
            if (thickness < 1) throw SpecError("flat layer thickness must be at least 1, got $thickness for '$block'")
            total += thickness
        }
        if (total > height) throw SpecError("flat layers are $total blocks thick in total, more than height ($height)")
    }

    companion object {
        const val DEFAULT_BIOME = "minecraft:the_void"
        const val DEFAULT_MIN_Y = 0
        const val DEFAULT_HEIGHT = 256

        /** Vanilla limits of a dimension type. */
        const val MIN_Y_LIMIT = -2032
        const val MAX_Y_LIMIT = 2031
        const val HEIGHT_LIMIT = 4064

        val DEFAULT_GENERATOR: Generator = Generator.Void
        val DEFAULT_SKY = Sky(time = null, ambientLight = 0f, skylight = true, ceiling = false)

        private val ID = Regex("[a-z0-9_.-]+:[a-z0-9_./-]+")
        private val OPTIONS = setOf("generator", "biome", "min_y", "height", "sky")
        private val SKY_OPTIONS = setOf("time", "ambient_light", "skylight", "ceiling")

        /** A namespaced id other than the `minecraft` namespace; the namespace check applies to dimensions only. */
        private fun validateId(id: String, what: String = "dimension id") {
            if (!ID.matches(id)) throw SpecError("$what '$id' must be a namespaced id like 'namespace:name' (lowercase letters, digits, _ . - /)")
            if (what == "dimension id" && id.substringBefore(':') == "minecraft") {
                throw SpecError("$what '$id' must not use the 'minecraft' namespace")
            }
        }

        /** Reads the Lua arguments of `preload.dimension` and validates them. Unknown option keys are errors. */
        fun parse(id: LuaValue, options: LuaValue, blockExists: (String) -> Boolean): DimensionSpec {
            if (id.type() != LuaValue.TSTRING) throw SpecError("preload.dimension: the id must be a string, got ${id.typename()}")
            if (!options.isnil() && !options.istable()) {
                throw SpecError("preload.dimension: options must be a table, got ${options.typename()}")
            }
            val opts = options as? LuaTable ?: LuaTable()
            checkKeys(opts, OPTIONS, "preload.dimension")
            val sky = opts.get("sky")
            val skyTable = when {
                sky.isnil() -> null
                sky.istable() -> sky as LuaTable
                else -> throw SpecError("sky must be a table, got ${sky.typename()}")
            }
            skyTable?.let { checkKeys(it, SKY_OPTIONS, "sky") }
            val spec = DimensionSpec(
                id = id.tojstring(),
                generator = parseGenerator(opts.get("generator")),
                biome = opts.get("biome").let { if (it.isnil()) DEFAULT_BIOME else string(it, "biome") },
                minY = int(opts.get("min_y"), "min_y", DEFAULT_MIN_Y),
                height = int(opts.get("height"), "height", DEFAULT_HEIGHT),
                sky = Sky(
                    time = skyTable?.get("time")?.takeIf { !it.isnil() }?.let { skyTime(it) },
                    ambientLight = skyTable?.get("ambient_light")?.takeIf { !it.isnil() }?.let { number(it, "sky.ambient_light").toFloat() }
                        ?: DEFAULT_SKY.ambientLight,
                    skylight = skyTable?.get("skylight")?.takeIf { !it.isnil() }?.let { bool(it, "sky.skylight") } ?: DEFAULT_SKY.skylight,
                    ceiling = skyTable?.get("ceiling")?.takeIf { !it.isnil() }?.let { bool(it, "sky.ceiling") } ?: DEFAULT_SKY.ceiling,
                ),
            )
            spec.validate(blockExists)
            return spec
        }

        private fun skyTime(v: LuaValue): SkyTime {
            val name = if (v.type() == LuaValue.TSTRING) v.tojstring() else null
            return SkyTime.entries.firstOrNull { it.option == name }
                ?: throw SpecError("sky.time must be \"day\" or \"night\" (or nil for the normal cycle), got ${v.tojstring()}")
        }

        private fun parseGenerator(value: LuaValue): Generator {
            if (value.isnil()) return DEFAULT_GENERATOR
            if (value.type() == LuaValue.TSTRING) {
                if (value.tojstring() == "void") return Generator.Void
                throw SpecError("generator must be \"void\" or { flat = { {block, thickness}, ... } }, got '${value.tojstring()}'")
            }
            if (!value.istable()) throw SpecError("generator must be \"void\" or a table, got ${value.typename()}")
            val table = value as LuaTable
            checkKeys(table, setOf("flat"), "generator")
            val flat = table.get("flat")
            if (!flat.istable()) throw SpecError("generator.flat must be a list of {block, thickness} pairs")
            val layers = (1..flat.length()).map { i ->
                val pair = flat.get(i)
                if (!pair.istable() || pair.length() != 2 || pair.get(1).type() != LuaValue.TSTRING) {
                    throw SpecError("generator.flat[$i] must be a {\"namespace:block\", thickness} pair")
                }
                FlatLayer(pair.get(1).tojstring(), int(pair.get(2), "generator.flat[$i] thickness", 0))
            }
            if ((flat as LuaTable).keys().size != layers.size) throw SpecError("generator.flat must be a plain list (no named keys)")
            return Generator.Flat(layers)
        }

        private fun checkKeys(table: LuaTable, allowed: Set<String>, where: String) {
            for (key in table.keys()) {
                val name = if (key.type() == LuaValue.TSTRING) key.tojstring() else null
                if (name == null || name !in allowed) {
                    throw SpecError("$where: unknown option '${key.tojstring()}' (known: ${allowed.joinToString()})")
                }
            }
        }

        private fun string(v: LuaValue, what: String): String {
            if (v.type() != LuaValue.TSTRING) throw SpecError("$what must be a string, got ${v.typename()}")
            return v.tojstring()
        }

        private fun bool(v: LuaValue, what: String): Boolean {
            if (!v.isboolean()) throw SpecError("$what must be a boolean, got ${v.typename()}")
            return v.toboolean()
        }

        private fun number(v: LuaValue, what: String): Double {
            if (v.type() != LuaValue.TNUMBER) throw SpecError("$what must be a number, got ${v.typename()}")
            return v.todouble()
        }

        private fun int(v: LuaValue, what: String, default: Int): Int {
            if (v.isnil()) return default
            val d = number(v, what)
            if (d != Math.floor(d) || d.isInfinite()) throw SpecError("$what must be an integer, got ${v.tojstring()}")
            if (d > Int.MAX_VALUE || d < Int.MIN_VALUE) throw SpecError("$what is out of range: ${v.tojstring()}")
            return d.toInt()
        }
    }
}

/**
 * The data pack files of a [DimensionSpec]: `dimension/<id>.json` and `dimension_type/<id>.json` (same id).
 *
 * [worldClocks] says whether the target has per-dimension world clocks (Minecraft 26.1 and later): the dimension
 * type then names one (and requires `has_ender_dragon_fight`). Pure: no Minecraft classes.
 */
object DimensionJson {
    private const val OVERWORLD_TIMELINES = "#minecraft:in_overworld"
    private const val UNIVERSAL_TIMELINES = "#minecraft:universal"

    fun dimension(spec: DimensionSpec): JsonObject {
        val layers = when (val g = spec.generator) {
            Generator.Void -> listOf(FlatLayer("minecraft:air", 1))
            is Generator.Flat -> g.layers
        }
        val settings = JsonObject().apply {
            addProperty("biome", spec.biome)
            addProperty("features", false)
            addProperty("lakes", false)
            add("structure_overrides", JsonArray())
            add("layers", JsonArray().also { arr ->
                layers.forEach { arr.add(JsonObject().apply { addProperty("block", it.block); addProperty("height", it.thickness) }) }
            })
        }
        return JsonObject().apply {
            addProperty("type", spec.id)
            add("generator", JsonObject().apply {
                addProperty("type", "minecraft:flat")
                add("settings", settings)
            })
        }
    }

    fun dimensionType(spec: DimensionSpec, worldClocks: Boolean): JsonObject {
        val fixed = spec.sky.time
        return JsonObject().apply {
            addProperty("has_skylight", spec.sky.skylight)
            addProperty("has_ceiling", spec.sky.ceiling)
            addProperty("has_fixed_time", fixed != null)
            addProperty("coordinate_scale", 1.0)
            addProperty("min_y", spec.minY)
            addProperty("height", spec.height)
            addProperty("logical_height", spec.height)
            addProperty("infiniburn", "#minecraft:infiniburn_overworld")
            addProperty("ambient_light", spec.sky.ambientLight)
            addProperty("monster_spawn_block_light_limit", 0)
            add("monster_spawn_light_level", JsonObject().apply {
                addProperty("type", "minecraft:uniform")
                addProperty("min_inclusive", 0)
                addProperty("max_inclusive", 7)
            })
            addProperty("timelines", if (fixed == null) OVERWORLD_TIMELINES else UNIVERSAL_TIMELINES)
            if (worldClocks) addProperty("has_ender_dragon_fight", false)
            if (worldClocks && fixed == null) addProperty("default_clock", "minecraft:overworld")
            add("attributes", attributes(fixed))
        }
    }

    /**
     * Sky attributes. Without a fixed time they are the overworld's base values and the overworld timelines
     * modulate them over the day. With a [SkyTime] there are no day timelines (the sky would otherwise move), so the
     * attributes the timelines produce at noon or at midnight are written out as constants.
     */
    private fun attributes(fixed: SkyTime?): JsonObject = JsonObject().apply {
        if (fixed == null) {
            addProperty("minecraft:visual/sky_color", "#78a7ff")
            addProperty("minecraft:visual/fog_color", "#c0d8ff")
            addProperty("minecraft:visual/cloud_color", "#ccffffff")
            return@apply
        }
        val night = fixed == SkyTime.NIGHT
        val sun = if (night) 180f else 0f
        addProperty("minecraft:visual/sky_color", if (night) "#000000" else "#78a7ff")
        addProperty("minecraft:visual/fog_color", if (night) "#0b0d16" else "#c0d8ff")
        addProperty("minecraft:visual/cloud_color", if (night) "#cc191926" else "#ccffffff")
        addProperty("minecraft:visual/sun_angle", sun)
        addProperty("minecraft:visual/star_angle", sun)
        addProperty("minecraft:visual/moon_angle", (sun + 180f) % 360f)
        addProperty("minecraft:visual/star_brightness", if (night) 0.5f else 0.0f)
        addProperty("minecraft:visual/sky_light_color", if (night) "#7a7aff" else "#ffffff")
        addProperty("minecraft:visual/sky_light_factor", if (night) 0.24f else 1.0f)
        addProperty("minecraft:gameplay/sky_light_level", if (night) 0.26666668f else 1.0f)
        addProperty("minecraft:gameplay/monsters_burn", !night)
    }
}
