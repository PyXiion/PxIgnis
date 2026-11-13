package ru.pyxiion.ignis.api.wrapper

import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import ru.pyxiion.ignis.api.MetaTableRegistry
import ru.pyxiion.ignis.api.Vector
import ru.pyxiion.ignis.api.Vector.Companion.toVec3d
import ru.pyxiion.ignis.api.manager.DisplayHandle
import ru.pyxiion.ignis.api.DisplayLib
import ru.pyxiion.ignis.api.util.metaTable
import ru.pyxiion.ignis.unwrap
import ru.pyxiion.ignis.unwrapOrNull
import ru.pyxiion.ignis.wrapObject
import ru.pyxiion.luanova.vm2.LuaError
import ru.pyxiion.luanova.vm2.LuaTable
import ru.pyxiion.luanova.vm2.LuaValue

/** Lua handle of a virtual display (`world:display`). The per-handle state lives in [DisplayHandle]. */
object DisplayWrap {
    fun wrap(handle: DisplayHandle): LuaTable = wrapObject(MetaTableRegistry.DISPLAY, "display", handle)

    private val BUILT by lazy {
        metaTable<DisplayHandle> {
            prop("valid") { LuaValue.valueOf(valid) }
            prop("kind") { LuaValue.valueOf(kind) }
            prop("id") { LuaValue.valueOf(id) }
            prop("pos") { Vector.of(pos.x, pos.y, pos.z) }
            prop("yaw") { LuaValue.valueOf(yaw.toDouble()) }
            prop("pitch") { LuaValue.valueOf(pitch.toDouble()) }

            // h:set(props)
            method("set") { args ->
                args.arg(1).unwrap<DisplayHandle>().let { h ->
                    check(h.valid) { "display removed" }
                    DisplayLib.apply(h, args.arg(2).checktable())
                }
                LuaValue.NIL
            }
            // h:moveTo(pos, yaw?, pitch?)
            method("moveTo") { args ->
                val h = args.arg(1).unwrap<DisplayHandle>()
                h.moveTo(
                    args.arg(2).toVec3d(),
                    args.arg(3).let { if (it.isnil()) null else it.checkdouble().toFloat() },
                    args.arg(4).let { if (it.isnil()) null else it.checkdouble().toFloat() },
                )
                LuaValue.NIL
            }
            // h:attach(entity, offset?)
            method("attach") { args ->
                val h = args.arg(1).unwrap<DisplayHandle>()
                val host = args.arg(2).unwrapOrNull<Entity>() ?: throw LuaError("display:attach: an entity is expected")
                h.attach(host, args.arg(3).let { if (it.isnil()) null else it.toVec3d() })
                LuaValue.NIL
            }
            method("detach") { args -> args.arg(1).unwrap<DisplayHandle>().detach(); LuaValue.NIL }
            method("show") { args -> args.arg(1).unwrap<DisplayHandle>().show(player(args.arg(2))); LuaValue.NIL }
            method("hide") { args -> args.arg(1).unwrap<DisplayHandle>().hide(player(args.arg(2))); LuaValue.NIL }
            method("remove") { args -> args.arg(1).unwrap<DisplayHandle>().remove(); LuaValue.NIL }
            // h:onClick(fn(player, hand)) / h:onAttack(fn(player)); interaction displays only
            method("onClick") { args ->
                val h = interaction(args.arg(1), "onClick")
                h.onClick = args.arg(2).let { if (it.isnil()) null else it.checkfunction() }
                LuaValue.NIL
            }
            method("onAttack") { args ->
                val h = interaction(args.arg(1), "onAttack")
                h.onAttack = args.arg(2).let { if (it.isnil()) null else it.checkfunction() }
                LuaValue.NIL
            }
            toString { "display($kind#$id)" }
        }
    }

    private fun player(v: LuaValue): ServerPlayer =
        v.unwrapOrNull<ServerPlayer>() ?: throw LuaError("a player is expected")

    private fun interaction(self: LuaValue, what: String): DisplayHandle {
        val h = self.unwrap<DisplayHandle>()
        if (h.kind != "interaction") throw LuaError("display:$what is only available for kind \"interaction\"")
        return h
    }

    fun initMeta(meta: LuaTable) {
        BUILT.apply(meta)
    }
}
