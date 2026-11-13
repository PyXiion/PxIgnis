package ru.pyxiion.ignis

import ru.pyxiion.ignis.sandbox.ModuleNames
import ru.pyxiion.ignis.sandbox.ModuleRef
import ru.pyxiion.ignis.sandbox.PathSandbox
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ModuleNamesTest {
    @Test
    fun `plain names are dotted paths`() {
        assertEquals(ModuleRef.Plain("util/strings"), ModuleNames.parse("util.strings"))
        assertEquals(ModuleRef.Plain("main"), ModuleNames.parse("main"))
    }

    @Test
    fun `namespaces`() {
        assertEquals(ModuleRef.Core("format"), ModuleNames.parse("core:format"))
        assertEquals(ModuleRef.Pkg("arena", ""), ModuleNames.parse("pkg:arena"))
        assertEquals(ModuleRef.Pkg("arena", "ui/menu"), ModuleNames.parse("pkg:arena.ui.menu"))
        assertEquals(ModuleRef.Ext("voice", null), ModuleNames.parse("ext:voice"))
        assertEquals(ModuleRef.Ext("voice", "sub.x"), ModuleNames.parse("ext:voice.sub.x"))
    }

    @Test
    fun `unknown namespace is an error and no folder fallback`() {
        val e = assertFailsWith<IllegalArgumentException> { ModuleNames.parse("mymod:utils") }
        assertTrue(e.message!!.contains("unknown namespace 'mymod:'"), e.message)
        assertTrue(e.message!!.contains("mymod.utils"), e.message)
    }

    @Test
    fun `bad ids and segments`() {
        assertFailsWith<IllegalArgumentException> { ModuleNames.parse("pkg:Arena") }
        assertFailsWith<IllegalArgumentException> { ModuleNames.parse("pkg:") }
        assertFailsWith<IllegalArgumentException> { ModuleNames.parse("a..b") }
        assertFailsWith<IllegalArgumentException> { ModuleNames.parse("a/b") }
        assertFailsWith<IllegalArgumentException> { ModuleNames.parse("core:") }
    }

    @Test
    fun `sandbox keeps paths inside the root`() {
        val tmp = Files.createTempDirectory("sandbox")
        val root = Files.createDirectories(tmp.resolve("root"))
        Files.writeString(root.resolve("a.txt"), "x")
        Files.writeString(tmp.resolve("secret.txt"), "s")
        val outside = Files.createDirectories(tmp.resolve("outside"))
        Files.createSymbolicLink(root.resolve("link"), outside)

        assertEquals(root.toRealPath().resolve("a.txt"), PathSandbox.resolveFile(root, "a.txt"))
        assertFailsWith<IOException> { PathSandbox.resolveFile(root, "../secret.txt") }
        assertFailsWith<IOException> { PathSandbox.resolveFile(root, tmp.resolve("secret.txt").toString()) }
        assertFailsWith<IOException> { PathSandbox.resolveExisting(root, "link") }
        assertFailsWith<IOException> { PathSandbox.resolveFile(root, "missing.txt") }
        assertFailsWith<IOException> { PathSandbox.resolveFile(root, ".") }
    }
}
