package ru.pyxiion.ignis

import ru.pyxiion.ignis.runtime.ScriptRoots
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class ScriptRootsTest {
    private val gameDir: Path = Path.of("/srv/mc")
    private val configDir: Path = gameDir.resolve("config")

    @Test
    fun `no entries gives the default root`() {
        val expected = listOf(Path.of("/srv/mc/config/ignis"))
        assertEquals(expected, ScriptRoots.resolve(null, gameDir, configDir))
        assertEquals(expected, ScriptRoots.resolve(emptyList(), gameDir, configDir))
        assertEquals(expected, ScriptRoots.resolve(listOf("", "  "), gameDir, configDir))
    }

    @Test
    fun `a relative entry resolves against the run dir`() {
        assertEquals(
            listOf(Path.of("/srv/mc/scripts")),
            ScriptRoots.resolve(listOf("scripts"), gameDir, configDir),
        )
    }

    @Test
    fun `an absolute entry is kept`() {
        assertEquals(
            listOf(Path.of("/opt/ignis")),
            ScriptRoots.resolve(listOf("/opt/ignis"), gameDir, configDir),
        )
    }

    @Test
    fun `entries keep the listed order`() {
        assertEquals(
            listOf(Path.of("/srv/mc/b"), Path.of("/srv/mc/config/ignis"), Path.of("/opt/a")),
            ScriptRoots.resolve(listOf("b", "config/ignis", "/opt/a"), gameDir, configDir),
        )
    }

    @Test
    fun `dots and trailing slashes are normalized`() {
        assertEquals(
            listOf(Path.of("/srv/mc/scripts"), Path.of("/opt/ignis")),
            ScriptRoots.resolve(listOf("./scripts/", "/opt/ignis/../ignis"), gameDir, configDir),
        )
    }

    @Test
    fun `duplicates are dropped, the first one wins`() {
        assertEquals(
            listOf(Path.of("/srv/mc/scripts")),
            ScriptRoots.resolve(listOf("scripts", "/srv/mc/scripts", "scripts/"), gameDir, configDir),
        )
    }

    @Test
    fun `entries are trimmed`() {
        assertEquals(
            listOf(Path.of("/srv/mc/scripts")),
            ScriptRoots.resolve(listOf(" scripts "), gameDir, configDir),
        )
    }
}
