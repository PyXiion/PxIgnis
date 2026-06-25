package ru.pyxiion.ignis.api.wrapper

import com.mojang.brigadier.StringReader
import com.mojang.brigadier.exceptions.CommandSyntaxException
import net.minecraft.command.EntitySelectorReader
import net.minecraft.entity.Entity
import net.minecraft.entity.EntityType
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.SpawnReason
import net.minecraft.entity.attribute.EntityAttributes
import net.minecraft.entity.decoration.DisplayEntity
import net.minecraft.entity.mob.MobEntity
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtOps
import net.minecraft.particle.ParticleTypes
import net.minecraft.network.packet.s2c.play.OverlayMessageS2CPacket
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket
import net.minecraft.particle.ParticleEffect
import net.minecraft.particle.ParticleType
import net.minecraft.registry.Registries
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import net.minecraft.world.World
import net.minecraft.world.border.WorldBorder
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.VarArgFunction
import ru.pyxiion.ignis.api.MetaTableRegistry
import ru.pyxiion.ignis.api.manager.HologramManager
import ru.pyxiion.ignis.api.manager.RegionManager
import ru.pyxiion.ignis.api.resolveOperand
import ru.pyxiion.ignis.api.util.BlockStateCodec
import ru.pyxiion.ignis.luaTableOf
import ru.pyxiion.ignis.unwrapOrNull
import ru.pyxiion.ignis.api.util.metaTable
import ru.pyxiion.ignis.api.util.performRaycast
import ru.pyxiion.ignis.toBlockPos
import ru.pyxiion.ignis.toLuaArray
import ru.pyxiion.ignis.toVec3d
import ru.pyxiion.ignis.unwrap
import java.util.*

object WorldWrap {

    private class InstanceData(
        @JvmField val playerCache: MutableMap<UUID, LuaValue>,
        @JvmField val tickProvider: () -> Long,
    )

    fun wrap(
        world: ServerWorld,
        playerCache: MutableMap<UUID, LuaValue> = mutableMapOf(),
        tickProvider: () -> Long = { 0L }
    ): LuaValue {
        val t = LuaTable()
        t.setmetatable(MetaTableRegistry.WORLD)
        t.rawset("__pxrp_type", LuaValue.valueOf("world"))
        t.rawset("__pxrp_object", LuaValue.userdataOf(world))
        t.rawset("__pxrp_data", LuaValue.userdataOf(InstanceData(playerCache, tickProvider)))
        return t
    }

    private val BUILT = metaTable<ServerWorld> {
        prop("name") { LuaValue.valueOf(registryKey.value.path) }
        prop(
            "time",
            get = { LuaValue.valueOf(timeOfDay.toDouble()) },
            set = { v -> timeOfDay = v.tolong() }
        )
        prop(
            "raining",
            get = { if (isRaining) LuaValue.TRUE else LuaValue.FALSE },
            set = { v ->
                if (v.toboolean()) setWeather(0, 6000, true, isThundering)
                else setWeather(0, 0, false, isThundering)
            }
        )
        prop(
            "thundering",
            get = { if (isThundering) LuaValue.TRUE else LuaValue.FALSE },
            set = { v ->
                if (v.toboolean()) setWeather(0, 6000, isRaining, true)
                else setWeather(0, 0, isRaining, false)
            }
        )
        prop("players") { w, self ->
            val data = self.rawget("__pxrp_data").checkuserdata() as InstanceData
            PlayerListWrapper(
                source = { w.players },
                playerCache = data.playerCache,
                tickProvider = data.tickProvider,
            ).toLuaValue()
        }
        prop("regions") {
            RegionManager.all(this).map(RegionWrap::wrap).toLuaArray()
        }

        // world:spawn(entityId, pos, overrides?)
        method("spawn") { args ->
            val self = args.arg(1).checktable()
            val w = self.unwrap<ServerWorld>()
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

            if (!w.spawnEntity(entity)) return@method LuaValue.NIL

            EntityFactory.wrap(entity)
        }

        // world:spawnHologram(pos, text, opts?)
        method("spawnHologram") { args ->
            val self = args.arg(1).checktable()
            val w = self.unwrap<ServerWorld>()
            val pos = args.arg(2).toVec3d()
            val text = args.arg(3).checkjstring()
            val opts = if (args.narg() >= 4) args.arg(4).checktable() else null

            val entityType = Registries.ENTITY_TYPE.get(Identifier.of("minecraft:text_display"))
                ?: throw LuaError("Сущность 'minecraft:text_display' не найдена")

            val entity = entityType.create(w, SpawnReason.COMMAND) as? DisplayEntity.TextDisplayEntity
                ?: throw LuaError("Не удалось создать text_display")

            entity.setPosition(pos.x, pos.y, pos.z)
            entity.text = Text.literal(text)
            entity.billboardMode = DisplayEntity.BillboardMode.CENTER
            entity.background = HologramDefaults.BACKGROUND
            entity.lineWidth = HologramDefaults.LINE_WIDTH

            opts?.let { o ->
                o.get("alignment").takeIf { it.isstring() }?.let {
                    HologramWrapper.applyAlignmentFromLua(entity, it.tojstring())
                }
                o.get("billboard").takeIf { it.isstring() }?.let {
                    entity.billboardMode = HologramWrapper.parseBillboardFromLua(it.tojstring())
                }
                o.get("lineWidth").takeIf { it.isnumber() }?.let { entity.lineWidth = it.toint() }
                o.get("background").takeIf { it.isnumber() }?.let { entity.background = it.toint() }
                o.get("opacity").takeIf { it.isnumber() }?.let { entity.textOpacity = it.toint().toByte() }
                o.get("shadow").takeIf { it.isboolean() }?.let {
                    HologramWrapper.applyFlagFromLua(
                        entity,
                        DisplayEntity.TextDisplayEntity.SHADOW_FLAG,
                        it.toboolean()
                    )
                }
                o.get("seeThrough").takeIf { it.isboolean() }?.let {
                    HologramWrapper.applyFlagFromLua(
                        entity,
                        DisplayEntity.TextDisplayEntity.SEE_THROUGH_FLAG,
                        it.toboolean()
                    )
                }
                o.get("glowing").takeIf { it.isboolean() }?.let { entity.isGlowing = it.toboolean() }
            }

            if (!w.spawnEntity(entity)) return@method LuaValue.NIL

            HologramManager.create(entity, w).toLuaValue()
        }

        // world:setBlock(pos, blockId)
        method("setBlock") { args ->
            val self = args.arg(1).checktable()
            val w = self.unwrap<ServerWorld>()
            val pos = args.arg(2).toBlockPos()
            val blockId = resolveBlockId(args.arg(3).checkjstring())

            val block = Registries.BLOCK.get(Identifier.of(blockId))
                ?: throw LuaError("Блок '$blockId' не найден")

            w.setBlockState(pos, block.defaultState, 0x03)
            LuaValue.NIL
        }

        // world:getBlock(pos) -> blockId
        method("getBlock") { args ->
            val self = args.arg(1).checktable()
            val w = self.unwrap<ServerWorld>()
            val pos = args.arg(2).toBlockPos()
            val block = w.getBlockState(pos).block
            LuaValue.valueOf(Registries.BLOCK.getId(block).toString())
        }

        // world:getBlockState(pos) -> { id, properties }
        method("getBlockState") { args ->
            val self = args.arg(1).checktable()
            val w = self.unwrap<ServerWorld>()
            val pos = args.arg(2).toBlockPos()
            val state = w.getBlockState(pos)
            BlockStateCodec.stateToTable(state)
        }

        // world:setBlockState(pos, { id, properties })
        method("setBlockState") { args ->
            val self = args.arg(1).checktable()
            val w = self.unwrap<ServerWorld>()
            val pos = args.arg(2).toBlockPos()
            val tbl = args.arg(3).checktable()

            val id = tbl.get("id").checkjstring()
            val blockId = resolveBlockId(id)
            val block = Registries.BLOCK.get(Identifier.of(blockId))
                ?: throw LuaError("Блок '$blockId' не найден")

            val state = if (tbl.get("properties").istable()) {
                BlockStateCodec.applyProperties(block, tbl.get("properties").checktable())
            } else {
                block.defaultState
            }

            w.setBlockState(pos, state, 0x03)
            LuaValue.NIL
        }

        // world:fill(posA, posB, blockId)
        method("fill") { args ->
            val self = args.arg(1).checktable()
            val w = self.unwrap<ServerWorld>()
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
            LuaValue.NIL
        }

        // world:particle(particleId, pos, opts?)
        method("particle") { args ->
            val self = args.arg(1).checktable()
            val w = self.unwrap<ServerWorld>()
            val id = args.arg(2).checkjstring()
            val resolvedId = if (id.contains(':')) id else "minecraft:$id"
            val key = RegistryKey.of(RegistryKeys.PARTICLE_TYPE, Identifier.of(resolvedId))
            val particleType = Registries.PARTICLE_TYPE.get(key)
                ?: throw LuaError("Частица '$id' не найдена")

            val (x, y, z) = resolveOperand(args.arg(3))

            val count: Int
            val deltaX: Double
            val deltaY: Double
            val deltaZ: Double
            val speed: Double
            var data: LuaTable?

            if (args.narg() >= 4 && args.arg(4).istable()) {
                val opts = args.arg(4).checktable()
                count = opts.get("count").optint(1)
                val spread = opts.get("spread")
                if (spread.istable()) {
                    val (dx, dy, dz) = resolveOperand(spread)
                    deltaX = dx; deltaY = dy; deltaZ = dz
                } else {
                    deltaX = 0.0; deltaY = 0.0; deltaZ = 0.0
                }
                speed = opts.get("speed").optdouble(0.0)
                data = opts
            } else {
                count = 1; deltaX = 0.0; deltaY = 0.0; deltaZ = 0.0; speed = 0.0; data = null
            }

            val effect = buildParticleEffect(particleType, data, w, resolvedId)

            w.spawnParticles(effect, true, false, x, y, z, count, deltaX, deltaY, deltaZ, speed)
            LuaValue.NIL
        }

        // world:playSound(soundId, pos, voulme?, pitch?)
        method("playSound") { args ->
            val self = args.arg(1).checktable()
            val w = self.unwrap<ServerWorld>()
            val id = args.arg(2).checkjstring()
            val pos = args.arg(3).toVec3d()
            val volume = args.arg(4).optdouble(1.0).toFloat()
            val pitch = args.arg(5).optdouble(1.0).toFloat()

            val key = RegistryKey.of(RegistryKeys.SOUND_EVENT, Identifier.of(id))
            val sound = Registries.SOUND_EVENT.get(key)
                ?: throw LuaError("Звук '$id' не найден")

            w.playSound(null, pos.x, pos.y, pos.z, sound, SoundCategory.PLAYERS, volume, pitch)
            LuaValue.NIL
        }

        // world:getEntities(pos, radius, type?) -> {entity,...}
        method("getEntities") { args ->
            val self = args.arg(1).checktable()
            val w = self.unwrap<ServerWorld>()
            val pos = args.arg(2).toVec3d()
            val radius = args.arg(3).checkdouble()
            val typeFilter = if (args.narg() >= 4 && args.arg(4).isstring()) args.arg(4).tojstring() else null

            val box = Box(
                pos.x - radius, pos.y - radius, pos.z - radius,
                pos.x + radius, pos.y + radius, pos.z + radius
            )

            val nearby = w.getOtherEntities(null, box)

            val entities = if (typeFilter != null) {
                val targetType = Registries.ENTITY_TYPE.get(Identifier.of(typeFilter))
                    ?: throw LuaError("Тип сущности '$typeFilter' не найден")
                nearby.filter { it.type == targetType }
            } else {
                nearby
            }

            entities.map(EntityFactory::wrap).toLuaArray()
        }

        // world:raycast(start, dir, range, fluids?, entities?)
        method("raycast") { args ->
            val self = args.arg(1).checktable()
            val w = self.unwrap<ServerWorld>()
            val start = args.arg(2).toVec3d()
            val dir = args.arg(3).toVec3d()
            val range = args.arg(4).checkdouble()
            val includeFluids = args.arg(5).optboolean(false)
            val includeEntities = args.arg(6).optboolean(true)

            val dirNorm = dir.normalize()
            val end = Vec3d(start.x + dirNorm.x * range, start.y + dirNorm.y * range, start.z + dirNorm.z * range)

            performRaycast(
                start,
                end,
                range,
                includeFluids,
                includeEntities,
                w,
                null
            )
        }

        // world:broadcastInRange(text, pos, range, overlay?)
        method("broadcastInRange") { args ->
            val self = args.arg(1).checktable()
            val w = self.unwrap<ServerWorld>()
            val text = args.arg(2).checkjstring()
            val pos = args.arg(3).toVec3d()
            val range = args.arg(4).checkdouble()
            val overlay = if (args.narg() >= 5 && args.arg(5).isint()) args.arg(5).toint() else null

            val rangeSquare = range * range
            val players = w.players.filter { it.squaredDistanceTo(pos) < rangeSquare }

            if (overlay == null) {
                players.forEach { it.sendMessage(Text.literal(text)) }
            } else {
                val timing = TitleFadeS2CPacket(20, overlay, 20)
                val title = OverlayMessageS2CPacket(Text.literal(text))
                players.forEach {
                    with(it.networkHandler) {
                        sendPacket(timing)
                        sendPacket(title)
                    }
                }
            }
            LuaValue.NIL
        }

        // world:getBiome(pos) -> string?
        method("getBiome") { args ->
            val self = args.arg(1).checktable()
            val w = self.unwrap<ServerWorld>()
            val pos = args.arg(2).toBlockPos()
            val biome = w.getBiome(pos)
            val biomeId = biome.key.flatMap { java.util.Optional.of(it.value.toString()) }.orElse(null)
            if (biomeId != null) LuaValue.valueOf(biomeId) else LuaValue.NIL
        }

        // world:getBorder() -> { center, size, damage, ... }
        method("getBorder") { args ->
            val self = args.arg(1).checktable()
            val w = self.unwrap<ServerWorld>()
            val border = w.worldBorder

            val meta = LuaTable()
            val indexFn = object : VarArgFunction() {
                override fun invoke(va: Varargs): Varargs {
                    when (va.arg(2).tojstring()) {
                        "center" -> return luaTableOf(
                            "x" to LuaValue.valueOf(border.centerX),
                            "z" to LuaValue.valueOf(border.centerZ),
                        )
                        "size" -> return LuaValue.valueOf(border.size)
                        "damage" -> return LuaValue.valueOf(border.damagePerBlock)
                        "warningTime" -> return LuaValue.valueOf(border.warningTime)
                        "warningBlocks" -> return LuaValue.valueOf(border.warningBlocks)
                        "damageThreshold" -> return LuaValue.valueOf(border.safeZone)
                    }
                    return LuaValue.NIL
                }
            }
            meta.set("__index", indexFn)

            val newIndexFn = object : VarArgFunction() {
                override fun invoke(va: Varargs): Varargs {
                    val key = va.arg(2).tojstring()
                    val value = va.arg(3)
                    when (key) {
                        "center" -> {
                            val t = value.checktable()
                            border.setCenter(
                                t.get("x").optdouble(0.0),
                                t.get("z").optdouble(0.0),
                            )
                        }
                        "size" -> border.setSize(value.todouble())
                        "damage" -> border.damagePerBlock = value.todouble()
                        "warningTime" -> border.warningTime = value.toint()
                        "warningBlocks" -> border.warningBlocks = value.toint()
                        "damageThreshold" -> border.safeZone = value.todouble()
                    }
                    return LuaValue.NIL
                }
            }
            meta.set("__newindex", newIndexFn)

            meta.set("setSize", object : VarArgFunction() {
                override fun invoke(va: Varargs): Varargs {
                    val size = va.arg(1).checkdouble()
                    border.setSize(size)
                    return LuaValue.NIL
                }
            })
            val t = LuaTable()
            t.setmetatable(meta)
            t
        }

        // world:explode(pos, power, opts?)
        method("explode") { args ->
            val self = args.arg(1).checktable()
            val w = self.unwrap<ServerWorld>()
            val pos = args.arg(2).toVec3d()
            val power = args.arg(3).checkdouble().toFloat()
            val opts = if (args.narg() >= 4 && args.arg(4).istable()) args.arg(4).checktable() else null

            val fire = opts?.get("fire")?.optboolean(false) ?: false
            val destruction = opts?.get("destruction")?.optjstring("break") ?: "break"
            val sourceType = try {
                World.ExplosionSourceType.valueOf(destruction.uppercase())
            } catch (_: Exception) {
                World.ExplosionSourceType.BLOCK
            }

            w.createExplosion(null, pos.x, pos.y, pos.z, power, fire, sourceType)
            LuaValue.NIL
        }

        // world:strike(pos, opts?)
        method("strike") { args ->
            val self = args.arg(1).checktable()
            val w = self.unwrap<ServerWorld>()
            val pos = args.arg(2).toVec3d()
            val opts = if (args.narg() >= 3 && args.arg(3).istable()) args.arg(3).checktable() else null

            val bolt = EntityType.LIGHTNING_BOLT.create(w, SpawnReason.COMMAND)
                ?: throw LuaError("Не удалось создать молнию")
            bolt.setPosition(pos.x, pos.y, pos.z)
            bolt.setCosmetic(opts?.get("effect")?.optboolean(false) ?: false)
            w.spawnEntity(bolt)
            LuaValue.NIL
        }

        // world:createRegion(posA, posB) -> region
        method("createRegion") { args ->
            val self = args.arg(1).checktable()
            val w = self.unwrap<ServerWorld>()
            if (args.narg() < 3) throw LuaError("createRegion: ожидается (posA, posB)")
            val a = args.arg(2).toVec3d()
            val b = args.arg(3).toVec3d()
            val min = Vec3d(minOf(a.x, b.x), minOf(a.y, b.y), minOf(a.z, b.z))
            val max = Vec3d(maxOf(a.x, b.x), maxOf(a.y, b.y), maxOf(a.z, b.z))
            val region = RegionManager.create(w, Box(min, max))
            RegionWrap.wrap(region)
        }

        // world:getRegion(id) -> region?
        method("getRegion") { args ->
            val self = args.arg(1).checktable()
            val w = self.unwrap<ServerWorld>()
            require(args.narg() == 2) { "getRegion(id) requires 1 argument" }
            val id = args.arg(2).checkint()
            val r = RegionManager.get(id)
            if (r != null && r.world === w) RegionWrap.wrap(r) else LuaValue.NIL
        }

        // world:getRegionsAt(pos) -> {region,...}
        method("getRegionsAt") { args ->
            val self = args.arg(1).checktable()
            val w = self.unwrap<ServerWorld>()
            require(args.narg() == 2) { "getRegionsAt(pos) requires 1 argument" }
            val pos = args.arg(2).toVec3d()
            RegionManager.getAt(w, pos).map(RegionWrap::wrap).toLuaArray()
        }

        // world:getEntitiesBySelector(selector, opts?) -> {entity,...}
        method("getEntitiesBySelector") { args ->
            val self = args.arg(1).checktable()
            val w = self.unwrap<ServerWorld>()
            val selector = args.arg(2).checkjstring()
            val opts = if (args.narg() >= 3 && args.arg(3).istable()) args.arg(3).checktable() else null

            val center = opts?.get("at")?.let { v ->
                if (v.istable()) v.toVec3d() else null
            }
            val sourceEntity = opts?.get("as")?.let { v ->
                if (v.istable()) v.unwrapOrNull<Entity>() else null
            }

            val parsed = try {
                EntitySelectorReader(StringReader(selector), true).read()
            } catch (e: CommandSyntaxException) {
                throw LuaError("Неверный селектор '$selector': ${e.message}")
            }

            val server = w.server ?: throw LuaError("Мировой сервер недоступен")
            val base = server.commandSource.withWorld(w)
            val withPos = if (center != null) base.withPosition(center) else base
            val source = if (sourceEntity != null) withPos.withEntity(sourceEntity) else withPos

            val entities = try {
                parsed.getEntities(source)
            } catch (e: CommandSyntaxException) {
                throw LuaError("Ошибка селектора '$selector': ${e.message}")
            }

            entities.map(EntityFactory::wrap).toLuaArray()
        }
    }

    fun initMeta(meta: LuaTable) {
        BUILT.apply(meta)
    }

    private fun resolveBlockId(id: String): String {
        return if (id.contains(':')) id else "minecraft:$id"
    }

    private fun applyOverrides(entity: Entity, overrides: LuaTable) {
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

    @Suppress("UNCHECKED_CAST")
    private fun buildParticleEffect(
        type: ParticleType<*>,
        data: LuaTable?,
        world: ServerWorld,
        id: String
    ): ParticleEffect {
        if (type is ParticleEffect) return type

        val nbt = buildParticleDataNbt(type, data, world, id)
        val ops = world.registryManager.getOps(NbtOps.INSTANCE)
        return (type as ParticleType<ParticleEffect>).codec.codec()
            .parse(ops, nbt)
            .getOrThrow { msg: String -> LuaError("Частица '$id': $msg") }
    }

    private fun buildParticleDataNbt(
        type: ParticleType<*>,
        data: LuaTable?,
        world: ServerWorld,
        id: String,
    ): NbtCompound {
        fun tbl(): LuaTable = data ?: throw LuaError("Частица '$id': data required for this particle type")

        fun rgb(v: LuaValue): Int {
            val t = v.checktable()
            val r = t.get(1).optdouble(t.get("r").optdouble(0.0))
            val g = t.get(2).optdouble(t.get("g").optdouble(0.0))
            val b = t.get(3).optdouble(t.get("b").optdouble(0.0))
            val s = if (r > 1.0 || g > 1.0 || b > 1.0) 1.0 else 255.0
            val ri = (r * s).toInt().coerceIn(0, 255)
            val gi = (g * s).toInt().coerceIn(0, 255)
            val bi = (b * s).toInt().coerceIn(0, 255)
            return (ri shl 16) or (gi shl 8) or bi
        }

        fun argb(v: LuaValue): Int {
            val t = v.checktable()
            val a = t.get(1).optdouble(t.get("a").optdouble(255.0))
            val r = t.get(2).optdouble(t.get("r").optdouble(0.0))
            val g = t.get(3).optdouble(t.get("g").optdouble(0.0))
            val b = t.get(4).optdouble(t.get("b").optdouble(0.0))
            val ai = a.toInt().coerceIn(0, 255)
            val ri = r.toInt().coerceIn(0, 255)
            val gi = g.toInt().coerceIn(0, 255)
            val bi = b.toInt().coerceIn(0, 255)
            return (ai shl 24) or (ri shl 16) or (gi shl 8) or bi
        }

        val c = NbtCompound()
        when (type) {
            ParticleTypes.BLOCK,
            ParticleTypes.BLOCK_MARKER,
            ParticleTypes.FALLING_DUST,
            ParticleTypes.DUST_PILLAR,
            ParticleTypes.BLOCK_CRUMBLE -> {
                c.putString("block_state", resolveBlockId(tbl().get("block").checkjstring()))
            }

            ParticleTypes.DRAGON_BREATH -> {
                c.putFloat("power", tbl().get("power").optdouble(1.0).toFloat())
            }

            ParticleTypes.DUST -> {
                val t = tbl()
                c.putInt("color", rgb(t.get("color")))
                c.putFloat("scale", t.get("scale").optdouble(1.0).toFloat())
            }

            ParticleTypes.DUST_COLOR_TRANSITION -> {
                val t = tbl()
                c.putInt("from_color", rgb(t.get("fromColor")))
                c.putInt("to_color", rgb(t.get("toColor")))
                c.putFloat("scale", t.get("scale").optdouble(1.0).toFloat())
            }

            ParticleTypes.EFFECT,
            ParticleTypes.INSTANT_EFFECT -> {
                val t = tbl()
                val colorVal = t.get("color")
                c.putInt("color", if (colorVal.istable()) rgb(colorVal) else -1)
                c.putFloat("power", t.get("power").optdouble(1.0).toFloat())
            }

            ParticleTypes.ENTITY_EFFECT,
            ParticleTypes.TINTED_LEAVES,
            ParticleTypes.FLASH -> {
                c.putInt("color", argb(tbl().get("color")))
            }

            ParticleTypes.ITEM -> {
                val t = tbl()
                val itemCompound = NbtCompound()
                itemCompound.putString("id", resolveBlockId(t.get("item").checkjstring()))
                itemCompound.putInt("count", t.get("count").optint(1))
                c.put("item", itemCompound)
            }

            ParticleTypes.SCULK_CHARGE -> {
                c.putFloat("roll", tbl().get("roll").optdouble(0.0).toFloat())
            }

            ParticleTypes.SHRIEK -> {
                c.putInt("delay", tbl().get("delay").optint(0))
            }

            ParticleTypes.TRAIL -> {
                val t = tbl()
                val target = t.get("target").toVec3d()
                val targetCompound = NbtCompound()
                targetCompound.putDouble("x", target.x)
                targetCompound.putDouble("y", target.y)
                targetCompound.putDouble("z", target.z)
                c.put("target", targetCompound)
                c.putInt("color", rgb(t.get("color")))
                c.putInt("duration", t.get("duration").optint(20))
            }

            ParticleTypes.VIBRATION -> {
                val t = tbl()
                val from = t.get("from").toVec3d()
                val dest = NbtCompound()
                dest.putString("type", "minecraft:block")
                dest.putInt("x", from.x.toInt())
                dest.putInt("y", from.y.toInt())
                dest.putInt("z", from.z.toInt())
                c.put("destination", dest)
                c.putInt("arrival_in_ticks", t.get("arrivalInTicks").optint(1))
            }

            else -> {
                val name = Registries.PARTICLE_TYPE.getId(type)?.toString() ?: id
                throw LuaError("Частица '$name' не поддерживается в data")
            }
        }
        return c
    }

}
