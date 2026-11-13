package ru.pyxiion.ignis

import ru.pyxiion.luanova.vm2.lib.jse.JsePlatform
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Numbers become text the same way on every server: a Russian default locale must not give "5,0" (breaks selectors like `distance=..5.0`). */
class LocaleIndependenceTest {
    private val saved = Locale.getDefault()

    @BeforeTest
    fun ru() = Locale.setDefault(Locale.of("ru", "RU"))

    @AfterTest
    fun restore() = Locale.setDefault(saved)

    private fun eval(code: String): String = JsePlatform.standardState().load("return $code", "t").call().tojstring()

    @Test
    fun `string format floats use a dot`() {
        assertEquals("5.0", eval("string.format('%.1f', 5)"))
        assertEquals("1234.568", eval("string.format('%.3f', 1234.5678)"))
        assertEquals("2.50", eval("('%.2f'):format(2.5)"))
        assertEquals("1.500000e+00", eval("string.format('%e', 1.5)"))
        assertEquals("0.500000", eval("string.format('%g', 0.5)"))
        assertEquals("distance=..5.5", eval("string.format('distance=..%.1f', 5.5)"))
    }

    @Test
    fun `quoted floats and tostring use a dot`() {
        assert(',' !in eval("string.format('%q', 0.1)"))
        assertEquals("5.5", eval("tostring(5.5)"))
        assertEquals("1.5", eval("1.5 .. ''"))
    }

    @Test
    fun `upper and lower ignore the locale`() {
        Locale.setDefault(Locale.of("tr", "TR"))
        assertEquals("TITLE", eval("string.upper('title')"))
        assertEquals("title", eval("string.lower('TITLE')"))
    }
}
