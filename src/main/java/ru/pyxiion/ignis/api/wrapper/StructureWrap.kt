package ru.pyxiion.ignis.api.wrapper

import net.minecraft.entity.SpawnReason
import net.minecraft.nbt.NbtDouble
import net.minecraft.nbt.NbtList
import net.minecraft.server.world.ServerWorld
import net.minecraft.structure.StructurePlacementData
import net.minecraft.structure.StructureTemplate
import net.minecraft.util.BlockMirror
import net.minecraft.util.BlockRotation
import net.minecraft.util.math.BlockPos
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import ru.pyxiion.ignis.Compat
import ru.pyxiion.ignis.api.MetaTableRegistry
import ru.pyxiion.ignis.api.Vector
import ru.pyxiion.ignis.api.util.metaTable
import ru.pyxiion.ignis.mixins.StructureTemplateMixin
import ru.pyxiion.ignis.toBlockPos
import ru.pyxiion.ignis.unwrap

object StructureWrap {

    fun wrap(template: StructureTemplate): LuaValue {
        val t = LuaTable()
        t.setmetatable(MetaTableRegistry.STRUCTURE)
        t.rawset("__pxrp_type", LuaValue.valueOf("structure"))
        t.rawset("__pxrp_object", LuaValue.userdataOf(template))
        return t
    }

    private val BUILT = metaTable<StructureTemplate> {
        prop("size") {
            val s = size
            Vector(s.x.toDouble(), s.y.toDouble(), s.z.toDouble()).toLuaValue()
        }

        method("place") { args ->
            val self = args.arg(1).checktable()
            val template = self.unwrap<StructureTemplate>()

            val worldArg = args.arg(2)
            val world = worldArg.unwrap<ServerWorld>()
            val pos = args.arg(3).toBlockPos()
            val params = if (args.narg() >= 4 && args.arg(4).istable())
                args.arg(4).checktable() else null

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

                    val entity = Compat.loadEntities(
                        nbt,
                        world,
                        SpawnReason.STRUCTURE
                    ) ?: continue

                    val yaw = entity.applyRotation(rotation) + entity.applyMirror(mirror) - entity.yaw
                    entity.refreshPositionAndAngles(finalPos.x, finalPos.y, finalPos.z, yaw, entity.pitch)
                    entity.setBodyYaw(yaw)
                    entity.setHeadYaw(yaw)

                    val entityWrapper = EntityFactory.wrap(entity)
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

            LuaValue.valueOf(result)
        }
    }

    fun initMeta(meta: LuaTable) {
        BUILT.apply(meta)
    }

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
