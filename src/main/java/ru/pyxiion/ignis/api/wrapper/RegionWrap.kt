package ru.pyxiion.ignis.api.wrapper

import net.minecraft.server.network.ServerPlayerEntity
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import ru.pyxiion.ignis.api.wrapper.EntityWrap
import ru.pyxiion.ignis.api.MetaTableRegistry
import ru.pyxiion.ignis.api.Vector
import ru.pyxiion.ignis.api.manager.Region
import ru.pyxiion.ignis.api.util.metaTable
import ru.pyxiion.ignis.luaTableOf
import ru.pyxiion.ignis.toVec3d
import ru.pyxiion.ignis.unwrap

object RegionWrap {

    fun wrap(region: Region): LuaValue {
        val t = LuaTable()
        t.setmetatable(MetaTableRegistry.REGION)
        t.rawset("__pxrp_type", LuaValue.valueOf("region"))
        t.rawset("__pxrp_object", LuaValue.userdataOf(region))
        return t
    }

    private val BUILT by lazy {
        metaTable<Region> {
            prop("id") { LuaValue.valueOf(id) }
            prop("world") { WorldWrap.wrap(world) }
            prop("players") {
                val t = LuaTable()
                livePlayers().forEachIndexed { i, p -> t.set(i + 1, PlayerWrap.wrap(p)) }
                t
            }
            prop("entities") {
                val t = LuaTable()
                liveEntities().forEachIndexed { i, e ->
                    t.set(
                        i + 1, when (e) {
                            is ServerPlayerEntity -> PlayerWrap.wrap(e)
                            else -> EntityWrap.wrap(e)
                        }
                    )
                }
                t
            }

            method("on") { args ->
                val self = args.arg(1).checktable()
                val region = self.unwrap<Region>()
                val event = args.arg(2).checkjstring()
                val callback = args.arg(3).checkfunction()
                val throttle = if (args.narg() >= 4 && args.arg(4).istable()) {
                    val t = args.arg(4).checktable()
                    val v = t.get("throttle").optint(0)
                    if (v < 0) throw LuaError("on: throttle должен быть >= 0")
                    v
                } else 0
                LuaValue.valueOf(region.on(event, callback, throttle))
            }

            method("off") { args ->
                val self = args.arg(1).checktable()
                val region = self.unwrap<Region>()
                LuaValue.valueOf(region.off(args.arg(2).checkint()))
            }

            method("destroy") { args ->
                val self = args.arg(1).checktable()
                self.unwrap<Region>().destroy()
                LuaValue.NIL
            }

            method("contains") { args ->
                val self = args.arg(1).checktable()
                val region = self.unwrap<Region>()
                val pos = args.arg(2).toVec3d()
                LuaValue.valueOf(region.contains(pos))
            }

            method("getBounds") { args ->
                val self = args.arg(1).checktable()
                val region = self.unwrap<Region>()
                with(region.bounds) {
                    luaTableOf(
                        "A" to Vector(minX, minY, minZ).toLuaValue(),
                        "B" to Vector(maxX, maxY, maxZ).toLuaValue(),
                    )
                }
            }

            method("setBounds") { args ->
                val self = args.arg(1).checktable()
                val region = self.unwrap<Region>()
                val a = args.arg(2).toVec3d()
                val b = args.arg(3).toVec3d()
                region.setCorners(a, b)
                LuaValue.NIL
            }

            toString { "[Region #$id, ${this.bounds}]" }
        }
    }

    fun initMeta(meta: LuaTable) {
        BUILT.apply(meta)
    }
}
