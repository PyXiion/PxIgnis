package ru.pyxiion.ignis.commands

import ru.pyxiion.ignis.types.LuaArgumentType

sealed interface SyntaxNode {
    data class Literal(val value: String) : SyntaxNode
    data class Argument(val name: String, val typeDef: String, val isOptional: Boolean) : SyntaxNode

    /**
     * One alternative of a `<name:choice=a,b,c>` argument. It is a plain Brigadier literal, so parsing, errors and
     * suggestions are vanilla; the handler receives [value] as the argument.
     */
    data class Choice(val argName: String, val value: String) : SyntaxNode
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

    val names = nodes.filterIsInstance<SyntaxNode.Argument>().map { it.name }
    val duplicate = names.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }?.key
    require(duplicate == null) { "Argument name '$duplicate' is used more than once in '$syntax'." }

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

    return buildSyntaxVariants(nodes).flatMap(::expandChoices)
}

private const val CHOICE_PREFIX = "choice="

/** `choice=a,b` options; each must be a single non-blank word, because each becomes a Brigadier literal. */
internal fun parseChoiceOptions(typeDef: String): List<String> {
    val options = typeDef.removePrefix(CHOICE_PREFIX).split(",").map { it.trim() }
    require(options.all { it.isNotEmpty() }) { "choice type has an empty option in '$typeDef'" }
    options.firstOrNull { it.any(Char::isWhitespace) }?.let {
        throw IllegalArgumentException("choice option '$it' contains a space; options are single words")
    }
    require(options.toSet().size == options.size) { "choice type has duplicate options in '$typeDef'" }
    return options
}

/** Replaces every choice argument by its alternatives (cartesian product when there are several). */
private fun expandChoices(variant: List<SyntaxNode>): List<List<SyntaxNode>> {
    var variants: List<List<SyntaxNode>> = listOf(emptyList())
    for (node in variant) {
        variants = if (node is SyntaxNode.Argument && node.typeDef.startsWith(CHOICE_PREFIX)) {
            val options = parseChoiceOptions(node.typeDef)
            variants.flatMap { prefix -> options.map { prefix + SyntaxNode.Choice(node.name, it) } }
        } else {
            variants.map { it + node }
        }
    }
    return variants
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
