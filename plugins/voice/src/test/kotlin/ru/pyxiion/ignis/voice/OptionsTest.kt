package ru.pyxiion.ignis.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OptionsTest {
    private fun kind(e: Boolean = false, p: Boolean = false, w: Boolean = false, pl: Boolean = false) =
        Options.sourceKind("voice.emitter", e, p, w, pl)

    @Test
    fun exactlyOneSource() {
        assertEquals(SourceKind.ENTITY, kind(e = true))
        assertEquals(SourceKind.POSITION, kind(p = true, w = true))
        assertEquals(SourceKind.PLAYERS, kind(pl = true))
        assertTrue(assertFailsWith<IllegalArgumentException> { kind() }.message!!.contains("give one source"))
        assertTrue(assertFailsWith<IllegalArgumentException> { kind(e = true, pl = true) }.message!!.contains("'entity' and 'players'"))
        assertFailsWith<IllegalArgumentException> { kind(p = true, pl = true, w = true) }
    }

    @Test
    fun positionNeedsWorld() {
        assertTrue(assertFailsWith<IllegalArgumentException> { kind(p = true) }.message!!.contains("'world' is required"))
        assertFailsWith<IllegalArgumentException> { kind(e = true, w = true) }
    }

    @Test
    fun numbersAreChecked() {
        assertEquals(8f, Options.checkDistance("f", 8f))
        assertFailsWith<IllegalArgumentException> { Options.checkDistance("f", 0f) }
        assertFailsWith<IllegalArgumentException> { Options.checkDistance("f", Float.NaN) }
        assertEquals(0f, Options.checkVolume("f", 0f))
        assertFailsWith<IllegalArgumentException> { Options.checkVolume("f", -1f) }
    }

    @Test
    fun categoryIds() {
        assertEquals("music_2-b", Options.checkCategoryId("music_2-b"))
        assertFailsWith<IllegalArgumentException> { Options.checkCategoryId("Music") }
        assertFailsWith<IllegalArgumentException> { Options.checkCategoryId("") }
    }
}
