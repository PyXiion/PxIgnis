package ru.pyxiion.ignis

import net.minecraft.util.math.Box
import ru.pyxiion.ignis.api.manager.RegionManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun box(minX: Double, minY: Double, minZ: Double, maxX: Double, maxY: Double, maxZ: Double) =
    Box(minX, minY, minZ, maxX, maxY, maxZ)

private fun box1() = box(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)
private fun box2() = box(2.0, 2.0, 2.0, 3.0, 3.0, 3.0)
private fun box3() = box(4.0, 4.0, 4.0, 5.0, 5.0, 5.0)

class RegionInterestDiffTest {

    @Test
    fun `empty prev and empty next yields no changes`() {
        val diff = RegionManager.diffRegionSnapshots(emptyMap(), emptyMap(), 256)
        assertTrue(diff.additions.isEmpty())
        assertTrue(diff.removals.isEmpty())
        assertFalse(diff.capped)
    }

    @Test
    fun `empty prev to one-entry next yields one addition`() {
        val diff = RegionManager.diffRegionSnapshots(
            emptyMap(),
            mapOf(1 to box1()),
            256
        )
        assertEquals(1, diff.additions.size)
        assertEquals(1, diff.additions[0].id)
        assertEquals(box1(), diff.additions[0].box)
        assertTrue(diff.removals.isEmpty())
        assertFalse(diff.capped)
    }

    @Test
    fun `one-entry prev to empty next yields one removal`() {
        val diff = RegionManager.diffRegionSnapshots(
            mapOf(1 to box1()),
            emptyMap(),
            256
        )
        assertTrue(diff.additions.isEmpty())
        assertEquals(listOf(1), diff.removals)
        assertFalse(diff.capped)
    }

    @Test
    fun `bounds change is reported as upsert`() {
        val diff = RegionManager.diffRegionSnapshots(
            mapOf(1 to box1()),
            mapOf(1 to box2()),
            256
        )
        assertEquals(1, diff.additions.size)
        assertEquals(1, diff.additions[0].id)
        assertEquals(box2(), diff.additions[0].box)
        assertTrue(diff.removals.isEmpty())
        assertFalse(diff.capped)
    }

    @Test
    fun `unbounded when under cap`() {
        val diff = RegionManager.diffRegionSnapshots(
            emptyMap(),
            mapOf(1 to box1(), 2 to box2(), 3 to box3()),
            10
        )
        assertEquals(3, diff.additions.size)
        assertTrue(diff.removals.isEmpty())
        assertFalse(diff.capped)
    }

    @Test
    fun `capped when over limit`() {
        val next = mapOf(
            1 to box1(),
            2 to box2(),
            3 to box3(),
            4 to box(5.0, 5.0, 5.0, 6.0, 6.0, 6.0),
            5 to box(6.0, 6.0, 6.0, 7.0, 7.0, 7.0)
        )
        val diff = RegionManager.diffRegionSnapshots(emptyMap(), next, 3)
        assertEquals(3, diff.additions.size)
        assertTrue(diff.capped)
    }
}
