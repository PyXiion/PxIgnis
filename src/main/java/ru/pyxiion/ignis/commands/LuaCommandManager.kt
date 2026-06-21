package ru.pyxiion.ignis.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.tree.ArgumentCommandNode
import com.mojang.brigadier.tree.CommandNode
import com.mojang.brigadier.tree.LiteralCommandNode
import net.minecraft.server.MinecraftServer
import net.minecraft.server.command.CommandManager
import net.minecraft.server.command.ServerCommandSource
import ru.pyxiion.ignis.PxIgnis
import ru.pyxiion.ignis.checkPermission
import ru.pyxiion.ignis.mixins.CommandNodeMixin

class LuaCommandManager(
    private val server: MinecraftServer
) {
    private var isRegistered = false
    private val commands = linkedMapOf<String, LiteralCommandNode<ServerCommandSource>>()
    private val pathPermissions = mutableMapOf<String, MutableSet<String?>>()
    private val originalNodes = mutableMapOf<String, LiteralCommandNode<ServerCommandSource>>()

    companion object {
        val RESERVED_COMMANDS = setOf(
            "ignis", "stop", "reload", "op", "deop",
            "ban", "ban-ip", "pardon", "pardon-ip",
            "save-all", "save-on", "save-off",
            "whitelist"
        )
    }

    fun addCommand(
        nodes: List<SyntaxNode>,
        argDefs: Map<String, ArgDef>,
        executor: (ctx: CommandContext<ServerCommandSource>) -> Int,
        permission: String?
    ) {
        check(!isRegistered) { "You can't add commands until you unregister the current commands" }

        val firstLiteral = nodes.firstOrNull() as? SyntaxNode.Literal
            ?: throw IllegalArgumentException("Command syntax must start with a literal")
        require(firstLiteral.value !in RESERVED_COMMANDS) {
            "Команда '${firstLiteral.value}' является зарезервированной и не может быть переопределена через Lua"
        }

        var currentNode: CommandNode<ServerCommandSource>? = null
        val pathBuilder = mutableListOf<String>()

        for (node in nodes) {
            val next = when (node) {
                is SyntaxNode.Literal -> {
                    if (currentNode == null) {
                        getOrCreateNodeByCmdPath(listOf(node.value))
                    } else {
                        var child = currentNode.getChild(node.value)
                        if (child == null) {
                            child = CommandManager.literal(node.value).build()
                            currentNode.addChild(child)
                        }
                        child
                    }
                }
                is SyntaxNode.Argument -> {
                    val argDef = argDefs[node.name]
                        ?: throw IllegalArgumentException("Unknown argument '${node.name}' in variant")
                    val argNode = (currentNode as CommandNodeMixin).children.values
                        .filterIsInstance<ArgumentCommandNode<*, *>>()
                        .find { it.name == node.name }
                        ?: argDef.luaType.getBrigadierArgument(node.name).also {
                            currentNode.addChild(it)
                        }
                    argNode as CommandNode<ServerCommandSource>
                }
            }
            currentNode = next

            pathBuilder.add(
                when (node) {
                    is SyntaxNode.Literal -> node.value
                    is SyntaxNode.Argument -> "<${node.name}>"
                }
            )

            val pathKey = pathBuilder.joinToString(" ")
            val perms = pathPermissions.getOrPut(pathKey) { mutableSetOf() }
            perms.add(permission)

            val snapshot = perms.toSet()
            if (null in snapshot) {
                (next as CommandNodeMixin).setRequirement { true }
            } else {
                (next as CommandNodeMixin).setRequirement { source ->
                    snapshot.any { perm -> source.checkPermission(perm!!) }
                }
            }
        }

        (currentNode as CommandNodeMixin).command = Command { ctx -> executor(ctx) }

        PxIgnis.logger.debug("/{} was added to the LuaCommandManager with arguments = {}, permission = {}",
            nodes.filterIsInstance<SyntaxNode.Literal>().joinToString(" ") { it.value },
            nodes.filterIsInstance<SyntaxNode.Argument>().map { it.name },
            permission)
    }

    private fun getOrCreateNodeByCmdPath(path: List<String>): LiteralCommandNode<ServerCommandSource> {
        var node = commands.getOrPut(path.first()) { CommandManager.literal(path.first()).build() }

        for (i in 1..<path.size) {
            var child = node.getChild(path[i])
            if (child == null) {
                child = CommandManager.literal(path[i]).build()
                node.addChild(child)
            }
            node = child as LiteralCommandNode
        }

        return node
    }

    fun registerAll() {
        check(!isRegistered) { "You can't register commands twice" }
        isRegistered = true
        val dispatcher = server.commandManager.dispatcher
        val root = dispatcher.root as CommandNodeMixin
        commands.forEach { (k, v) ->
            if (root.children.containsKey(k)) {
                originalNodes.putIfAbsent(k, root.children[k] as LiteralCommandNode<ServerCommandSource>)
                root.children.remove(k)
                root.literals.remove(k)
            }
            dispatcher.root.addChild(v)
        }
        updateCommandLists()
    }

    fun clear() {
        val root = (server.commandManager.dispatcher.root as CommandNodeMixin)

        if (isRegistered) {
            commands.forEach { (k, _) ->
                root.children.remove(k)
                root.literals.remove(k)
            }
            originalNodes.forEach { (name, original) ->
                root.children[name] = original
                root.literals[name] = original
            }
            originalNodes.clear()
        }
        isRegistered = false

        commands.clear()
        pathPermissions.clear()
        updateCommandLists()
    }

    private fun updateCommandLists() {
        val cmdManager = server.commandManager
        server.playerManager.playerList.forEach {
            cmdManager.sendCommandTree(it)
        }
    }
}
