package ru.pyxiion.ignis

import ru.pyxiion.ignis.api.util.StructureCapture
import ru.pyxiion.ignis.sandbox.Vfs
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StructureCaptureTest {
    @Test
    fun `box takes corners in any order and is inclusive`() {
        val b = StructureCapture.box(5, 10, 5, -4, 1, 0)
        assertEquals(listOf(-4, 1, 0), listOf(b.minX, b.minY, b.minZ))
        assertEquals(listOf(10, 10, 6), listOf(b.sizeX, b.sizeY, b.sizeZ))
        assertEquals(1L, StructureCapture.box(3, 3, 3, 3, 3, 3).volume)
    }

    @Test
    fun `volume limit`() {
        StructureCapture.box(0, 0, 0, 59, 9, 59)
        val e = assertFailsWith<IllegalArgumentException> { StructureCapture.box(0, 0, 0, 999, 999, 999) }
        assertTrue(e.message!!.contains("limit"), e.message)
        assertFailsWith<IllegalArgumentException> { StructureCapture.box(Int.MIN_VALUE, 0, 0, Int.MAX_VALUE, 0, 0) }
    }

    @Test
    fun `save path rules`() {
        assertEquals("structures/rooms/hall.nbt", StructureCapture.savePath("structures\\rooms/hall.nbt"))
        listOf("", "/abs.nbt", "C:/x.nbt", "../x.nbt", "a/../b.nbt", "a//b.nbt", "a.txt", ".nbt", "a/.nbt", "a/./b.nbt").forEach {
            assertFailsWith<IllegalArgumentException>(it) { StructureCapture.savePath(it) }
        }
    }

    @Test
    fun `root selection`() {
        val game = Path.of("/srv")
        val roots = listOf(Path.of("/srv/config/ignis"), Path.of("/other"))
        assertEquals(roots[0], StructureCapture.selectRoot(roots, null, null, game))
        assertEquals(roots[1], StructureCapture.selectRoot(roots, 2, null, game))
        assertEquals(roots[0], StructureCapture.selectRoot(roots, null, "config/ignis", game))
        assertEquals(roots[1], StructureCapture.selectRoot(roots, null, "/other", game))
        assertFailsWith<IllegalArgumentException> { StructureCapture.selectRoot(roots, 3, null, game) }
        assertFailsWith<IllegalArgumentException> { StructureCapture.selectRoot(roots, 0, null, game) }
        assertFailsWith<IllegalArgumentException> { StructureCapture.selectRoot(roots, null, "/elsewhere", game) }
    }

    @Test
    fun `write target creates parents and stays inside the root`() {
        val root = Files.createTempDirectory("root")
        val vfs = Vfs(listOf(root))
        val t = vfs.writeTarget(root, "structures/rooms/hall.nbt")
        assertTrue(t.startsWith(root.toRealPath()))
        assertTrue(Files.isDirectory(t.parent))
        assertFailsWith<IOException> { vfs.writeTarget(root, "../escape.nbt") }
    }

    @Test
    fun `write target refuses a symlink that leaves the root`() {
        val root = Files.createTempDirectory("root")
        val outside = Files.createTempDirectory("outside")
        Files.createSymbolicLink(root.resolve("link"), outside)
        Files.createFile(outside.resolve("f.nbt"))
        Files.createSymbolicLink(root.resolve("file.nbt"), outside.resolve("f.nbt"))
        val vfs = Vfs(listOf(root))
        assertFailsWith<IOException> { vfs.writeTarget(root, "link/x.nbt") }
        assertFailsWith<IOException> { vfs.writeTarget(root, "file.nbt") }
    }
}
