package ru.pyxiion.ignis.api

import net.minecraft.entity.Entity
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.attribute.EntityAttributes
import net.minecraft.entity.mob.MobEntity
import net.minecraft.server.world.ServerWorld
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.lib.jse.CoerceJavaToLua
import ru.pyxiion.ignis.toVec3d
import java.util.UUID

class MobWrapper(private val mob: MobEntity) {

    fun toLuaValue(): LuaValue {
        val t = LuaTable()
        t.setmetatable(MetaTableRegistry.MOB)
        t.rawset("__pxrp_type", LuaValue.valueOf("mob"))
        t.rawset("__pxrp_object", CoerceJavaToLua.coerce(mob))

        return t
    }

    companion object {
        private val mobKeys = listOf(
            "isMob", "aiActive", "target", "speed", "age",
            "pathRemaining", "pathFound",
            "setAI", "clearAI", "navigateTo", "stopNavigation",
            "lookAt", "moveToward", "jump", "tryAttack",
            "canSee", "distanceTo",
        )

        fun initMeta(meta: LuaTable) {
            meta.set("__index", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val key = args.arg(2).tojstring()
                    val m = self.rawget("__pxrp_object").checkuserdata() as MobEntity

                    return when (key) {
                        "isMob" -> LuaValue.TRUE
                        "aiActive" -> LuaValue.valueOf(MobAIManager.hasAI(m))
                        "target" -> {
                            m.target?.let { EntityWrapper(it).toLuaValue() } ?: LuaValue.NIL
                        }
                        "speed" -> {
                            m.getAttributeInstance(EntityAttributes.MOVEMENT_SPEED)?.value?.let {
                                LuaValue.valueOf(it)
                            } ?: LuaValue.NIL
                        }
                        "age" -> {
                            valueOf(m.age)
                        }
                        "pathRemaining" -> {
                            val nav = m.navigation
                            if (!nav.isIdle) {
                                val path = nav.currentPath
                                if (path != null) {
                                    val len = path.length
                                    if (len > 0) {
                                        val nodeIdx = path.currentNodeIndex
                                        val progress = 1.0 - (nodeIdx.toDouble() / len.toDouble())
                                        LuaValue.valueOf(progress.coerceIn(0.0, 1.0))
                                    } else LuaValue.valueOf(0.0)
                                } else LuaValue.valueOf(0.0)
                            } else LuaValue.valueOf(0.0)
                        }
                        "pathFound" -> LuaValue.valueOf(!m.navigation.isIdle)

                        else -> {
                            val metaVal = meta.get(key)
                            if (!metaVal.isnil()) return metaVal

                            val entityIndex = MetaTableRegistry.ENTITY.get("__index")
                            if (entityIndex.isfunction()) {
                                return entityIndex.invoke(LuaValue.varargsOf(arrayOf(self, LuaValue.valueOf(key))))
                            }
                            LuaValue.NIL
                        }
                    }
                }
            })

            meta.set("__newindex", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val key = args.arg(2).tojstring()
                    val value = args.arg(3)
                    val m = self.rawget("__pxrp_object").checkuserdata() as MobEntity

                    when (key) {
                        "target" -> {
                            if (value.isnil()) {
                                m.target = null
                            } else {
                                val uuidStr = value.checktable().get("uuid")
                                if (uuidStr.isstring()) {
                                    val uuid = UUID.fromString(uuidStr.tojstring())
                                    val entity = m.entityWorld.getEntity(uuid)
                                    if (entity is LivingEntity) {
                                        m.target = entity
                                    } else {
                                        m.target = null
                                    }
                                }
                            }
                        }
                        "speed" -> {
                            m.getAttributeInstance(EntityAttributes.MOVEMENT_SPEED)?.baseValue = value.todouble()
                        }
                        else -> {
                            val entityNewIndex = MetaTableRegistry.ENTITY.get("__newindex")
                            if (entityNewIndex.isfunction()) {
                                entityNewIndex.invoke(LuaValue.varargsOf(arrayOf(self, LuaValue.valueOf(key), value)))
                            }
                        }
                    }
                    return LuaValue.NIL
                }
            })

            meta.set("__pairs", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1)
                    val entityKeys = EntityWrapper.entityKeys
                    val allKeys = mobKeys + entityKeys
                    val iterator = object : VarArgFunction() {
                        private var index = 0
                        override fun invoke(args: Varargs): Varargs {
                            if (index >= allKeys.size) return LuaValue.NIL
                            val key = allKeys[index]
                            index++
                            val value = self.get(key)
                            return LuaValue.varargsOf(arrayOf(LuaValue.valueOf(key), value))
                        }
                    }
                    return LuaValue.varargsOf(arrayOf(iterator, self, LuaValue.NIL))
                }
            })

            meta.rawset("setAI", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val m = self.rawget("__pxrp_object").checkuserdata() as MobEntity
                    MobAIManager.setAI(m, args.arg(2))
                    return LuaValue.NIL
                }
            })

            meta.rawset("clearAI", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val m = self.rawget("__pxrp_object").checkuserdata() as MobEntity
                    MobAIManager.clearAI(m)
                    return LuaValue.NIL
                }
            })

            meta.rawset("navigateTo", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val m = self.rawget("__pxrp_object").checkuserdata() as MobEntity

                    val result: Boolean = if (args.arg(2).istable()) {
                        val entityTable = args.arg(2).checktable()
                        val uuidStr = entityTable.get("uuid").optjstring(null) ?: return LuaValue.FALSE
                        val target = m.entityWorld.getEntity(UUID.fromString(uuidStr)) ?: return LuaValue.FALSE
                        val speed = args.arg(3).optdouble(1.0)
                        m.navigation.startMovingTo(target, speed)
                    } else {
                        val x = args.arg(2).checkdouble()
                        val y = args.arg(3).checkdouble()
                        val z = args.arg(4).checkdouble()
                        val speed = args.arg(5).optdouble(1.0)
                        m.navigation.startMovingTo(x, y, z, speed)
                    }
                    return LuaValue.valueOf(result)
                }
            })

            meta.rawset("stopNavigation", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val m = self.rawget("__pxrp_object").checkuserdata() as MobEntity
                    m.navigation.stop()
                    return LuaValue.NIL
                }
            })

            meta.rawset("lookAt", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val m = self.rawget("__pxrp_object").checkuserdata() as MobEntity

                    if (args.arg(3).isnil() && args.arg(2).istable()) {
                        val entityTable = args.arg(2).checktable()
                        val uuidStr = entityTable.get("uuid").optjstring(null) ?: return LuaValue.NIL
                        val target = m.entityWorld.getEntity(UUID.fromString(uuidStr)) ?: return LuaValue.NIL
                        m.lookControl.lookAt(target)
                    } else {
                        val x = args.arg(2).checkdouble()
                        val y = args.arg(3).checkdouble()
                        val z = args.arg(4).checkdouble()
                        m.lookControl.lookAt(x, y, z)
                    }
                    return LuaValue.NIL
                }
            })

            meta.rawset("moveToward", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val m = self.rawget("__pxrp_object").checkuserdata() as MobEntity
                    val pos = args.arg(2).toVec3d()
                    val speed = args.arg(3).optdouble(1.0)
                    m.navigation.stop()
                    m.moveControl.moveTo(pos.x, pos.y, pos.z, speed)
                    return LuaValue.NIL
                }
            })

            meta.rawset("jump", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val m = self.rawget("__pxrp_object").checkuserdata() as MobEntity
                    m.jumpControl.setActive()
                    return LuaValue.NIL
                }
            })

            meta.rawset("tryAttack", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val m = self.rawget("__pxrp_object").checkuserdata() as MobEntity
                    val targetArg = args.arg(2)
                    val target = if (targetArg.istable()) {
                        targetArg.checktable().rawget("__pxrp_object").checkuserdata() as Entity
                    } else {
                        targetArg.checkuserdata() as Entity
                    }
                    val result = m.tryAttack(m.entityWorld as ServerWorld, target)
                    return LuaValue.valueOf(result)
                }
            })

            meta.rawset("canSee", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val m = self.rawget("__pxrp_object").checkuserdata() as MobEntity
                    val targetArg = args.arg(2)
                    val targetEntity: Entity? = if (targetArg.istable()) {
                        val uuidStr = targetArg.checktable().get("uuid").optjstring(null)
                        if (uuidStr != null) {
                            m.entityWorld.getEntity(UUID.fromString(uuidStr))
                        } else {
                            targetArg.checktable().rawget("__pxrp_object").checkuserdata() as Entity
                        }
                    } else if (targetArg.isuserdata()) {
                        targetArg.checkuserdata() as Entity
                    } else null

                    if (targetEntity == null) return LuaValue.FALSE
                    return LuaValue.valueOf(m.canSee(targetEntity))
                }
            })

            meta.rawset("distanceTo", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val m = self.rawget("__pxrp_object").checkuserdata() as MobEntity
                    val targetArg = args.arg(2)
                    val result: Double = if (targetArg.istable()) {
                        if (targetArg.checktable().rawget("__pxrp_object").isnil()) {
                            val pos = targetArg.toVec3d()
                            m.squaredDistanceTo(pos.x, pos.y, pos.z)
                        } else {
                            val entity = targetArg.checktable().rawget("__pxrp_object").checkuserdata() as Entity
                            m.squaredDistanceTo(entity)
                        }
                    } else if (targetArg.isuserdata()) {
                        m.squaredDistanceTo(targetArg.checkuserdata() as Entity)
                    } else {
                        throw LuaError("distanceTo: ожидается entity или позиция")
                    }
                    return LuaValue.valueOf(Math.sqrt(result))
                }
            })
        }
    }
}
