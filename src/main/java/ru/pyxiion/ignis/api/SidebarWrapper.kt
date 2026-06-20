package ru.pyxiion.ignis.api

import net.minecraft.network.packet.s2c.play.ScoreboardObjectiveUpdateS2CPacket
import net.minecraft.network.packet.s2c.play.ScoreboardScoreResetS2CPacket
import net.minecraft.network.packet.s2c.play.ScoreboardScoreUpdateS2CPacket
import net.minecraft.network.packet.s2c.play.ScoreboardDisplayS2CPacket
import net.minecraft.scoreboard.Scoreboard
import net.minecraft.scoreboard.ScoreboardCriterion
import net.minecraft.scoreboard.ScoreboardDisplaySlot
import net.minecraft.scoreboard.ScoreboardObjective
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import ru.pyxiion.ignis.unwrap
import java.util.Optional

class SidebarWrapper(val player: ServerPlayerEntity, initialTitle: String) {
    private val objectiveName = "pxrp_${player.uuid.toString().take(8)}"
    private val localScoreboard = Scoreboard()
    private val objective: ScoreboardObjective
    private val lines = LinkedHashMap<Int, String>()
    private var lastSentSlots = emptySet<Int>()
    var title: String = initialTitle
        private set
    var visible: Boolean = false
        private set

    init {
        objective = localScoreboard.addObjective(
            objectiveName,
            ScoreboardCriterion.DUMMY,
            Text.literal(initialTitle),
            ScoreboardCriterion.RenderType.INTEGER,
            false,
            null
        )
    }

    fun toLuaValue(): LuaValue {
        val t = LuaTable()
        t.setmetatable(MetaTableRegistry.SIDEBAR)
        t.rawset("__pxrp_type", LuaValue.valueOf("sidebar"))
        t.rawset("__pxrp_object", LuaValue.userdataOf(this))
        return t
    }

    fun setTitle(text: String) {
        title = text
        objective.displayName = Text.literal(text)
        if (visible) {
            player.networkHandler.sendPacket(
                ScoreboardObjectiveUpdateS2CPacket(objective, ScoreboardObjectiveUpdateS2CPacket.UPDATE_MODE)
            )
        }
    }

    fun setLine(line: Int, text: String) {
        if (line < 1) throw LuaError("Номер строки должен быть >= 1")
        lines[line] = text
        if (visible) resendLines()
    }

    fun setLinesFromTable(table: LuaTable) {
        if (visible) resetAllSlots()
        lines.clear()
        var i = 1
        while (true) {
            val v = table.rawget(i)
            if (v.isnil()) break
            if (v.isstring()) lines[i] = v.tojstring()
            i++
        }
        if (visible) resendLines()
    }

    fun show() {
        if (visible) return
        visible = true
        player.networkHandler.sendPacket(
            ScoreboardObjectiveUpdateS2CPacket(objective, ScoreboardObjectiveUpdateS2CPacket.ADD_MODE)
        )
        resendLines()
        player.networkHandler.sendPacket(
            ScoreboardDisplayS2CPacket(ScoreboardDisplaySlot.SIDEBAR, objective)
        )
    }

    fun hide() {
        if (!visible) return
        visible = false
        player.networkHandler.sendPacket(
            ScoreboardDisplayS2CPacket(ScoreboardDisplaySlot.SIDEBAR, null as ScoreboardObjective?)
        )
    }

    fun destroy() {
        SidebarManager.unregister(this)
        destroyInternals()
    }

    internal fun destroyInternals() {
        if (visible) {
            player.networkHandler.sendPacket(
                ScoreboardDisplayS2CPacket(ScoreboardDisplaySlot.SIDEBAR, null as ScoreboardObjective?)
            )
        }
        player.networkHandler.sendPacket(
            ScoreboardObjectiveUpdateS2CPacket(objective, ScoreboardObjectiveUpdateS2CPacket.REMOVE_MODE)
        )
    }

    private fun slotName(line: Int) = "_s$line"

    private fun resetAllSlots() {
        for (slot in lastSentSlots) {
            player.networkHandler.sendPacket(
                ScoreboardScoreResetS2CPacket(slotName(slot), objectiveName)
            )
        }
    }

    private fun resendLines() {
        resetAllSlots()
        lastSentSlots = lines.keys.toSet()
        val sorted = lines.entries.sortedBy { it.key }
        val size = sorted.size
        for ((idx, entry) in sorted.withIndex()) {
            val (lineNum, text) = entry
            val score = size - idx
            player.networkHandler.sendPacket(
                ScoreboardScoreUpdateS2CPacket(
                    slotName(lineNum),
                    objectiveName,
                    score,
                    Optional.of(Text.literal(text)),
                    Optional.empty()
                )
            )
        }
    }

    companion object {
        private val BUILT = metaTable<SidebarWrapper> {
            prop("title",
                get = { LuaValue.valueOf(title) },
                set = { v -> if (v.isstring()) setTitle(v.tojstring()) }
            )
            prop("lines",
                get = {
                    val t = LuaTable()
                    val sorted = lines.entries.sortedBy { it.key }
                    for ((idx, entry) in sorted.withIndex()) {
                        t.rawset(idx + 1, LuaValue.valueOf(entry.value))
                    }
                    t
                },
                set = { v -> if (v.istable()) setLinesFromTable(v.checktable()) }
            )
            prop("visible") { LuaValue.valueOf(visible) }
            prop("lineCount") { LuaValue.valueOf(lines.size) }

            method("setLine") { args ->
                val self = args.arg(1).checktable()
                val wrapper = self.unwrap<SidebarWrapper>()
                wrapper.setLine(args.arg(2).checkint(), args.arg(3).checkjstring())
                LuaValue.NIL
            }

            method("show") { args ->
                args.arg(1).checktable().unwrap<SidebarWrapper>().show()
                LuaValue.NIL
            }

            method("hide") { args ->
                args.arg(1).checktable().unwrap<SidebarWrapper>().hide()
                LuaValue.NIL
            }

            method("destroy") { args ->
                args.arg(1).checktable().unwrap<SidebarWrapper>().destroy()
                LuaValue.NIL
            }
        }

        fun initMeta(meta: LuaTable) {
            BUILT.apply(meta)
        }
    }
}
