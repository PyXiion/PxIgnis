package ru.pyxiion.ignis.api.wrapper

import ru.pyxiion.ignis.wrapObject
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.Mob
import net.minecraft.nbt.DoubleTag
import net.minecraft.nbt.ListTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.Rotation
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.NbtUtils
import net.fabricmc.loader.api.FabricLoader
import ru.pyxiion.ignis.PxIgnis
import ru.pyxiion.ignis.api.util.StructureCapture
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import ru.pyxiion.luanova.vm2.LuaError
import ru.pyxiion.luanova.vm2.LuaTable
import ru.pyxiion.luanova.vm2.LuaValue
import ru.pyxiion.ignis.Compat
import ru.pyxiion.ignis.api.MetaTableRegistry
import ru.pyxiion.ignis.api.Vector
import ru.pyxiion.ignis.api.Vector.Companion.toBlockPos
import ru.pyxiion.ignis.api.util.metaTable
import ru.pyxiion.ignis.mixins.StructureTemplateMixin
import ru.pyxiion.ignis.unwrap

object StructureWrap {

    fun wrap(template: StructureTemplate): LuaValue {
        return wrapObject(MetaTableRegistry.STRUCTURE, "structure", template)
    }

    private val BUILT = metaTable<StructureTemplate> {
        prop("size") {
            val s = size
            Vector.of(s.x.toDouble(), s.y.toDouble(), s.z.toDouble()).toLuaValue()
        }

        // structure:save(path[, { root = index | path }]): gzipped vanilla .nbt in a script root
        method("save") { args ->
            val template = args.arg(1).checktable().unwrap<StructureTemplate>()
            val rel = try {
                StructureCapture.savePath(args.arg(2).checkjstring())
            } catch (e: IllegalArgumentException) {
                throw LuaError(e.message)
            }
            val opts = args.arg(3)
            if (!opts.isnil() && !opts.istable()) throw LuaError("structure:save: options must be a table")
            var index: Int? = null
            var rootPath: String? = null
            if (opts.istable()) {
                for (key in opts.checktable().keys()) {
                    if (key.tojstring() != "root") throw LuaError("structure:save: unknown option '${key.tojstring()}' (known: root)")
                }
                val root = opts.get("root")
                when {
                    root.isnil() -> {}
                    root.type() == LuaValue.TNUMBER && root.todouble() == Math.floor(root.todouble()) -> index = root.toint()
                    root.type() == LuaValue.TSTRING -> rootPath = root.tojstring()
                    else -> throw LuaError("structure:save: root must be a root index or a root path")
                }
            }
            val rt = PxIgnis.runtimeOrNull ?: throw LuaError("structure:save: scripts are not running")
            val vfs = rt.api.vfs
            try {
                val root = StructureCapture.selectRoot(vfs.roots, index, rootPath, FabricLoader.getInstance().gameDir)
                val target = vfs.writeTarget(root, rel)
                val nbt = NbtUtils.addCurrentDataVersion(template.save(CompoundTag()))
                val tmp = Files.createTempFile(target.parent, ".structure", ".tmp")
                try {
                    NbtIo.writeCompressed(nbt, tmp)
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
                } finally {
                    Files.deleteIfExists(tmp)
                }
            } catch (e: IllegalArgumentException) {
                throw LuaError(e.message)
            } catch (e: IOException) {
                throw LuaError("structure:save: ${e.message}")
            }
            LuaValue.NIL
        }

        method("place") { args ->
            val self = args.arg(1).checktable()
            val template = self.unwrap<StructureTemplate>()

            val worldArg = args.arg(2)
            val world = worldArg.unwrap<ServerLevel>()
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

            val data = StructurePlaceSettings()
                .setRotation(rotation)
                .setMirror(mirror)
                .setRotationPivot(pivot)
                .setKnownShape(true)
                .setFinalizeEntities(true)
                .setRandom(world.random)

            val result = if (entityCallback != null) {
                data.setIgnoreEntities(true)
                val blockResult = template.placeInWorld(world, pos, pos, data, world.random, 0x03)

                val accessor = template as? StructureTemplateMixin
                    ?: throw LuaError("Не удалось получить доступ к сущностям структуры")

                var anySpawned = false
                for (entry in accessor.entities) {
                    val entityInfo = entry
                    val nbt = entityInfo.nbt.copy()
                    val transformedPos = StructureTemplate.transform(entityInfo.pos, mirror, rotation, pivot)
                    val finalPos = transformedPos.add(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble())

                    val posList = ListTag()
                    posList.add(DoubleTag.valueOf(finalPos.x))
                    posList.add(DoubleTag.valueOf(finalPos.y))
                    posList.add(DoubleTag.valueOf(finalPos.z))
                    nbt.put("Pos", posList)
                    nbt.remove("UUID")

                    val entity = Compat.loadEntities(
                        nbt,
                        world,
                        EntitySpawnReason.STRUCTURE
                    ) ?: continue

                    val yaw = entity.rotate(rotation) + entity.mirror(mirror) - entity.yRot
                    entity.snapTo(finalPos.x, finalPos.y, finalPos.z, yaw, entity.xRot)
                    entity.setYBodyRot(yaw)
                    entity.setYHeadRot(yaw)

                    val entityWrapper = EntityFactory.wrap(entity)
                    val callbackResult = entityCallback.call(entityWrapper)
                    if (callbackResult.isboolean() && !callbackResult.toboolean()) continue

                    // placeInWorld finalizes mobs itself when entities are not ignored; this branch spawns them by hand.
                    if (entity is Mob) {
                        entity.finalizeSpawn(world, world.getCurrentDifficultyAt(entity.blockPosition()), EntitySpawnReason.STRUCTURE, null)
                    }
                    world.addFreshEntityWithPassengers(entity)
                    anySpawned = true
                }

                blockResult || anySpawned
            } else {
                data.setIgnoreEntities(false)
                template.placeInWorld(world, pos, pos, data, world.random, 0x03)
            }

            LuaValue.valueOf(result)
        }
    }

    fun initMeta(meta: LuaTable) {
        BUILT.apply(meta)
    }

    private fun parseRotation(s: String): Rotation {
        return when (s) {
            "0", "none" -> Rotation.NONE
            "90", "clockwise_90" -> Rotation.CLOCKWISE_90
            "180", "clockwise_180" -> Rotation.CLOCKWISE_180
            "270", "counterclockwise_90" -> Rotation.COUNTERCLOCKWISE_90
            else -> throw LuaError("Неизвестный поворот: '$s' (0/90/180/270)")
        }
    }

    private fun parseMirror(s: String): Mirror {
        return when (s) {
            "none" -> Mirror.NONE
            "left_right" -> Mirror.LEFT_RIGHT
            "front_back" -> Mirror.FRONT_BACK
            else -> throw LuaError("Неизвестное отражение: '$s' (none/left_right/front_back)")
        }
    }
}
