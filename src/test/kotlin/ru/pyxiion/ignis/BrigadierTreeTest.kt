package ru.pyxiion.ignis

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.tree.ArgumentCommandNode
import com.mojang.brigadier.tree.CommandNode
import net.minecraft.server.command.ServerCommandSource
import kotlin.test.Test
import kotlin.test.assertEquals

private fun literal(name: String) =
    LiteralArgumentBuilder.literal<ServerCommandSource>(name).build()

private fun arg(name: String): ArgumentCommandNode<ServerCommandSource, *> =
    RequiredArgumentBuilder.argument<ServerCommandSource, String>(name, StringArgumentType.word()).build()

private val childrenField = CommandNode::class.java.getDeclaredField("children").also { it.isAccessible = true }

@Suppress("UNCHECKED_CAST")
private fun CommandNode<*>.childrenMap(): Map<String, CommandNode<*>> =
    childrenField.get(this) as Map<String, CommandNode<*>>

class BrigadierTreeTest {

    @Test
    fun `same arg name deduplicates`() {
        val cmd = literal("gamemode")
        val a1 = arg("mode")

        cmd.addChild(a1)
        assertEquals(1, cmd.childrenMap().size)
        assertEquals(a1, cmd.getChild("mode"))
    }

    @Test
    fun `different arg names become siblings`() {
        val cmd = literal("gamemode")
        cmd.addChild(arg("mode"))
        cmd.addChild(arg("mode2"))

        assertEquals(setOf("mode", "mode2"), cmd.childrenMap().keys)
    }

    @Test
    fun `arg chain builds correctly`() {
        val cmd = literal("gamemode")
        val mode = arg("mode")
        val target = arg("target")

        cmd.addChild(mode)
        mode.addChild(target)

        assertEquals(setOf("mode"), cmd.childrenMap().keys)
        assertEquals(setOf("target"), cmd.getChild("mode")!!.childrenMap().keys)
    }

    @Test
    fun `literal after arg in same tree`() {
        val root = literal("cmd")
        val target = arg("target")
        val doLeaf = literal("do")
        val undoLeaf = literal("undo")

        root.addChild(target)
        target.addChild(doLeaf)
        root.addChild(undoLeaf)

        assertEquals(setOf("target", "undo"), root.childrenMap().keys)
        assertEquals(setOf("do"), root.getChild("target")!!.childrenMap().keys)
    }
}
