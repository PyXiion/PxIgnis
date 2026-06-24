package ru.pyxiion.ignis.api.wrapper

import net.minecraft.entity.boss.BossBar
import net.minecraft.entity.boss.ServerBossBar
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.VarArgFunction
import ru.pyxiion.ignis.api.MetaTableRegistry
import ru.pyxiion.ignis.api.manager.BossBarManager
import ru.pyxiion.ignis.api.util.metaTable
import ru.pyxiion.ignis.unwrap

class BossBarWrapper(title: String, colorStr: String = "white", styleStr: String = "progress") {
    private val bar = ServerBossBar(
        Text.literal(title),
        parseColor(colorStr),
        parseStyle(styleStr),
    )

    var title: String
        get() = bar.name.string
        set(v) { bar.name = Text.literal(v) }

    var progress: Float
        get() = bar.percent
        set(v) { bar.percent = v.coerceIn(0f, 1f) }

    var color: String
        get() = bar.color.name.lowercase()
        set(v) { bar.color = parseColor(v) }

    var style: String
        get() = bar.style.name.lowercase()
        set(v) { bar.style = parseStyle(v) }

    var visible: Boolean
        get() = bar.isVisible
        set(v) { bar.isVisible = v }

    fun addPlayer(player: ServerPlayerEntity) {
        bar.addPlayer(player)
    }

    fun removePlayer(player: ServerPlayerEntity) {
        bar.removePlayer(player)
    }

    fun destroy() {
        bar.clearPlayers()
        BossBarManager.unregister(this)
    }

    internal fun destroyInternals() {
        bar.clearPlayers()
    }

    fun toLuaValue(): LuaValue {
        val t = LuaTable()
        t.setmetatable(MetaTableRegistry.BOSS_BAR)
        t.rawset("__pxrp_type", LuaValue.valueOf("bossbar"))
        t.rawset("__pxrp_object", LuaValue.userdataOf(this))
        return t
    }

    companion object {
        private val BUILT = metaTable<BossBarWrapper> {
            prop(
                "title",
                get = { LuaValue.valueOf(title) },
                set = { v -> title = v.tojstring() }
            )
            prop(
                "progress",
                get = { LuaValue.valueOf(progress.toDouble()) },
                set = { v -> progress = v.tofloat() }
            )
            prop(
                "color",
                get = { LuaValue.valueOf(color) },
                set = { v -> color = v.tojstring() }
            )
            prop(
                "style",
                get = { LuaValue.valueOf(style) },
                set = { v -> style = v.tojstring() }
            )
            prop(
                "visible",
                get = { if (visible) LuaValue.TRUE else LuaValue.FALSE },
                set = { v -> visible = v.toboolean() }
            )

            method("addPlayer") { args: Varargs ->
                val self = args.arg(1).checktable()
                val wrapper = self.unwrap<BossBarWrapper>()
                val player = args.arg(2).checktable().unwrap<ServerPlayerEntity>()
                wrapper.addPlayer(player)
                LuaValue.NIL
            }

            method("removePlayer") { args: Varargs ->
                val self = args.arg(1).checktable()
                val wrapper = self.unwrap<BossBarWrapper>()
                val player = args.arg(2).checktable().unwrap<ServerPlayerEntity>()
                wrapper.removePlayer(player)
                LuaValue.NIL
            }

            method("destroy") { args: Varargs ->
                args.arg(1).checktable().unwrap<BossBarWrapper>().destroy()
                LuaValue.NIL
            }
        }

        fun initMeta(meta: LuaTable) {
            BUILT.apply(meta)
        }

        private fun parseColor(s: String): BossBar.Color {
            return BossBar.Color.valueOf(s.uppercase())
        }

        private fun parseStyle(s: String): BossBar.Style {
            return BossBar.Style.valueOf(s.uppercase())
        }
    }
}
