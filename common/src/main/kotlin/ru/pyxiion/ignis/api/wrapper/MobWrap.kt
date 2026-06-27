package ru.pyxiion.ignis.api.wrapper

import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.server.level.ServerLevel
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import ru.pyxiion.ignis.api.MetaTableRegistry
import ru.pyxiion.ignis.api.Vector.Companion.toVec3d
import ru.pyxiion.ignis.api.manager.MobAIManager
import ru.pyxiion.ignis.api.util.metaTable
import ru.pyxiion.ignis.unwrap
import ru.pyxiion.ignis.unwrapOrNull
import java.util.UUID

object MobWrap {

    fun wrap(mob: Mob): LuaValue {
        val t = LuaTable()
        t.setmetatable(MetaTableRegistry.MOB)
        t.rawset("__pxrp_type", LuaValue.valueOf("mob"))
        t.rawset("__pxrp_object", LuaValue.userdataOf(mob))
        return t
    }

    private val BUILT = metaTable<Mob> {
        inherit { MetaTableRegistry.ENTITY }

        prop("isMob") { LuaValue.TRUE }
        prop("aiActive") { LuaValue.valueOf(MobAIManager.hasAI(this)) }
        prop(
            "target",
            get = {
                target?.let { EntityFactory.wrap(it) } ?: LuaValue.NIL
            },
            set = { v ->
                if (v.isnil()) {
                    target = null
                } else {
                    val uuidStr = v.checktable().get("uuid")
                    if (uuidStr.isstring()) {
                        val uuid = UUID.fromString(uuidStr.tojstring())
                        val entity = level().getEntity(uuid)
                        target = entity as? LivingEntity
                    }
                }
            }
        )
        prop("age") { LuaValue.valueOf(tickCount) }
        prop("pathRemaining") {
            val nav = navigation
            if (!nav.isDone) {
                val path = nav.path
                if (path != null) {
                    val len = path.nodeCount
                    if (len > 0) {
                        val nodeIdx = path.nextNodeIndex
                        val progress = 1.0 - (nodeIdx.toDouble() / len.toDouble())
                        LuaValue.valueOf(progress.coerceIn(0.0, 1.0))
                    } else LuaValue.valueOf(0.0)
                } else LuaValue.valueOf(0.0)
            } else LuaValue.valueOf(0.0)
        }
        prop("pathFound") { LuaValue.valueOf(!navigation.isDone) }

        method("setAI") { args ->
            val self = args.arg(1).checktable()
            MobAIManager.setAI(self.unwrap<Mob>(), args.arg(2))
            LuaValue.NIL
        }

        method("clearAI") { args ->
            args.arg(1).checktable().unwrap<Mob>()
                .let { MobAIManager.clearAI(it) }
            LuaValue.NIL
        }

        method("navigateTo") { args ->
            val self = args.arg(1).checktable()
            val m = self.unwrap<Mob>()

            val result: Boolean = if (args.arg(2).istable()) {
                val entityTable = args.arg(2).checktable()
                val uuidStr = entityTable.get("uuid").optjstring(null) ?: return@method LuaValue.FALSE
                val target = m.level().getEntity(UUID.fromString(uuidStr)) ?: return@method LuaValue.FALSE
                val speed = args.arg(3).optdouble(1.0)
                m.navigation.moveTo(target, speed)
            } else {
                val x = args.arg(2).checkdouble()
                val y = args.arg(3).checkdouble()
                val z = args.arg(4).checkdouble()
                val speed = args.arg(5).optdouble(1.0)
                m.navigation.moveTo(x, y, z, speed)
            }
            LuaValue.valueOf(result)
        }

        method("stopNavigation") { args ->
            args.arg(1).checktable().unwrap<Mob>().navigation.stop()
            LuaValue.NIL
        }

        method("lookAt") { args ->
            val self = args.arg(1).checktable()
            val m = self.unwrap<Mob>()

            if (args.arg(3).isnil() && args.arg(2).istable()) {
                val entityTable = args.arg(2).checktable()
                val uuidStr = entityTable.get("uuid").optjstring(null) ?: return@method LuaValue.NIL
                val target = m.level().getEntity(UUID.fromString(uuidStr)) ?: return@method LuaValue.NIL
                m.lookControl.setLookAt(target)
            } else {
                val x = args.arg(2).checkdouble()
                val y = args.arg(3).checkdouble()
                val z = args.arg(4).checkdouble()
                m.lookControl.setLookAt(x, y, z)
            }
            LuaValue.NIL
        }

        method("moveToward") { args ->
            val self = args.arg(1).checktable()
            val m = self.unwrap<Mob>()
            val pos = args.arg(2).toVec3d()
            val speed = args.arg(3).optdouble(1.0)
            m.navigation.stop()
            m.moveControl.setWantedPosition(pos.x, pos.y, pos.z, speed)
            LuaValue.NIL
        }

        method("jump") { args ->
            args.arg(1).checktable().unwrap<Mob>().jumpControl.jump()
            LuaValue.NIL
        }

        method("tryAttack") { args ->
            val self = args.arg(1).checktable()
            val m = self.unwrap<Mob>()
            val target = args.arg(2).unwrap<Entity>()
            LuaValue.valueOf(m.doHurtTarget(m.level() as ServerLevel, target))
        }

        method("canSee") { args ->
            val self = args.arg(1).checktable()
            val m = self.unwrap<Mob>()
            val targetEntity = args.arg(2).unwrapOrNull<Entity>()
            if (targetEntity == null) return@method LuaValue.FALSE
            LuaValue.valueOf(m.hasLineOfSight(targetEntity))
        }

        method("distanceTo") { args ->
            val self = args.arg(1).checktable()
            val m = self.unwrap<Mob>()
            val targetArg = args.arg(2)
            val result: Double = if (targetArg.istable()) {
                targetArg.unwrapOrNull<Entity>()?.let { m.distanceToSqr(it) }
                    ?: m.distanceToSqr(targetArg.toVec3d())
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
