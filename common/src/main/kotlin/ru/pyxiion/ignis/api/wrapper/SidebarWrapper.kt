package ru.pyxiion.ignis.api.wrapper

import net.minecraft.network.protocol.game.ClientboundResetScorePacket
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket
import net.minecraft.network.protocol.game.ClientboundSetScorePacket
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.Objective
import net.minecraft.world.scores.Scoreboard
import net.minecraft.world.scores.criteria.ObjectiveCriteria
import net.minecraft.server.level.ServerPlayer
import net.minecraft.network.chat.Component
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import ru.pyxiion.ignis.api.MetaTableRegistry
import ru.pyxiion.ignis.api.manager.SidebarManager
import ru.pyxiion.ignis.api.util.metaTable
import ru.pyxiion.ignis.unwrap
import java.util.Optional

class SidebarWrapper(val player: ServerPlayer, initialTitle: String) {
    private val objectiveName = "pxrp_${player.uuid.toString().take(8)}"
    private val localScoreboard = Scoreboard()
    private val objective: Objective
    private val lines = LinkedHashMap<Int, String>()
    private var lastSentSlots = emptySet<Int>()
    var title: String = initialTitle
        private set
    var visible: Boolean = false
        private set

    init {
        objective = localScoreboard.addObjective(
            objectiveName,
            ObjectiveCriteria.DUMMY,
            Component.literal(initialTitle),
            ObjectiveCriteria.RenderType.INTEGER,
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
        objective.displayName = Component.literal(text)
        if (visible) {
            player.connection.send(
                ClientboundSetObjectivePacket(objective, ClientboundSetObjectivePacket.METHOD_CHANGE)
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
        player.connection.send(
            ClientboundSetObjectivePacket(objective, ClientboundSetObjectivePacket.METHOD_ADD)
        )
        resendLines()
        player.connection.send(
            ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, objective)
        )
    }

    fun hide() {
        if (!visible) return
        visible = false
        player.connection.send(
            ClientboundSetObjectivePacket(objective, ClientboundSetObjectivePacket.METHOD_REMOVE)
        )
    }

    fun destroy() {
        SidebarManager.unregister(this)
        destroyInternals()
    }

    internal fun destroyInternals() {
        if (visible) {
            player.connection.send(
                ClientboundSetObjectivePacket(objective, ClientboundSetObjectivePacket.METHOD_REMOVE)
            )
        }
        player.connection.send(
            ClientboundSetObjectivePacket(objective, ClientboundSetObjectivePacket.METHOD_REMOVE)
        )
    }

    private fun slotName(line: Int) = "_s$line"

    private fun resetAllSlots() {
        for (slot in lastSentSlots) {
            player.connection.send(
                ClientboundResetScorePacket(slotName(slot), objectiveName)
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
            player.connection.send(
                ClientboundSetScorePacket(
                    slotName(lineNum),
                    objectiveName,
                    score,
                    Optional.of(Component.literal(text)),
                    Optional.empty()
                )
            )
        }
    }

    companion object {
        private val BUILT = metaTable<SidebarWrapper> {
            prop(
                "title",
                get = { LuaValue.valueOf(title) },
                set = { v -> if (v.isstring()) setTitle(v.tojstring()) }
            )
            prop(
                "lines",
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
