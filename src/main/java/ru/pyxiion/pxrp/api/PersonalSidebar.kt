package ru.pyxiion.pxrp.api

import net.minecraft.network.packet.s2c.play.ScoreboardDisplayS2CPacket
import net.minecraft.scoreboard.ScoreHolder
import net.minecraft.scoreboard.ScoreboardCriterion
import net.minecraft.scoreboard.ScoreboardDisplaySlot
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import java.util.UUID

class PersonalSidebarManager(private val server: MinecraftServer) {
    private val sidebars = mutableMapOf<UUID, PersonalSidebarData>()

    data class PersonalSidebarData(
        val objectiveName: String,
        val title: String,
        val lines: List<String>
    )

    fun setSidebar(player: ServerPlayerEntity, lines: List<String>, title: String?) {
        removeForPlayer(player)

        val objectiveName = "_pxrp_${player.uuid.toString().take(8)}"
        val displayTitle = title ?: "§6§lПерсональный сайдбар"

        val sideData = PersonalSidebarData(objectiveName, displayTitle, lines)
        sidebars[player.uuid] = sideData

        val scoreboard = server.scoreboard
        var objective = scoreboard.getNullableObjective(objectiveName)
        if (objective == null) {
            objective = scoreboard.addObjective(objectiveName, ScoreboardCriterion.DUMMY, Text.literal(displayTitle), ScoreboardCriterion.RenderType.INTEGER, false, null)
        } else {
            objective.displayName = Text.literal(displayTitle)
        }

        for ((index, line) in lines.withIndex()) {
            val holder = ScoreHolder.fromName("_l$index")
            val scoreObj = scoreboard.getOrCreateScore(holder, objective)
            scoreObj.setScore(lines.size - index)
            scoreObj.setDisplayText(Text.literal(line))
        }

        player.networkHandler.sendPacket(
            ScoreboardDisplayS2CPacket(ScoreboardDisplaySlot.SIDEBAR, objective)
        )
    }

    fun getSidebar(player: ServerPlayerEntity): PersonalSidebarData? {
        return sidebars[player.uuid]
    }

    fun setSidebarTitle(player: ServerPlayerEntity, title: String) {
        val data = sidebars[player.uuid] ?: return
        val newData = data.copy(title = title)
        sidebars[player.uuid] = newData

        val objective = server.scoreboard.getNullableObjective(data.objectiveName) ?: return
        objective.displayName = Text.literal(title)
    }

    fun setSidebarLines(player: ServerPlayerEntity, lines: List<String>) {
        val data = sidebars[player.uuid] ?: return
        val newData = data.copy(lines = lines)
        sidebars[player.uuid] = newData

        val scoreboard = server.scoreboard
        val objective = scoreboard.getNullableObjective(data.objectiveName) ?: return

        val prevCount = data.lines.size
        if (lines.size < prevCount) {
            for (i in lines.size until prevCount) {
                scoreboard.removeScore(ScoreHolder.fromName("_l$i"), objective)
            }
        }
        for ((index, line) in lines.withIndex()) {
            val holder = ScoreHolder.fromName("_l$index")
            val scoreObj = scoreboard.getOrCreateScore(holder, objective)
            scoreObj.setScore(lines.size - index)
            scoreObj.setDisplayText(Text.literal(line))
        }
    }

    fun clearSidebar(player: ServerPlayerEntity) {
        removeForPlayer(player)
    }

    fun restoreForPlayer(player: ServerPlayerEntity) {
        val data = sidebars[player.uuid] ?: return
        val objective = server.scoreboard.getNullableObjective(data.objectiveName)
        if (objective != null) {
            player.networkHandler.sendPacket(
                ScoreboardDisplayS2CPacket(ScoreboardDisplaySlot.SIDEBAR, objective)
            )
        }
    }

    fun removeForPlayer(player: ServerPlayerEntity) {
        val data = sidebars.remove(player.uuid) ?: return
        val objective = server.scoreboard.getNullableObjective(data.objectiveName)
        if (objective != null) {
            server.scoreboard.removeObjective(objective)
        }
    }
}
