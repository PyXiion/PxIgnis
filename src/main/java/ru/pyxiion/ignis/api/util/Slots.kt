package ru.pyxiion.ignis.api.util

/**
 * Every slot number a Lua script sees or passes is 1-based; Minecraft's own indices are 0-based.
 * All conversions at the API boundary go through here, so there is exactly one place that adds or removes the 1.
 */
object Slots {
    /** Vanilla 0-based index to the number Lua sees. */
    fun toLua(index: Int): Int = index + 1

    /** Lua slot to a 0-based index in a container of [size] slots, or -1 when it is outside `1..size`. */
    fun toIndex(slot: Int, size: Int): Int = if (slot in 1..size) slot - 1 else -1
}
