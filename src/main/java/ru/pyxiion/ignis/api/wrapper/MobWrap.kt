package ru.pyxiion.ignis.api.wrapper

import net.minecraft.entity.Entity
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.mob.MobEntity
import net.minecraft.server.world.ServerWorld
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import ru.pyxiion.ignis.api.wrapper.EntityWrap
import ru.pyxiion.ignis.api.MetaTableRegistry
import ru.pyxiion.ignis.api.manager.MobAIManager
import ru.pyxiion.ignis.api.util.metaTable
import ru.pyxiion.ignis.toVec3d
import ru.pyxiion.ignis.unwrap
import ru.pyxiion.ignis.unwrapOrNull
import java.util.UUID

object MobWrap {

    fun wrap(mob: MobEntity): LuaValue {
        val t = LuaTable()
        t.setmetatable(MetaTableRegistry.MOB)
        t.rawset("__pxrp_type", LuaValue.valueOf("mob"))
        t.rawset("__pxrp_object", LuaValue.userdataOf(mob))
        return t
    }

    private val BUILT = metaTable<MobEntity> {
        inherit(EntityWrap.BUILT)

        prop("isMob") { LuaValue.TRUE }
        prop("aiActive") { LuaValue.valueOf(MobAIManager.hasAI(this)) }
        prop(
            "target",
            get = {
                target?.let { EntityWrap.wrap(it) } ?: LuaValue.NIL
            },
            set = { v ->
                if (v.isnil()) {
                    target = null
                } else {
                    val uuidStr = v.checktable().get("uuid")
                    if (uuidStr.isstring()) {
                        val uuid = UUID.fromString(uuidStr.tojstring())
                        val entity = entityWorld.getEntity(uuid)
                        target = entity as? LivingEntity
                    }
                }
            }
        )
        prop("age") { LuaValue.valueOf(age) }
        prop("pathRemaining") {
            val nav = navigation
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
        prop("pathFound") { LuaValue.valueOf(!navigation.isIdle) }

        method("setAI") { args ->
            val self = args.arg(1).checktable()
            MobAIManager.setAI(self.unwrap<MobEntity>(), args.arg(2))
            LuaValue.NIL
        }

        method("clearAI") { args ->
            args.arg(1).checktable().unwrap<MobEntity>()
                .let { MobAIManager.clearAI(it) }
            LuaValue.NIL
        }

        method("navigateTo") { args ->
            val self = args.arg(1).checktable()
            val m = self.unwrap<MobEntity>()

            val result: Boolean = if (args.arg(2).istable()) {
                val entityTable = args.arg(2).checktable()
                val uuidStr = entityTable.get("uuid").optjstring(null) ?: return@method LuaValue.FALSE
                val target = m.entityWorld.getEntity(UUID.fromString(uuidStr)) ?: return@method LuaValue.FALSE
                val speed = args.arg(3).optdouble(1.0)
                m.navigation.startMovingTo(target, speed)
            } else {
                val x = args.arg(2).checkdouble()
                val y = args.arg(3).checkdouble()
                val z = args.arg(4).checkdouble()
                val speed = args.arg(5).optdouble(1.0)
                m.navigation.startMovingTo(x, y, z, speed)
            }
            LuaValue.valueOf(result)
        }

        method("stopNavigation") { args ->
            args.arg(1).checktable().unwrap<MobEntity>().navigation.stop()
            LuaValue.NIL
        }

        method("lookAt") { args ->
            val self = args.arg(1).checktable()
            val m = self.unwrap<MobEntity>()

            if (args.arg(3).isnil() && args.arg(2).istable()) {
                val entityTable = args.arg(2).checktable()
                val uuidStr = entityTable.get("uuid").optjstring(null) ?: return@method LuaValue.NIL
                val target = m.entityWorld.getEntity(UUID.fromString(uuidStr)) ?: return@method LuaValue.NIL
                m.lookControl.lookAt(target)
            } else {
                val x = args.arg(2).checkdouble()
                val y = args.arg(3).checkdouble()
                val z = args.arg(4).checkdouble()
                m.lookControl.lookAt(x, y, z)
            }
            LuaValue.NIL
        }

        method("moveToward") { args ->
            val self = args.arg(1).checktable()
            val m = self.unwrap<MobEntity>()
            val pos = args.arg(2).toVec3d()
            val speed = args.arg(3).optdouble(1.0)
            m.navigation.stop()
            m.moveControl.moveTo(pos.x, pos.y, pos.z, speed)
            LuaValue.NIL
        }

        method("jump") { args ->
            args.arg(1).checktable().unwrap<MobEntity>().jumpControl.setActive()
            LuaValue.NIL
        }

        method("tryAttack") { args ->
            val self = args.arg(1).checktable()
            val m = self.unwrap<MobEntity>()
            val target = args.arg(2).unwrap<Entity>()
            LuaValue.valueOf(m.tryAttack(m.entityWorld as ServerWorld, target))
        }

        method("canSee") { args ->
            val self = args.arg(1).checktable()
            val m = self.unwrap<MobEntity>()
            val targetEntity = args.arg(2).unwrapOrNull<Entity>()
            if (targetEntity == null) return@method LuaValue.FALSE
            LuaValue.valueOf(m.canSee(targetEntity))
        }

        method("distanceTo") { args ->
            val self = args.arg(1).checktable()
            val m = self.unwrap<MobEntity>()
            val targetArg = args.arg(2)
            val result: Double = if (targetArg.istable()) {
                targetArg.unwrapOrNull<Entity>()?.let { m.squaredDistanceTo(it) }
                    ?: m.squaredDistanceTo(targetArg.toVec3d())
            } else {
                throw LuaError("distanceTo: ожидается entity или позиция")
            }
            LuaValue.valueOf(Math.sqrt(result))
        }
    }

    fun initMeta(meta: LuaTable) {
        BUILT.apply(meta)
    }
}
