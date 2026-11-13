package ru.pyxiion.ignis.mapcanvas

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CanvasLogicTest {
    @Test fun paletteBytes() {
        assertEquals(1 * 4 + 1, Palette.color("grass", 1))
        assertEquals(28 * 4 + 2, Palette.color("color_red", 2))
        assertEquals(28 * 4, Palette.color("RED", 0))
        assertEquals(Palette.color("snow", 3), Palette.color("white", 3))
        assertFailsWith<IllegalArgumentException> { Palette.color("nope", 1) }
        assertFailsWith<IllegalArgumentException> { Palette.color("stone", 4) }
        assertFailsWith<IllegalArgumentException> { Palette.color("stone", -1) }
    }

    @Test fun rgbNearest() {
        assertEquals(Palette.color("snow", 2), Palette.rgb(255, 255, 255))
        assertEquals(Palette.color("grass", 2), Palette.rgb(0x7F, 0xB2, 0x38))
        assertEquals(Palette.color("grass", 0), Palette.rgb(0x7F * 180 / 255, 0xB2 * 180 / 255, 0x38 * 180 / 255))
        assertTrue(Palette.rgb(0, 0, 0) >= 4, "never transparent")
        assertFailsWith<IllegalArgumentException> { Palette.rgb(256, 0, 0) }
    }

    @Test fun startsTransparentAndClean() {
        val b = PixelBuffer()
        assertTrue(b.data.all { it == 0.toByte() })
        assertFalse(b.dirty)
        assertNull(b.takeDirty())
    }

    @Test fun fillClipsAndTracksRect() {
        val b = PixelBuffer()
        b.fill(-5, 120, 20, 20, 9)
        val p = assertNotNull(b.takeDirty())
        assertEquals(0, p.x); assertEquals(120, p.y); assertEquals(15, p.w); assertEquals(8, p.h)
        assertEquals(15 * 8, p.bytes.size)
        assertTrue(p.bytes.all { it == 9.toByte() })
        assertEquals(9, b.data[127 * 128 + 14].toInt())
        assertEquals(0, b.data[127 * 128 + 15].toInt())
        assertNull(b.takeDirty(), "dirty resets")
    }

    @Test fun fillOutOfRangeIsNoop() {
        val b = PixelBuffer()
        b.fill(200, 0, 5, 5, 4)
        b.fill(0, 0, 0, 5, 4)
        b.fill(Int.MAX_VALUE - 1, 0, 100, 5, 4)
        b.pixel(-1, 0, 4); b.pixel(0, 128, 4)
        assertFalse(b.dirty)
    }

    @Test fun unchangedPixelsAreNotDirty() {
        val b = PixelBuffer()
        b.pixel(3, 4, 7); b.takeDirty()
        b.pixel(3, 4, 7)
        assertFalse(b.dirty)
        b.pixel(3, 4, 8)
        assertTrue(b.dirty)
    }

    @Test fun dirtyRectIsBoundingBox() {
        val b = PixelBuffer()
        b.pixel(10, 20, 1); b.pixel(30, 5, 1)
        val p = assertNotNull(b.takeDirty())
        assertEquals(10, p.x); assertEquals(5, p.y); assertEquals(21, p.w); assertEquals(16, p.h)
        assertEquals(1, p.bytes[(20 - 5) * 21 + 0].toInt())
        assertEquals(1, p.bytes[0 * 21 + 20].toInt())
    }

    @Test fun blitClipsWithSourceOffset() {
        val b = PixelBuffer()
        val src = byteArrayOf(1, 2, 3, 4, 5, 6) // 3x2
        b.blit(126, 127, 3, 2, src)
        assertEquals(1, b.data[127 * 128 + 126].toInt())
        assertEquals(2, b.data[127 * 128 + 127].toInt())
        val p = assertNotNull(b.takeDirty())
        assertEquals(126, p.x); assertEquals(127, p.y); assertEquals(2, p.w); assertEquals(1, p.h)
        b.blit(-1, 0, 2, 1, byteArrayOf(8, 9))
        assertEquals(9, b.data[0].toInt())
        assertEquals(0, b.data[1].toInt())
    }

    @Test fun blitRejectsShortSource() {
        assertFailsWith<IllegalArgumentException> { PixelBuffer().blit(0, 0, 4, 4, ByteArray(15)) }
        PixelBuffer().blit(0, 0, 2, 2, ByteArray(5), 1)
    }

    @Test fun clearAndFull() {
        val b = PixelBuffer()
        b.clear(5)
        val p = b.full()
        assertEquals(128 * 128, p.bytes.size)
        assertContentEquals(ByteArray(128 * 128) { 5 }, p.bytes)
        val d = assertNotNull(b.takeDirty())
        assertEquals(128, d.w); assertEquals(128, d.h)
    }
}
