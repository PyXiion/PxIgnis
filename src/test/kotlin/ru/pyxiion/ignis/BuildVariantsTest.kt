package ru.pyxiion.ignis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import ru.pyxiion.ignis.commands.SyntaxNode
import ru.pyxiion.ignis.commands.generateCommandPaths

class BuildVariantsTest {

    @Test
    fun `no optional returns single variant`() {
        val variants = generateCommandPaths("cmd <a:text> <b:text>")
        assertEquals(1, variants.size)
        val variant = variants[0]
        assertEquals(3, variant.size)
        assertEquals(SyntaxNode.Literal("cmd"), variant[0])
        assertEquals(SyntaxNode.Argument("a", "text", false), variant[1])
        assertEquals(SyntaxNode.Argument("b", "text", false), variant[2])
    }

    @Test
    fun `single optional generates two variants`() {
        val variants = generateCommandPaths("cmd [a:text]")
        assertEquals(2, variants.size)
        assertEquals(listOf(SyntaxNode.Literal("cmd")), variants[0])
        assertEquals(
            listOf(SyntaxNode.Literal("cmd"), SyntaxNode.Argument("a", "text", true)),
            variants[1]
        )
    }

    @Test
    fun `required plus one optional`() {
        val variants = generateCommandPaths("cmd <a:text> [b:text]")
        assertEquals(2, variants.size)
        assertEquals(
            listOf(SyntaxNode.Literal("cmd"), SyntaxNode.Argument("a", "text", false)),
            variants[0]
        )
        assertEquals(
            listOf(
                SyntaxNode.Literal("cmd"),
                SyntaxNode.Argument("a", "text", false),
                SyntaxNode.Argument("b", "text", true)
            ),
            variants[1]
        )
    }

    @Test
    fun `required plus two optional`() {
        val variants = generateCommandPaths("cmd <a:text> [b:text] [c:text]")
        assertEquals(3, variants.size)
        assertEquals(
            listOf(SyntaxNode.Literal("cmd"), SyntaxNode.Argument("a", "text", false)),
            variants[0]
        )
        assertEquals(
            listOf(
                SyntaxNode.Literal("cmd"),
                SyntaxNode.Argument("a", "text", false),
                SyntaxNode.Argument("b", "text", true)
            ),
            variants[1]
        )
        assertEquals(
            listOf(
                SyntaxNode.Literal("cmd"),
                SyntaxNode.Argument("a", "text", false),
                SyntaxNode.Argument("b", "text", true),
                SyntaxNode.Argument("c", "text", true)
            ),
            variants[2]
        )
    }

    @Test
    fun `all optional generates N plus 1 variants`() {
        val variants = generateCommandPaths("cmd [a:text] [b:text] [c:text]")
        assertEquals(4, variants.size)
        assertEquals(listOf(SyntaxNode.Literal("cmd")), variants[0])
        assertEquals(
            listOf(SyntaxNode.Literal("cmd"), SyntaxNode.Argument("a", "text", true)),
            variants[1]
        )
        assertEquals(
            listOf(
                SyntaxNode.Literal("cmd"),
                SyntaxNode.Argument("a", "text", true),
                SyntaxNode.Argument("b", "text", true)
            ),
            variants[2]
        )
        assertEquals(
            listOf(
                SyntaxNode.Literal("cmd"),
                SyntaxNode.Argument("a", "text", true),
                SyntaxNode.Argument("b", "text", true),
                SyntaxNode.Argument("c", "text", true)
            ),
            variants[3]
        )
    }

    @Test
    fun `literal after optional is preserved in all variants`() {
        val variants = generateCommandPaths("test [opt:int] confirm")
        assertEquals(2, variants.size)
        assertEquals(
            listOf(SyntaxNode.Literal("test"), SyntaxNode.Literal("confirm")),
            variants[0]
        )
        assertEquals(
            listOf(
                SyntaxNode.Literal("test"),
                SyntaxNode.Argument("opt", "int", true),
                SyntaxNode.Literal("confirm")
            ),
            variants[1]
        )
    }


    @Test
    fun `choice becomes one literal variant per option`() {
        val variants = generateCommandPaths("cmd <mode:choice=да,нет>")
        assertEquals(
            listOf(
                listOf(SyntaxNode.Literal("cmd"), SyntaxNode.Choice("mode", "да")),
                listOf(SyntaxNode.Literal("cmd"), SyntaxNode.Choice("mode", "нет")),
            ),
            variants
        )
    }

    @Test
    fun `choice options are trimmed`() {
        val variants = generateCommandPaths("cmd <m:choice=a, b>")
        assertEquals(listOf("a", "b"), variants.map { (it[1] as SyntaxNode.Choice).value })
    }

    @Test
    fun `optional choice keeps the variant without it`() {
        val variants = generateCommandPaths("cmd [m:choice=a,b]")
        assertEquals(3, variants.size)
        assertEquals(listOf(SyntaxNode.Literal("cmd")), variants[0])
    }

    @Test
    fun `several choices make a cartesian product`() {
        val variants = generateCommandPaths("cmd <a:choice=x,y> <b:choice=1,2>")
        assertEquals(4, variants.size)
        assertEquals(
            listOf(SyntaxNode.Literal("cmd"), SyntaxNode.Choice("a", "y"), SyntaxNode.Choice("b", "2")),
            variants.last()
        )
    }

    @Test
    fun `choice option with a space is an error`() {
        assertFailsWith<IllegalArgumentException> { generateCommandPaths("cmd <m:choice=a b,c>") }
    }

    @Test
    fun `empty and duplicate choice options are errors`() {
        assertFailsWith<IllegalArgumentException> { generateCommandPaths("cmd <m:choice=a,,b>") }
        assertFailsWith<IllegalArgumentException> { generateCommandPaths("cmd <m:choice=a,a>") }
    }

    @Test
    fun `repeated argument name is an error`() {
        assertFailsWith<IllegalArgumentException> { generateCommandPaths("cmd <x:int> <x:word>") }
        assertFailsWith<IllegalArgumentException> { generateCommandPaths("cmd <x:int> [x:int]") }
    }

}
