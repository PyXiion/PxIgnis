package ru.pyxiion.pxrp.api

import net.minecraft.entity.LivingEntity
import net.minecraft.entity.SpawnReason
import net.minecraft.entity.attribute.EntityAttributes
import net.minecraft.registry.Registries
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.server.world.ServerWorld
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import net.minecraft.util.math.BlockPos
import net.minecraft.world.World as McWorld
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.VarArgFunction
import ru.pyxiion.pxrp.luaTableOf
import ru.pyxiion.pxrp.toBlockPos
import ru.pyxiion.pxrp.toVec3d

class World(private val world: ServerWorld) {

    fun toLuaValue(): LuaValue {
        val w = world

        val metatable = LuaTable()
        metatable.set("__index", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val key = args.arg(2).tojstring()
                return when (key) {
                    "name" -> LuaValue.valueOf(w.registryKey.value.path)
                    "time" -> LuaValue.valueOf(w.timeOfDay.toDouble())
                    "raining" -> if (w.isRaining) LuaValue.TRUE else LuaValue.FALSE
                    "thundering" -> if (w.isThundering) LuaValue.TRUE else LuaValue.FALSE
                    else -> LuaValue.NIL
                }
            }
        })

        metatable.set("__newindex", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val key = args.arg(2).tojstring()
                val value = args.arg(3)
                when (key) {
                    "time" -> w.timeOfDay = value.tolong()
                    "raining" -> {
                        if (value.toboolean()) {
                            w.setWeather(0, 6000, true, w.isThundering)
                        } else {
                            w.setWeather(0, 0, false, w.isThundering)
                        }
                    }
                    "thundering" -> {
                        if (value.toboolean()) {
                            w.setWeather(0, 6000, w.isRaining, true)
                        } else {
                            w.setWeather(0, 0, w.isRaining, false)
                        }
                    }
                }
                return LuaValue.NIL
            }
        })

        val t = LuaTable()
        t.setmetatable(metatable)

        t.rawset("spawn", spawn(w))
        t.rawset("setBlock", setBlock(w))
        t.rawset("getBlock", getBlock(w))
        t.rawset("fill", fill(w))

        return t
    }

    companion object {
        private fun resolveBlockId(id: String): String {
            return if (id.contains(':')) id else "minecraft:$id"
        }

        private fun spawn(w: ServerWorld) = object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val entityId = args.arg(2).checkjstring()
                val pos = args.arg(3).toVec3d()
                val overrides = if (args.narg() >= 4) args.arg(4).checktable() else null

                val id = resolveBlockId(entityId)
                val entityType = Registries.ENTITY_TYPE.get(Identifier.of(id))
                    ?: throw LuaError("Сущность '$entityId' не найдена")

                val entity = entityType.create(w, SpawnReason.COMMAND)
                    ?: throw LuaError("Не удалось создать сущность '$entityId'")

                entity.setPosition(pos.x, pos.y, pos.z)

                overrides?.let { applyOverrides(entity, it) }

                if (!w.spawnEntity(entity)) {
                    return LuaValue.NIL
                }

                return EntityWrapper(entity).toLuaValue()
            }
        }

        private fun applyOverrides(entity: net.minecraft.entity.Entity, overrides: LuaTable) {
            overrides.get("health").let { v ->
                if (v.isnumber() && entity is LivingEntity) {
                    val maxAttr = entity.getAttributeInstance(EntityAttributes.MAX_HEALTH)
                    if (maxAttr != null) {
                        val desired = v.todouble()
                        if (desired > maxAttr.value) {
                            maxAttr.baseValue = desired
                        }
                    }
                    entity.health = v.tofloat()
                }
            }
            overrides.get("custom_name").let { v ->
                if (v.isstring()) {
                    entity.customName = Text.literal(v.tojstring())
                }
            }
        }

        private fun setBlock(w: ServerWorld) = object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val pos = args.arg(2).toBlockPos()
                val blockId = resolveBlockId(args.arg(3).checkjstring())

                val block = Registries.BLOCK.get(Identifier.of(blockId))
                    ?: throw LuaError("Блок '$blockId' не найден")

                w.setBlockState(pos, block.defaultState, 0x03)
                return LuaValue.NIL
            }
        }

        private fun getBlock(w: ServerWorld) = object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val pos = args.arg(2).toBlockPos()
                val block = w.getBlockState(pos).block
                return LuaValue.valueOf(Registries.BLOCK.getId(block).toString())
            }
        }

        private fun fill(w: ServerWorld) = object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val pos1 = args.arg(2).toBlockPos()
                val pos2 = args.arg(3).toBlockPos()
                val blockId = resolveBlockId(args.arg(4).checkjstring())

                val block = Registries.BLOCK.get(Identifier.of(blockId))
                    ?: throw LuaError("Блок '$blockId' не найден")

                val minX = Math.min(pos1.x, pos2.x)
                val minY = Math.min(pos1.y, pos2.y)
                val minZ = Math.min(pos1.z, pos2.z)
                val maxX = Math.max(pos1.x, pos2.x)
                val maxY = Math.max(pos1.y, pos2.y)
                val maxZ = Math.max(pos1.z, pos2.z)

                val volume = (maxX - minX + 1L) * (maxY - minY + 1L) * (maxZ - minZ + 1L)
                if (volume > 32768) {
                    throw LuaError("fill: объём ($volume) превышает максимум (32768)")
                }

                val state = block.defaultState
                for (pos in BlockPos.iterate(BlockPos(minX, minY, minZ), BlockPos(maxX, maxY, maxZ))) {
                    w.setBlockState(pos, state, 0x02)
                }
                return LuaValue.NIL
            }
        }
    }
}
