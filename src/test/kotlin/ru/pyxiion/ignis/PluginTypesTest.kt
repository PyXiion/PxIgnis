package ru.pyxiion.ignis

import ru.pyxiion.ignis.runtime.EditorSupport
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginTypesTest {
    @Test
    fun `plugin stubs go to their own folder and survive the core copy`() {
        val tmp = Files.createTempDirectory("types")
        val ignis = Files.createDirectories(tmp.resolve("ignis"))
        val core = Files.createDirectories(tmp.resolve("core")).also { it.resolve("00-core.lua").writeText("---@meta\n") }
        val voice = Files.createDirectories(tmp.resolve("voice")).also { it.resolve("voice.lua").writeText("---@meta voice\n") }

        EditorSupport.install(ignis, core)
        EditorSupport.installPluginTypes(ignis, mapOf("voice" to voice, "gone" to voice))
        EditorSupport.install(ignis, core) // the core copy wipes .types/*.lua, not .types/plugins
        assertEquals("---@meta voice\n", ignis.resolve(".types/plugins/voice/voice.lua").readText())
        assertTrue(ignis.resolve(".types/00-core.lua").exists())

        EditorSupport.installPluginTypes(ignis, mapOf("voice" to voice))
        assertFalse(ignis.resolve(".types/plugins/gone").exists())
        assertTrue(ignis.resolve(".types/plugins/voice/voice.lua").exists())
    }
}
