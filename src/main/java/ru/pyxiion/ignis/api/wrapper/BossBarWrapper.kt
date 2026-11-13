package ru.pyxiion.ignis.api.wrapper

import ru.pyxiion.ignis.wrapObject
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.BossEvent
import ru.pyxiion.luanova.vm2.LuaError
import ru.pyxiion.luanova.vm2.LuaTable
import ru.pyxiion.luanova.vm2.LuaValue
import ru.pyxiion.luanova.vm2.Varargs
import ru.pyxiion.ignis.Compat
import ru.pyxiion.ignis.api.MetaTableRegistry
import ru.pyxiion.ignis.api.manager.BossBarManager
import ru.pyxiion.ignis.api.util.metaTable
import ru.pyxiion.ignis.unwrap

class BossBarWrapper(title: String, colorStr: String = "white", styleStr: String = "progress") {
    private val bar = Compat.createBossEvent(
        Component.literal(title),
        parseColor(colorStr),
        parseStyle(styleStr),
    )

    var title: String
        get() = bar.name.string
        set(v) { bar.name = Component.literal(v) }

    var progress: Float
        get() = bar.progress
        set(v) { bar.progress = v.coerceIn(0f, 1f) }

    var color: String
        get() = bar.color.name.lowercase()
        set(v) { bar.color = parseColor(v) }

    var style: String
        get() = bar.overlay.name.lowercase()
        set(v) { bar.overlay = parseStyle(v) }

    var visible: Boolean
        get() = bar.isVisible
        set(v) { bar.isVisible = v }

    fun addPlayer(player: ServerPlayer) {
        bar.addPlayer(player)
    }

    fun removePlayer(player: ServerPlayer) {
        bar.removePlayer(player)
    }

    fun destroy() {
        bar.removeAllPlayers()
        BossBarManager.unregister(this)
    }

    internal fun destroyInternals() {
        bar.removeAllPlayers()
    }

    fun toLuaValue(): LuaValue {
        return wrapObject(MetaTableRegistry.BOSS_BAR, "bossbar", this)
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
                val player = args.arg(2).checktable().unwrap<ServerPlayer>()
                wrapper.addPlayer(player)
                LuaValue.NIL
            }

            method("removePlayer") { args: Varargs ->
                val self = args.arg(1).checktable()
                val wrapper = self.unwrap<BossBarWrapper>()
                val player = args.arg(2).checktable().unwrap<ServerPlayer>()
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

        private fun parseColor(s: String): BossEvent.BossBarColor =
            BossEvent.BossBarColor.entries.firstOrNull { it.name.equals(s, ignoreCase = true) }
                ?: throw LuaError(
                    "Неизвестный цвет боссбара '$s' (допустимо: ${BossEvent.BossBarColor.entries.joinToString { it.name.lowercase() }})"
                )

        private fun parseStyle(s: String): BossEvent.BossBarOverlay =
            BossEvent.BossBarOverlay.entries.firstOrNull { it.name.equals(s, ignoreCase = true) }
                ?: throw LuaError(
                    "Неизвестный стиль боссбара '$s' (допустимо: ${BossEvent.BossBarOverlay.entries.joinToString { it.name.lowercase() }})"
                )
    }
}
