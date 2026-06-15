package ru.pyxiion.ignis.api

import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaFunction
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.lib.jse.CoerceJavaToLua
import ru.pyxiion.ignis.luaTableOf
import ru.pyxiion.ignis.toVec3d

class RegionWrapper(private val region: Region) {

    fun toLuaValue(): LuaValue {
        val t = LuaTable()
        t.setmetatable(MetaTableRegistry.REGION)
        t.rawset("__pxrp_type", LuaValue.valueOf("region"))
        t.rawset("__pxrp_object", CoerceJavaToLua.coerce(region))
        return t
    }

    companion object {
        internal val regionKeys = listOf(
            "id", "world", "getBounds", "players", "entities",
            "setBounds", "on", "off", "destroy", "contains",
        )

        fun initMeta(meta: LuaTable) {
            meta.set("__index", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val key = args.arg(2).tojstring()
                    val region = self.rawget("__pxrp_object").checkuserdata() as Region

                    return when (key) {
                        "id" -> LuaValue.valueOf(region.id)
                        "world" -> WorldWrapper(region.world).toLuaValue()
                        "getBounds" -> {
                            val b = region.bounds
                            luaTableOf(
                                "A" to Vector(b.minX, b.minY, b.minZ).toLuaValue(),
                                "B" to Vector(b.maxX, b.maxY, b.maxZ).toLuaValue(),
                            )
                        }
                        "players" -> {
                            val t = LuaTable()
                            region.livePlayers().forEachIndexed { i, p ->
                                t.set(i + 1, PlayerWrapper(p).toLuaValue())
                            }
                            t
                        }
                        "entities" -> {
                            val t = LuaTable()
                            region.liveEntities().forEachIndexed { i, e ->
                                t.set(i + 1, when (e) {
                                    is net.minecraft.server.network.ServerPlayerEntity -> PlayerWrapper(e).toLuaValue()
                                    else -> EntityWrapper(e).toLuaValue()
                                })
                            }
                            t
                        }
                        else -> meta.get(key)
                    }
                }
            })

            meta.set("__pairs", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1)
                    val keys = regionKeys
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

            meta.rawset("on", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val region = self.rawget("__pxrp_object").checkuserdata() as Region
                    val event = args.arg(2).checkjstring()
                    val callback = args.arg(3).checkfunction()
                    val throttle = if (args.narg() >= 4 && args.arg(4).istable()) {
                        val t = args.arg(4).checktable()
                        val v = t.get("throttle").optint(0)
                        if (v < 0) throw LuaError("on: throttle должен быть >= 0")
                        v
                    } else 0
                    val id = region.on(event, callback, throttle)
                    return LuaValue.valueOf(id)
                }
            })

            meta.rawset("off", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val region = self.rawget("__pxrp_object").checkuserdata() as Region
                    val id = args.arg(2).checkint()
                    return LuaValue.valueOf(region.off(id))
                }
            })

            meta.rawset("destroy", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val region = self.rawget("__pxrp_object").checkuserdata() as Region
                    region.destroy()
                    return LuaValue.NIL
                }
            })

            meta.rawset("contains", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val region = self.rawget("__pxrp_object").checkuserdata() as Region
                    val pos = args.arg(2).toVec3d()
                    return LuaValue.valueOf(region.contains(pos))
                }
            })

            meta.rawset("setBounds", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val region = self.rawget("__pxrp_object").checkuserdata() as Region
                    val a = args.arg(2).toVec3d()
                    val b = args.arg(3).toVec3d()
                    region.setCorners(a, b)
                    return LuaValue.NIL
                }
            })
        }
    }
}
