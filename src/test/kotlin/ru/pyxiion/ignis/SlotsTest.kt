package ru.pyxiion.ignis

import kotlin.test.Test
import kotlin.test.assertEquals
import ru.pyxiion.ignis.api.util.Slots

/** Lua slots are 1-based; the conversion at the API boundary is one place. */
class SlotsTest {
    @Test
    fun `vanilla index maps to a 1-based Lua number`() {
        assertEquals(1, Slots.toLua(0))
        assertEquals(9, Slots.toLua(8))
        assertEquals(41, Slots.toLua(40))
    }

    @Test
    fun `Lua slot maps to a 0-based index inside 1 to size`() {
        assertEquals(0, Slots.toIndex(1, 41))
        assertEquals(40, Slots.toIndex(41, 41))
        assertEquals(26, Slots.toIndex(27, 27))
    }

    @Test
    fun `slot 0 and slots past the end are out of range`() {
        assertEquals(-1, Slots.toIndex(0, 41))
        assertEquals(-1, Slots.toIndex(-1, 41))
        assertEquals(-1, Slots.toIndex(42, 41))
    }

    @Test
    fun `round trip`() {
        for (i in 0 until 41) assertEquals(i, Slots.toIndex(Slots.toLua(i), 41))
    }
}
