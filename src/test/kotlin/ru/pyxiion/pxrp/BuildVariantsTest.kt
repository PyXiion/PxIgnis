package ru.pyxiion.pxrp

import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun `single required returns single variant`() {
        val variants = generateCommandPaths("cmd <a:text>")
        assertEquals(1, variants.size)
        assertEquals(2, variants[0].size)
        assertEquals(SyntaxNode.Literal("cmd"), variants[0][0])
        assertEquals(SyntaxNode.Argument("a", "text", false), variants[0][1])
    }

    @Test
    fun `generates empty literal-only variant when all optional`() {
        val variants = generateCommandPaths("cmd [a:text]")
        assertEquals(listOf(SyntaxNode.Literal("cmd")), variants[0])
    }
}
