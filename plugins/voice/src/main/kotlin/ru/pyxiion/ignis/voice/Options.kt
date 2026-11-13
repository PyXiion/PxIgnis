package ru.pyxiion.ignis.voice

enum class SourceKind { ENTITY, POSITION, PLAYERS }

/** Pure checks of the emitter options, kept apart from Lua so they can be tested alone. */
object Options {
    private val CATEGORY_ID = Regex("[a-z0-9_-]+")

    /** Exactly one of `entity`, `pos` (with `world`) and `players` must be present. */
    fun sourceKind(fn: String, hasEntity: Boolean, hasPos: Boolean, hasWorld: Boolean, hasPlayers: Boolean): SourceKind {
        val given = listOfNotNull(
            "entity".takeIf { hasEntity }, "pos".takeIf { hasPos }, "players".takeIf { hasPlayers },
        )
        require(given.isNotEmpty()) { "$fn: give one source: 'entity', 'pos' + 'world', or 'players'" }
        require(given.size == 1) { "$fn: give only one source, got ${given.joinToString(" and ") { "'$it'" }}" }
        if (hasPos) require(hasWorld) { "$fn: option 'world' is required with 'pos'" }
        else require(!hasWorld) { "$fn: option 'world' only goes with 'pos'" }
        return when {
            hasEntity -> SourceKind.ENTITY
            hasPos -> SourceKind.POSITION
            else -> SourceKind.PLAYERS
        }
    }

    fun checkDistance(fn: String, distance: Float): Float {
        require(distance > 0f && distance.isFinite()) { "$fn: option 'distance' must be positive, got $distance" }
        return distance
    }

    fun checkVolume(fn: String, volume: Float): Float {
        require(volume >= 0f && volume.isFinite()) { "$fn: option 'volume' must be 0 or more, got $volume" }
        return volume
    }

    fun checkCategoryId(id: String): String {
        require(CATEGORY_ID.matches(id)) { "voice.category: 'id' must match [a-z0-9_-]+, got '$id'" }
        return id
    }
}
