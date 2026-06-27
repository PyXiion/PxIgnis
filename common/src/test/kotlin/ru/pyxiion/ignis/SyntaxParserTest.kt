package ru.pyxiion.ignis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import ru.pyxiion.ignis.commands.SyntaxNode
import ru.pyxiion.ignis.commands.SyntaxParser
import ru.pyxiion.ignis.commands.generateCommandPaths

class SyntaxParserTest {

    @Test
    fun `parses single literal`() {
        val nodes = SyntaxParser("gamemode").parse()
        assertEquals(listOf(SyntaxNode.Literal("gamemode")), nodes)
    }

    @Test
    fun `parses multiple literals`() {
        val nodes = SyntaxParser("cmd sub").parse()
        assertEquals(listOf(SyntaxNode.Literal("cmd"), SyntaxNode.Literal("sub")), nodes)
    }

    @Test
    fun `parses literal with required arg`() {
        val nodes = SyntaxParser("cmd <name:text>").parse()
        assertEquals(
            listOf(SyntaxNode.Literal("cmd"), SyntaxNode.Argument("name", "text", false)),
            nodes
        )
    }

    @Test
    fun `parses literal with multiple required args`() {
        val nodes = SyntaxParser("cmd <name:text> <count:int>").parse()
        assertEquals(3, nodes.size)
        assertEquals(SyntaxNode.Literal("cmd"), nodes[0])
        assertEquals(SyntaxNode.Argument("name", "text", false), nodes[1])
        assertEquals(SyntaxNode.Argument("count", "int", false), nodes[2])
    }

    @Test
    fun `parses optional arg`() {
        val nodes = SyntaxParser("cmd [opt:bool]").parse()
        assertEquals(
            listOf(SyntaxNode.Literal("cmd"), SyntaxNode.Argument("opt", "bool", true)),
            nodes
        )
    }

    @Test
    fun `parses optional arg with legacy angle bracket wrapper`() {
        val nodes = SyntaxParser("cmd [<name:text>]").parse()
        assertEquals(
            listOf(SyntaxNode.Literal("cmd"), SyntaxNode.Argument("name", "text", true)),
            nodes
        )
    }

    @Test
    fun `parses choice arg`() {
        val nodes = SyntaxParser("cmd <mode:choice=creative,survival>").parse()
        assertEquals(2, nodes.size)
        assertEquals(SyntaxNode.Literal("cmd"), nodes[0])
        assertEquals(SyntaxNode.Argument("mode", "choice=creative,survival", false), nodes[1])
    }

    @Test
    fun `parses required and optional together`() {
        val nodes = SyntaxParser("gamemode <mode:choice=creative,spectator> [target:player]").parse()
        assertEquals(3, nodes.size)
        assertEquals(SyntaxNode.Literal("gamemode"), nodes[0])
        assertEquals(SyntaxNode.Argument("mode", "choice=creative,spectator", false), nodes[1])
        assertEquals(SyntaxNode.Argument("target", "player", true), nodes[2])
    }

    @Test
    fun `handles extra whitespace`() {
        val nodes = SyntaxParser("  cmd   <arg:text>  ").parse()
        assertEquals(
            listOf(SyntaxNode.Literal("cmd"), SyntaxNode.Argument("arg", "text", false)),
            nodes
        )
    }

    @Test
    fun `errors on missing type`() {
        assertFailsWith<IllegalArgumentException> {
            SyntaxParser("cmd <name>").parse()
        }
    }

    @Test
    fun `errors on empty name`() {
        assertFailsWith<IllegalArgumentException> {
            SyntaxParser("cmd <:text>").parse()
        }
    }

    @Test
    fun `errors on empty type`() {
        assertFailsWith<IllegalArgumentException> {
            SyntaxParser("cmd <name:>").parse()
        }
    }

    @Test
    fun `errors on missing closing bracket`() {
        assertFailsWith<IllegalArgumentException> {
            SyntaxParser("cmd <name:text").parse()
        }
    }

    @Test
    fun `empty input returns empty list`() {
        val nodes = SyntaxParser("").parse()
        assertTrue(nodes.isEmpty())
    }

    @Test
    fun `whitespace only returns empty list`() {
        val nodes = SyntaxParser("   ").parse()
        assertTrue(nodes.isEmpty())
    }

    @Test
    fun `errors on required after optional`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            generateCommandPaths("cmd [opt:bool] <req:text>")
        }
        assertTrue(ex.message!!.contains("req"))
    }

    @Test
    fun `no error when optional follows required`() {
        val nodes = SyntaxParser("cmd <req:text> [opt:bool]").parse()
        assertEquals(3, nodes.size)
        assertEquals(SyntaxNode.Argument("req", "text", false), nodes[1])
        assertEquals(SyntaxNode.Argument("opt", "bool", true), nodes[2])
    }

    @Test
    fun `literal after required arg`() {
        val nodes = SyntaxParser("cmd <target:player> do").parse()
        assertEquals(
            listOf(
                SyntaxNode.Literal("cmd"),
                SyntaxNode.Argument("target", "player", false),
                SyntaxNode.Literal("do")
            ),
            nodes
        )
    }

    @Test
    fun `subcommands with same argument before literal`() {
        val doNodes = SyntaxParser("cmd <target:player> do").parse()
        val undoNodes = SyntaxParser("cmd <target:player> undo").parse()
        assertEquals(
            listOf(
                SyntaxNode.Literal("cmd"),
                SyntaxNode.Argument("target", "player", false),
                SyntaxNode.Literal("do")
            ),
            doNodes
        )
        assertEquals(
            listOf(
                SyntaxNode.Literal("cmd"),
                SyntaxNode.Argument("target", "player", false),
                SyntaxNode.Literal("undo")
            ),
            undoNodes
        )
    }
}
