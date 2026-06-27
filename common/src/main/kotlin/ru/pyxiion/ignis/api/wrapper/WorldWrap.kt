package ru.pyxiion.ignis.api.wrapper

import com.mojang.brigadier.StringReader
import com.mojang.brigadier.exceptions.CommandSyntaxException
import net.minecraft.commands.arguments.selector.EntitySelectorParser
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.Display
import net.minecraft.world.entity.Mob
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.core.particles.ParticleType
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.RegistryOps
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundSource
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraft.world.level.Level
import net.minecraft.world.level.border.WorldBorder
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.VarArgFunction
import ru.pyxiion.ignis.api.MetaTableRegistry
import ru.pyxiion.ignis.api.Vector.Companion.toBlockPos
import ru.pyxiion.ignis.api.Vector.Companion.toVec3d
import ru.pyxiion.ignis.api.manager.HologramManager
import ru.pyxiion.ignis.api.manager.RegionManager
import ru.pyxiion.ignis.api.resolveOperand
import ru.pyxiion.ignis.api.util.BlockStateCodec
import ru.pyxiion.ignis.luaTableOf
import ru.pyxiion.ignis.unwrapOrNull
import ru.pyxiion.ignis.api.util.metaTable
import ru.pyxiion.ignis.api.util.performRaycast
import ru.pyxiion.ignis.api.wrappertoLuaValue.PlayerListWrapper
import ru.pyxiion.ignis.toLuaArray
import ru.pyxiion.ignis.unwrap
import java.util.*

object WorldWrap {

    private class InstanceData(
        @JvmField val playerCache: MutableMap<UUID, LuaValue>,
        @JvmField val tickProvider: () -> Long,
    )

    fun wrap(
        world: ServerLevel,
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

    private val BUILT = metaTable<ServerLevel> {
        prop("name") { LuaValue.valueOf(dimension().identifier().path) }
        prop(
            "time",
            get = { LuaValue.valueOf(dayTime.toDouble()) },
            set = { v -> setDayTime(v.tolong()) }
        )
        prop(
            "raining",
            get = { if (isRaining) LuaValue.TRUE else LuaValue.FALSE },
            set = { v ->
                if (v.toboolean()) setWeatherParameters(0, 6000, true, isThundering)
                else setWeatherParameters(0, 0, false, isThundering)
            }
        )
        prop(
            "thundering",
            get = { if (isThundering) LuaValue.TRUE else LuaValue.FALSE },
            set = { v ->
                if (v.toboolean()) setWeatherParameters(0, 6000, isRaining, true)
                else setWeatherParameters(0, 0, isRaining, false)
            }
        )
        propWithTable("players") { self ->
            val data = self.rawget("__pxrp_data").checkuserdata() as InstanceData
            PlayerListWrapper(
                source = { players() },
                playerCache = data.playerCache,
                tickProvider = data.tickProvider,
            ).toLua()
        }
        prop("regions") {
            RegionManager.all(this).map(RegionWrap::wrap).toLuaArray()
        }

        // world:spawn(entityId, pos, overrides?)
        method("spawn") { args ->
            val self = args.arg(1).checktable()
            val w = self.unwrap<ServerLevel>()
            val entityId = args.arg(2).checkjstring()
            val pos = args.arg(3).toVec3d()
            val overrides = if (args.narg() >= 4) args.arg(4).checktable() else null

            val id = resolveBlockId(entityId)
            val entityType = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(id))
                ?: throw LuaError("Сущность '$entityId' не найдена")

            val entity = entityType.create(w, EntitySpawnReason.COMMAND)
                ?: throw LuaError("Не удалось создать сущность '$entityId'")

            entity.setPos(pos.x, pos.y, pos.z)
            overrides?.let { applyOverrides(entity, it) }

            if (!w.addFreshEntity(entity)) return@method LuaValue.NIL

            EntityFactory.wrap(entity)
        }

        method("spawnHologram") { args ->
            val self = args.arg(1).checktable()
            val w = self.unwrap<ServerLevel>()
            val pos = args.arg(2).toVec3d()
            val text = args.arg(3).checkjstring()
            val opts = if (args.narg() >= 4) args.arg(4).checktable() else null

            val entityType = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse("minecraft:text_display"))
                ?: throw LuaError("Сущность 'minecraft:text_display' не найдена")

            val entity = entityType.create(w, EntitySpawnReason.COMMAND) as? Display.TextDisplay
                ?: throw LuaError("Не удалось создать text_display")

            entity.setPos(pos.x, pos.y, pos.z)
            val flags = byteArrayOf()
            val flagsByte = 0.toByte()

            opts?.let { o ->
                o.get("alignment").takeIf { it.isstring() }?.let {
                    HologramWrapper.applyAlignmentFromLua(entity, it.tojstring())
                }
                o.get("billboard").takeIf { it.isstring() }?.let {
                    HologramWrapper.parseBillboardFromLua(it.tojstring())
                }
                o.get("glowing").takeIf { it.isboolean() }?.let { entity.setGlowingTag(it.toboolean()) }
            }

            if (!w.addFreshEntity(entity)) return@method LuaValue.NIL

            HologramManager.create(entity, w).toLuaValue()
        }

        // world:setBlock(pos, blockId)
        method("setBlock") { args ->
            val self = args.arg(1).checktable()
            val w = self.unwrap<ServerLevel>()
            val pos = args.arg(2).toBlockPos()
            val blockId = resolveBlockId(args.arg(3).checkjstring())

            val block = BuiltInRegistries.BLOCK.getValue(Identifier.parse(blockId))
                ?: throw LuaError("Блок '$blockId' не найден")

            w.setBlock(pos, block.defaultBlockState(), 0x03)
            LuaValue.NIL
        }

        // world:getBlock(pos) -> blockId
        method("getBlock") { args ->
            val self = args.arg(1).checktable()
            val w = self.unwrap<ServerLevel>()
            val pos = args.arg(2).toBlockPos()
            val block = w.getBlockState(pos).block
            LuaValue.valueOf(BuiltInRegistries.BLOCK.getId(block).toString())
        }

        // world:getBlockState(pos) -> { id, properties }
        method("getBlockState") { args ->
            val self = args.arg(1).checktable()
            val w = self.unwrap<ServerLevel>()
            val pos = args.arg(2).toBlockPos()
            val state = w.getBlockState(pos)
            BlockStateCodec.stateToTable(state)
        }

        // world:setBlockState(pos, { id, properties })
        method("setBlockState") { args ->
            val self = args.arg(1).checktable()
            val w = self.unwrap<ServerLevel>()
            val pos = args.arg(2).toBlockPos()
            val tbl = args.arg(3).checktable()

            val id = tbl.get("id").checkjstring()
            val blockId = resolveBlockId(id)
            val block = BuiltInRegistries.BLOCK.getValue(Identifier.parse(blockId))
                ?: throw LuaError("Блок '$blockId' не найден")

            val state = if (tbl.get("properties").istable()) {
                BlockStateCodec.applyProperties(block, tbl.get("properties").checktable())
            } else {
                block.defaultBlockState()
            }

            w.setBlock(pos, state, 0x03)
            LuaValue.NIL
        }

        // world:fill(posA, posB, blockId)
        method("fill") { args ->
            val self = args.arg(1).checktable()
            val w = self.unwrap<ServerLevel>()
            val pos1 = args.arg(2).toBlockPos()
            val pos2 = args.arg(3).toBlockPos()
            val blockId = resolveBlockId(args.arg(4).checkjstring())

            val block = BuiltInRegistries.BLOCK.getValue(Identifier.parse(blockId))
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

            val state = block.defaultBlockState()
            for (pos in BlockPos.betweenClosed(BlockPos(minX, minY, minZ), BlockPos(maxX, maxY, maxZ))) {
                w.setBlock(pos, state, 0x02)
            }
            LuaValue.NIL
        }

        // world:particle(particleId, pos, opts?)
        method("particle") { args ->
            val self = args.arg(1).checktable()
            val w = self.unwrap<ServerLevel>()
            val id = args.arg(2).checkjstring()
            val resolvedId = if (id.contains(':')) id else "minecraft:$id"
            val particleType = BuiltInRegistries.PARTICLE_TYPE.getValue(Identifier.parse(resolvedId))
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

            w.sendParticles(effect, true, false, x, y, z, count, deltaX, deltaY, deltaZ, speed)
            LuaValue.NIL
        }

        // world:playSound(soundId, pos, voulme?, pitch?)
        method("playSound") { args ->
            val self = args.arg(1).checktable()
            val w = self.unwrap<ServerLevel>()
            val id = args.arg(2).checkjstring()
            val pos = args.arg(3).toVec3d()
            val volume = args.arg(4).optdouble(1.0).toFloat()
            val pitch = args.arg(5).optdouble(1.0).toFloat()

            val sound = BuiltInRegistries.SOUND_EVENT.getValue(Identifier.parse(id))
                ?: throw LuaError("Звук '$id' не найден")

            w.playSound(null, pos.x, pos.y, pos.z, sound, SoundSource.PLAYERS, volume, pitch)
            LuaValue.NIL
        }

        // world:getEntities(pos, radius, type?) -> {entity,...}
        method("getEntities") { args ->
            val self = args.arg(1).checktable()
            val w = self.unwrap<ServerLevel>()
            val pos = args.arg(2).toVec3d()
            val radius = args.arg(3).checkdouble()
            val typeFilter = if (args.narg() >= 4 && args.arg(4).isstring()) args.arg(4).tojstring() else null

            val box = AABB(
                pos.x - radius, pos.y - radius, pos.z - radius,
                pos.x + radius, pos.y + radius, pos.z + radius
            )

            val nearby = w.getEntities(null, box) { true }

            val entities = if (typeFilter != null) {
                val targetType = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(typeFilter))
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
            val w = self.unwrap<ServerLevel>()
            val start = args.arg(2).toVec3d()
            val dir = args.arg(3).toVec3d()
            val range = args.arg(4).checkdouble()
            val includeFluids = args.arg(5).optboolean(false)
            val includeEntities = args.arg(6).optboolean(true)

            val dirNorm = dir.normalize()
            val end = Vec3(start.x + dirNorm.x * range, start.y + dirNorm.y * range, start.z + dirNorm.z * range)

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
            val w = self.unwrap<ServerLevel>()
            val text = args.arg(2).checkjstring()
            val pos = args.arg(3).toVec3d()
            val range = args.arg(4).checkdouble()
            val overlay = if (args.narg() >= 5 && args.arg(5).isint()) args.arg(5).toint() else null

            val rangeSquare = range * range
            val players = w.players().filter { it.distanceToSqr(pos) < rangeSquare }

            if (overlay == null) {
                players.forEach { it.sendSystemMessage(Component.literal(text)) }
            } else {
                val timing = ClientboundSetTitlesAnimationPacket(20, overlay, 20)
                val title = ClientboundSetActionBarTextPacket(Component.literal(text))
                players.forEach {
                    it.connection.send(timing)
                    it.connection.send(title)
                }
            }
            LuaValue.NIL
        }

        // world:getBiome(pos) -> string?
        method("getBiome") { args ->
            val self = args.arg(1).checktable()
            val w = self.unwrap<ServerLevel>()
            val pos = args.arg(2).toBlockPos()
            val biome = w.getUncachedNoiseBiome(pos.x, pos.y, pos.z)
            val biomeKey = biome.unwrapKey()
            val biomeId = biomeKey.map { it.identifier().toString() }.orElse(null)
            if (biomeId != null) LuaValue.valueOf(biomeId) else LuaValue.NIL
        }

        // world:getBorder() -> { center, size, damage, ... }
        method("getBorder") { args ->
            val self = args.arg(1).checktable()
            val w = self.unwrap<ServerLevel>()
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
            val w = self.unwrap<ServerLevel>()
            val pos = args.arg(2).toVec3d()
            val power = args.arg(3).checkdouble().toFloat()
            val opts = if (args.narg() >= 4 && args.arg(4).istable()) args.arg(4).checktable() else null

            val fire = opts?.get("fire")?.optboolean(false) ?: false
            val destruction = opts?.get("destruction")?.optjstring("break") ?: "break"
            val interaction = try {
                Level.ExplosionInteraction.valueOf(destruction.uppercase())
            } catch (_: Exception) {
                Level.ExplosionInteraction.BLOCK
            }

            w.explode(null, pos.x, pos.y, pos.z, power, fire, interaction)
            LuaValue.NIL
        }

        // world:strike(pos, opts?)
        method("strike") { args ->
            val self = args.arg(1).checktable()
            val w = self.unwrap<ServerLevel>()
            val pos = args.arg(2).toVec3d()
            val opts = if (args.narg() >= 3 && args.arg(3).istable()) args.arg(3).checktable() else null

            val bolt = EntityType.LIGHTNING_BOLT.create(w, EntitySpawnReason.COMMAND)
                ?: throw LuaError("Не удалось создать молнию")
            bolt.setPos(pos.x, pos.y, pos.z)
            bolt.setVisualOnly(opts?.get("effect")?.optboolean(false) ?: false)
            w.addFreshEntity(bolt)
            LuaValue.NIL
        }

        // world:createRegion(posA, posB) -> region
        method("createRegion") { args ->
            val self = args.arg(1).checktable()
            val w = self.unwrap<ServerLevel>()
            if (args.narg() < 3) throw LuaError("createRegion: ожидается (posA, posB)")
            val a = args.arg(2).toVec3d()
            val b = args.arg(3).toVec3d()
            val min = Vec3(minOf(a.x, b.x), minOf(a.y, b.y), minOf(a.z, b.z))
            val max = Vec3(maxOf(a.x, b.x), maxOf(a.y, b.y), maxOf(a.z, b.z))
            val region = RegionManager.create(w, AABB(min, max))
            RegionWrap.wrap(region)
        }

        // world:getRegion(id) -> region?
        method("getRegion") { args ->
            val self = args.arg(1).checktable()
            val w = self.unwrap<ServerLevel>()
            require(args.narg() == 2) { "getRegion(id) requires 1 argument" }
            val id = args.arg(2).checkint()
            val r = RegionManager.get(id)
            if (r != null && r.world === w) RegionWrap.wrap(r) else LuaValue.NIL
        }

        // world:getRegionsAt(pos) -> {region,...}
        method("getRegionsAt") { args ->
            val self = args.arg(1).checktable()
            val w = self.unwrap<ServerLevel>()
            require(args.narg() == 2) { "getRegionsAt(pos) requires 1 argument" }
            val pos = args.arg(2).toVec3d()
            RegionManager.getAt(w, pos).map(RegionWrap::wrap).toLuaArray()
        }

        // world:getEntitiesBySelector(selector, opts?) -> {entity,...}
        method("getEntitiesBySelector") { args ->
            val self = args.arg(1).checktable()
            val w = self.unwrap<ServerLevel>()
            val selector = args.arg(2).checkjstring()
            val opts = if (args.narg() >= 3 && args.arg(3).istable()) args.arg(3).checktable() else null

            val center = opts?.get("at")?.let { v ->
                if (v.istable()) v.toVec3d() else null
            }
            val sourceEntity = opts?.get("as")?.let { v ->
                if (v.istable()) v.unwrapOrNull<Entity>() else null
            }

            val parsed = try {
                EntitySelectorParser(StringReader(selector), true).parse()
            } catch (e: CommandSyntaxException) {
                throw LuaError("Неверный селектор '$selector': ${e.message}")
            }

            val server = w.server ?: throw LuaError("Мировой сервер недоступен")
            val base = server.createCommandSourceStack().withLevel(w)
            val withPos = if (center != null) base.withPosition(center) else base
            val source = if (sourceEntity != null) withPos.withEntity(sourceEntity) else withPos

            val entities = try {
                parsed.findEntities(source)
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
                val maxAttr = entity.getAttribute(Attributes.MAX_HEALTH)
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
                entity.customName = Component.literal(v.tojstring())
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun buildParticleEffect(
        type: ParticleType<*>,
        data: LuaTable?,
        world: ServerLevel,
        id: String
    ): ParticleOptions {
        if (type is ParticleOptions) return type

        val nbt = buildParticleDataNbt(type, data, world, id)
        val ops = RegistryOps.create(NbtOps.INSTANCE, world.registryAccess())
        return (type as ParticleType<ParticleOptions>).codec().codec()
            .parse(ops, nbt)
            .getOrThrow { msg: String -> LuaError("Частица '$id': $msg") }
    }

    private fun buildParticleDataNbt(
        type: ParticleType<*>,
        data: LuaTable?,
        world: ServerLevel,
        id: String,
    ): CompoundTag {
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

        val c = CompoundTag()
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
                val itemCompound = CompoundTag()
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
                val targetCompound = CompoundTag()
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
                val dest = CompoundTag()
                dest.putString("type", "minecraft:block")
                dest.putInt("x", from.x.toInt())
                dest.putInt("y", from.y.toInt())
                dest.putInt("z", from.z.toInt())
                c.put("destination", dest)
                c.putInt("arrival_in_ticks", t.get("arrivalInTicks").optint(1))
            }

            else -> {
                val name = BuiltInRegistries.PARTICLE_TYPE.getId(type)?.toString() ?: id
                throw LuaError("Частица '$name' не поддерживается в data")
            }
        }
        return c
    }

}
