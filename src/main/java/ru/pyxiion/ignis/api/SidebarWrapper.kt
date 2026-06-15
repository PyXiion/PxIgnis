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
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.lib.jse.CoerceJavaToLua
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
        t.rawset("__pxrp_object", CoerceJavaToLua.coerce(this))
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
        private val sidebarKeys = listOf("title", "lines", "visible", "lineCount")

        fun initMeta(meta: LuaTable) {
            meta.set("__index", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val key = args.arg(2).tojstring()
                    val wrapper = self.rawget("__pxrp_object").checkuserdata() as SidebarWrapper

                    return when (key) {
                        "title" -> LuaValue.valueOf(wrapper.title)
                        "visible" -> LuaValue.valueOf(wrapper.visible)
                        "lineCount" -> LuaValue.valueOf(wrapper.lines.size)
                        "lines" -> {
                            val t = LuaTable()
                            val sorted = wrapper.lines.entries.sortedBy { it.key }
                            for ((idx, entry) in sorted.withIndex()) {
                                t.rawset(idx + 1, LuaValue.valueOf(entry.value))
                            }
                            t
                        }
                        else -> meta.get(key)
                    }
                }
            })

            meta.set("__newindex", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val key = args.arg(2).tojstring()
                    val value = args.arg(3)
                    val wrapper = self.rawget("__pxrp_object").checkuserdata() as SidebarWrapper

                    when (key) {
                        "title" -> {
                            if (value.isstring()) wrapper.setTitle(value.tojstring())
                        }
                        "lines" -> {
                            if (value.istable()) wrapper.setLinesFromTable(value.checktable())
                        }
                    }
                    return LuaValue.NIL
                }
            })

            meta.set("__pairs", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1)
                    val keys = sidebarKeys
                    val iterator = object : VarArgFunction() {
                        private var index = 0
                        override fun invoke(args: Varargs): Varargs {
                            if (index >= keys.size) return LuaValue.NIL
                            val key = keys[index]
                            index++
                            val value = self.get(key)
                            return LuaValue.varargsOf(arrayOf(LuaValue.valueOf(key), value))
                        }
                    }
                    return LuaValue.varargsOf(arrayOf(iterator, self, LuaValue.NIL))
                }
            })

            meta.rawset("setLine", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val wrapper = self.rawget("__pxrp_object").checkuserdata() as SidebarWrapper
                    val line = args.arg(2).checkint()
                    val text = args.arg(3).checkjstring()
                    wrapper.setLine(line, text)
                    return LuaValue.NIL
                }
            })

            meta.rawset("show", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val wrapper = self.rawget("__pxrp_object").checkuserdata() as SidebarWrapper
                    wrapper.show()
                    return LuaValue.NIL
                }
            })

            meta.rawset("hide", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val wrapper = self.rawget("__pxrp_object").checkuserdata() as SidebarWrapper
                    wrapper.hide()
                    return LuaValue.NIL
                }
            })

            meta.rawset("destroy", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val wrapper = self.rawget("__pxrp_object").checkuserdata() as SidebarWrapper
                    wrapper.destroy()
                    return LuaValue.NIL
                }
            })
        }
    }
}
