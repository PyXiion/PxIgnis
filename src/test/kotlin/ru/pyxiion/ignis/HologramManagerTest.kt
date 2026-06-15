package ru.pyxiion.ignis

import kotlin.test.Test
import kotlin.test.assertEquals
import ru.pyxiion.ignis.api.HologramDefaults
import ru.pyxiion.ignis.api.HologramWrapper

class HologramManagerTest {

    @Test
    fun `hologramKeys contains expected entries`() {
        val expected = setOf(
            "text", "lines", "alignment", "billboard", "lineWidth",
            "background", "opacity", "shadow", "seeThrough", "glowing",
            "setLine", "destroy",
        )
        val keys = HologramWrapper.hologramKeys.toSet()
        assertEquals(expected, keys)
    }

    @Test
    fun `hologramKeys has no duplicates`() {
        val keys = HologramWrapper.hologramKeys
        assertEquals(keys.size, keys.toSet().size, "hologramKeys should not contain duplicates")
    }

    @Test
    fun `hologramKeys contains setLine and destroy as methods`() {
        val keys = HologramWrapper.hologramKeys.toSet()
        assertEquals(true, "setLine" in keys)
        assertEquals(true, "destroy" in keys)
    }

    @Test
    fun `default values match vanilla constants`() {
        assertEquals(0x40000000, HologramDefaults.BACKGROUND)
        assertEquals(200, HologramDefaults.LINE_WIDTH)
    }

    @Test
    fun `background default is a positive ARGB int`() {
        assertEquals(true, HologramDefaults.BACKGROUND > 0)
        assertEquals(true, HologramDefaults.BACKGROUND and 0xFF000000.toInt() != 0)
    }

    @Test
    fun `lineWidth default is positive`() {
        assertEquals(true, HologramDefaults.LINE_WIDTH > 0)
    }
}
