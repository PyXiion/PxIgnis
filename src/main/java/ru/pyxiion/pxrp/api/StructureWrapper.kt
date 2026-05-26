package ru.pyxiion.pxrp.api

import net.minecraft.entity.EntityType
import net.minecraft.entity.LoadedEntityProcessor
import net.minecraft.entity.SpawnReason
import net.minecraft.nbt.NbtDouble
import net.minecraft.nbt.NbtList
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.server.MinecraftServer
import net.minecraft.server.world.ServerWorld
import net.minecraft.structure.StructurePlacementData
import net.minecraft.structure.StructureTemplate
import net.minecraft.util.BlockMirror
import net.minecraft.util.BlockRotation
import net.minecraft.util.Identifier
import net.minecraft.util.math.BlockPos
import net.minecraft.world.World
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.VarArgFunction
import ru.pyxiion.pxrp.toBlockPos
import ru.pyxiion.pxrp.mixins.StructureTemplateMixin

class StructureWrapper(
    private val template: StructureTemplate,
    private val server: MinecraftServer,
) {
    fun toLuaValue(): LuaValue {
        val meta = LuaTable()
        meta.set("__index", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val key = args.arg(2).tojstring()
                if (key == "size") {
                    val s = template.size
                    return Vector(s.x.toDouble(), s.y.toDouble(), s.z.toDouble()).toLuaValue()
                }
                return MetaTableRegistry.STRUCTURE.get(key)
            }
        })

        meta.set("__pairs", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val self = args.arg(1)
                val keys = kotlin.collections.listOf("size", "place")
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

        val t = LuaTable()
        t.setmetatable(meta)
        t.rawset("place", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val worldArg = args.arg(2)
                val pos = args.arg(3).toBlockPos()
                val params = if (args.narg() >= 4 && args.arg(4).istable())
                    args.arg(4).checktable() else null

                val worldName = worldArg.get("name").tojstring()
                val key = RegistryKey.of(RegistryKeys.WORLD, Identifier.of(worldName))
                val world = server.getWorld(key)
                    ?: throw LuaError("Мир '$worldName' не найден")

                val rotation = parseRotation(
                    params?.get("rotation")?.optjstring("none") ?: "none"
                )
                val mirror = parseMirror(
                    params?.get("mirror")?.optjstring("none") ?: "none"
                )
                val entityCallback = params?.get("on_entity")?.takeIf { it.isfunction() }

                val pivot = BlockPos((template.size.x - 1) / 2, 0, (template.size.z - 1) / 2)

                val data = StructurePlacementData()
                    .setRotation(rotation)
                    .setMirror(mirror)
                    .setPosition(pivot)
                    .setUpdateNeighbors(true)
                    .setInitializeMobs(true)
                    .setRandom(world.random)

                val result = if (entityCallback != null) {
                    data.setIgnoreEntities(true)
                    val blockResult = template.place(world, pos, pivot, data, world.random, 0x03)

                    val accessor = template as? StructureTemplateMixin
                        ?: throw LuaError("Не удалось получить доступ к сущностям структуры")

                    var anySpawned = false
                    for (entry in accessor.entities) {
                        val entityInfo = entry
                        val nbt = entityInfo.nbt.copy()
                        val transformedPos = StructureTemplate.transformAround(entityInfo.pos, mirror, rotation, pivot)
                        val finalPos = transformedPos.add(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble())

                        val posList = NbtList()
                        posList.add(NbtDouble.of(finalPos.x))
                        posList.add(NbtDouble.of(finalPos.y))
                        posList.add(NbtDouble.of(finalPos.z))
                        nbt.put("Pos", posList)
                        nbt.remove("UUID")


                        val entity = EntityType.loadEntityWithPassengers(
                            nbt, world, SpawnReason.STRUCTURE, LoadedEntityProcessor.NOOP
                        ) ?: continue

                        val yaw = entity.applyRotation(rotation) + entity.applyMirror(mirror) - entity.yaw
                        entity.refreshPositionAndAngles(finalPos.x, finalPos.y, finalPos.z, yaw, entity.pitch)
                        entity.setBodyYaw(yaw)
                        entity.setHeadYaw(yaw)

                        val entityWrapper = EntityWrapper(entity).toLuaValue()
                        val callbackResult = entityCallback.call(entityWrapper)
                        if (callbackResult.isboolean() && !callbackResult.toboolean()) continue

                        world.spawnEntityAndPassengers(entity)
                        anySpawned = true
                    }

                    blockResult || anySpawned
                } else {
                    data.setIgnoreEntities(false)
                    template.place(world, pos, pivot, data, world.random, 0x03)
                }

                return LuaValue.valueOf(result)
            }
        })
        return t
    }

    companion object {
        private fun parseRotation(s: String): BlockRotation {
            return when (s) {
                "0", "none" -> BlockRotation.NONE
                "90", "clockwise_90" -> BlockRotation.CLOCKWISE_90
                "180", "clockwise_180" -> BlockRotation.CLOCKWISE_180
                "270", "counterclockwise_90" -> BlockRotation.COUNTERCLOCKWISE_90
                else -> throw LuaError("Неизвестный поворот: '$s' (0/90/180/270)")
            }
        }

        private fun parseMirror(s: String): BlockMirror {
            return when (s) {
                "none" -> BlockMirror.NONE
                "left_right" -> BlockMirror.LEFT_RIGHT
                "front_back" -> BlockMirror.FRONT_BACK
                else -> throw LuaError("Неизвестное отражение: '$s' (none/left_right/front_back)")
            }
        }
    }
}
