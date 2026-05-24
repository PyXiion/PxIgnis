package ru.pyxiion.pxrp

import ru.pyxiion.pxrp.types.LuaArgumentType

sealed interface SyntaxNode {
    data class Literal(val value: String) : SyntaxNode
    data class Argument(val name: String, val typeDef: String, val isOptional: Boolean) : SyntaxNode
}

data class ArgDef(
    val luaType: LuaArgumentType,
    val isOptional: Boolean
)

class SyntaxParser(private val syntax: String) {
    private var pos = 0

    fun parse(): List<SyntaxNode> {
        val nodes = mutableListOf<SyntaxNode>()
        skipWhitespace()
        while (pos < syntax.length) {
            nodes.add(
                when (syntax[pos]) {
                    '<' -> parseRequiredArg()
                    '[' -> parseOptionalArg()
                    else -> parseLiteral()
                }
            )
            skipWhitespace()
        }
        return nodes
    }

    private fun parseLiteral(): SyntaxNode.Literal {
        val start = pos
        while (pos < syntax.length && !syntax[pos].isWhitespace() && syntax[pos] !in "<[") pos++
        if (pos == start) error("Expected literal, argument, or end at position $pos")
        return SyntaxNode.Literal(syntax.substring(start, pos))
    }

    private fun parseRequiredArg(): SyntaxNode.Argument {
        return parseBracketedContent('<', '>', isOptional = false)
    }

    private fun parseOptionalArg(): SyntaxNode.Argument {
        return parseBracketedContent('[', ']', isOptional = true)
    }

    private fun parseBracketedContent(open: Char, close: Char, isOptional: Boolean): SyntaxNode.Argument {
        val argStart = pos
        require(syntax[pos] == open) { "Expected '$open' at position $pos" }
        pos++

        val nameStart = pos
        var colonPos = -1

        while (pos < syntax.length && syntax[pos] != close) {
            if (syntax[pos] == ':' && colonPos == -1) colonPos = pos
            pos++
        }

        require(pos < syntax.length) { "Missing closing '$close' for argument starting at position $argStart" }
        require(colonPos != -1) {
            "Argument '${syntax.substring(nameStart, pos)}' is missing type specification. Use <name:type>."
        }

        var name = syntax.substring(nameStart, colonPos)
        var typeDef = syntax.substring(colonPos + 1, pos)

        if (isOptional && name.startsWith("<") && typeDef.endsWith(">")) {
            name = name.substring(1)
            typeDef = typeDef.substring(0, typeDef.length - 1)
        }

        require(name.isNotEmpty()) { "Argument has empty name at position $argStart" }
        require(typeDef.isNotEmpty()) { "Argument '$name' has empty type at position $argStart" }

        pos++

        return SyntaxNode.Argument(name, typeDef, isOptional)
    }

    private fun skipWhitespace() {
        while (pos < syntax.length && syntax[pos].isWhitespace()) pos++
    }
}

fun generateCommandPaths(syntax: String): List<List<SyntaxNode>> {
    val nodes = SyntaxParser(syntax).parse()

    var optionalStarted = false
    for (node in nodes) {
        if (node is SyntaxNode.Argument) {
            if (node.isOptional) {
                optionalStarted = true
            } else if (optionalStarted) {
                throw IllegalArgumentException("Required argument '${node.name}' cannot follow optional arguments.")
            }
        }
    }

    return buildSyntaxVariants(nodes)
}

private fun buildSyntaxVariants(nodes: List<SyntaxNode>): List<List<SyntaxNode>> {
    val optionalIndices = nodes.indices.filter { i ->
        val arg = nodes[i] as? SyntaxNode.Argument
        arg != null && arg.isOptional
    }

    if (optionalIndices.isEmpty()) return listOf(nodes)

    return (0..optionalIndices.size).map { keepCount ->
        val omit = optionalIndices.drop(keepCount).toSet()
        nodes.filterIndexed { index, _ -> index !in omit }
    }
}


