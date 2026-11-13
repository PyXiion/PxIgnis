package ru.pyxiion.ignis

import ru.pyxiion.luanova.vm2.LuaState
import ru.pyxiion.luanova.vm2.LuaValue
import ru.pyxiion.luanova.vm2.compiler.LuaC
import ru.pyxiion.ignis.preload.DimensionJson
import ru.pyxiion.ignis.preload.DimensionSpec
import ru.pyxiion.ignis.preload.FlatLayer
import ru.pyxiion.ignis.preload.Generator
import ru.pyxiion.ignis.preload.SkyTime
import ru.pyxiion.ignis.preload.SpecError
import ru.pyxiion.ignis.runtime.ScriptRoots
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PreloadDimensionTest {
    private val blocks = setOf("minecraft:stone", "minecraft:dirt", "minecraft:air")
    private fun lua(src: String): LuaValue {
        val state = LuaState()
        LuaC.install(state)
        LuaState.setCurrent(state)
        return state.load("return $src", "t").checkfunction().call()
    }

    private fun parse(id: String, opts: String) = DimensionSpec.parse(LuaValue.valueOf(id), lua(opts)) { it in blocks }
    private fun error(id: String, opts: String) = assertFailsWith<SpecError> { parse(id, opts) }.message!!

    @Test
    fun `defaults`() {
        val s = DimensionSpec.parse(LuaValue.valueOf("t:r"), LuaValue.NIL) { true }
        assertEquals(DimensionSpec("t:r"), s)
    }

    @Test
    fun `full options`() {
        val s = parse("t:r", "{generator={flat={{'minecraft:stone',1},{'minecraft:dirt',2}}}, biome='minecraft:plains', min_y=-64, height=128, sky={time='night', ambient_light=0.1, skylight=false, ceiling=true}}")
        assertEquals(Generator.Flat(listOf(FlatLayer("minecraft:stone", 1), FlatLayer("minecraft:dirt", 2))), s.generator)
        assertEquals(-64, s.minY)
        assertEquals(SkyTime.NIGHT, s.sky.time)
        assertTrue(s.sky.ceiling && !s.sky.skylight)
    }

    @Test
    fun `id rules`() {
        assertTrue(error("rift", "{}").contains("namespaced"))
        assertTrue(error("minecraft:rift", "{}").contains("minecraft"))
    }

    @Test
    fun `constraints`() {
        assertTrue(error("t:r", "{min_y=5}").contains("min_y"))
        assertTrue(error("t:r", "{height=100}").contains("height"))
        assertTrue(error("t:r", "{min_y=2000, height=256}").contains("min_y + height"))
        assertTrue(error("t:r", "{sky={ambient_light=2}}").contains("ambient_light"))
        assertTrue(error("t:r", "{sky={time=18000}}").contains("sky.time"))
        assertTrue(error("t:r", "{sky={time='noon'}}").contains("day"))
        assertTrue(error("t:r", "{sky={fixed_time=1}}").contains("unknown option 'fixed_time'"))
    }

    @Test
    fun `unknown keys and bad types`() {
        assertTrue(error("t:r", "{hieght=1}").contains("unknown option 'hieght'"))
        assertTrue(error("t:r", "{sky={foo=1}}").contains("unknown option 'foo'"))
        assertTrue(error("t:r", "{generator='lava'}").contains("generator"))
        assertTrue(error("t:r", "{height='x'}").contains("number"))
        assertTrue(error("t:r", "{generator={flat={{'minecraft:nope',1}}}}").contains("does not exist"))
        assertTrue(error("t:r", "{height=16, generator={flat={{'minecraft:stone',17}}}}").contains("more than height"))
    }

    @Test
    fun `dimension json`() {
        val d = DimensionJson.dimension(DimensionSpec("t:r"))
        assertEquals("t:r", d["type"].asString)
        val layers = d["generator"].asJsonObject["settings"].asJsonObject["layers"].asJsonArray
        assertEquals("minecraft:air", layers[0].asJsonObject["block"].asString)
    }

    @Test
    fun `dimension type json`() {
        val normal = DimensionJson.dimensionType(DimensionSpec("t:r"), worldClocks = true)
        assertEquals("minecraft:overworld", normal["default_clock"].asString)
        assertEquals("#minecraft:in_overworld", normal["timelines"].asString)
        val fixed = DimensionJson.dimensionType(DimensionSpec("t:r", sky = DimensionSpec.DEFAULT_SKY.copy(time = SkyTime.NIGHT)), worldClocks = false)
        assertTrue(fixed["has_fixed_time"].asBoolean)
        assertTrue(!fixed.has("default_clock"))
        assertEquals(180f, fixed["attributes"].asJsonObject["minecraft:visual/sun_angle"].asFloat)
        assertEquals("#000000", fixed["attributes"].asJsonObject["minecraft:visual/sky_color"].asString)
        val day = DimensionJson.dimensionType(DimensionSpec("t:r", sky = DimensionSpec.DEFAULT_SKY.copy(time = SkyTime.DAY)), worldClocks = false)
        assertEquals(0f, day["attributes"].asJsonObject["minecraft:visual/sun_angle"].asFloat)
    }

    @Test
    fun `preload files are found at any depth in order`() {
        val a = Files.createTempDirectory("a"); val b = Files.createTempDirectory("b")
        Files.createDirectories(a.resolve("sub/deep"))
        listOf("z.preload.lua", "sub/deep/m.preload.lua", "x.lua", "sub/a.preload.lua").forEach { Files.writeString(a.resolve(it), "") }
        Files.writeString(b.resolve("q.preload.lua"), "")
        assertEquals(
            listOf("sub/a.preload.lua", "sub/deep/m.preload.lua", "z.preload.lua", "q.preload.lua"),
            ScriptRoots.preloadFiles(listOf(a, b)).map { it.relative },
        )
    }
}
