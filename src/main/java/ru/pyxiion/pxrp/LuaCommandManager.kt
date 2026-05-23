package ru.pyxiion.pxrp

import com.mojang.brigadier.Command
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.tree.ArgumentCommandNode
import com.mojang.brigadier.tree.CommandNode
import com.mojang.brigadier.tree.LiteralCommandNode
import net.minecraft.server.MinecraftServer
import net.minecraft.server.command.CommandManager
import net.minecraft.server.command.ServerCommandSource
import ru.pyxiion.pxrp.mixins.CommandNodeMixin

class LuaCommandManager(
    private val server: MinecraftServer
) {
    private var isRegistered = false
    private val commands = linkedMapOf<String, LiteralCommandNode<ServerCommandSource>>()

    fun addCommand(
        command: String,
        arguments: List<ArgumentCommandNode<ServerCommandSource, *>>,
        executor: (ctx: CommandContext<ServerCommandSource>) -> Int,
        permission: String?
    ) {
        check(!isRegistered) { "You can't add commands until you unregister the current commands" }

        val commandParts = command.split(" ")
        require(commandParts.isNotEmpty()) { "Command must be not empty" }

        val cmd = getOrCreateNodeByCmdPath(commandParts)
        permission?.let {
            (cmd as CommandNodeMixin).setRequirement {
                it.checkPermission(permission)
            }
        }
        val lastArg = mergeOrBuildArgsFrom(cmd, arguments)
        (lastArg as CommandNodeMixin).command = Command { ctx -> executor(ctx) }

        PxRp.logger.debug("/{} was added to the LuaCommandManager with arguments = {}, permission = {}", command, arguments, permission)
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

    /**
     * Returns last arg node
     */
    private fun mergeOrBuildArgsFrom(
        node: LiteralCommandNode<ServerCommandSource>,
        args: List<ArgumentCommandNode<ServerCommandSource, *>>
    ): CommandNode<ServerCommandSource> {
        var currentNode: CommandNode<ServerCommandSource> = node
        for (arg in args) {
            val argNode = (currentNode as CommandNodeMixin).children.values.find {
                it is ArgumentCommandNode<*, *> && it.type == arg.type
            } ?: run {
                currentNode.addChild(arg)
                arg
            }
            currentNode = argNode as CommandNode<ServerCommandSource>
        }

        return currentNode
    }

    fun registerAll() {
        check(!isRegistered) { "You can't register commands twice" }
        isRegistered = true
        val dispatcher = server.commandManager.dispatcher
        commands.forEach { (k, v) ->
            // If it has a child with the name K already, delete it
            if ((dispatcher.root as CommandNodeMixin).children.containsKey(k)) {
                (dispatcher.root as CommandNodeMixin).children.remove(k)
                (dispatcher.root as CommandNodeMixin).literals.remove(k)
            }
            // Add/replace
            dispatcher.root.addChild(v)
        }
        updateCommandLists()
    }

    /**
     * Deletes all commands
     */
    fun clear() {
        // Getting the root node
        val root = (server.commandManager.dispatcher.root as CommandNodeMixin)

        // Unregistering old commands
        if (isRegistered) {
            commands.forEach { (k, v) ->
                root.children.remove(k)
                root.literals.remove(k)
            }
        }
        isRegistered = false

        commands.clear()
        updateCommandLists()
    }

    private fun updateCommandLists() {
        val cmdManager = server.commandManager
        server.playerManager.playerList.forEach {
            cmdManager.sendCommandTree(it)
        }
    }
}