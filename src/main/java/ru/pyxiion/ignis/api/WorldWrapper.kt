package ru.pyxiion.ignis.api

import net.minecraft.entity.Entity
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.SpawnReason
import net.minecraft.entity.attribute.EntityAttributes
import net.minecraft.entity.decoration.DisplayEntity
import net.minecraft.entity.mob.MobEntity
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtOps
import net.minecraft.network.packet.s2c.play.OverlayMessageS2CPacket
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket
import net.minecraft.particle.ParticleEffect
import net.minecraft.particle.ParticleType
import net.minecraft.registry.Registries
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.lib.jse.CoerceJavaToLua
import ru.pyxiion.ignis.luaToNbt
import ru.pyxiion.ignis.toBlockPos
import ru.pyxiion.ignis.toVec3d
import java.util.UUID

class WorldWrapper(private val world: ServerWorld) {

    companion object {
        var playerCache: Map<UUID, LuaValue>? = null

        private val worldKeys = listOf(
            "name", "time", "raining", "thundering", "players",
            "spawn", "setBlock", "getBlock", "fill", "particle",
            "playSound", "getEntities", "raycast", "broadcastInRange",
            "spawnHologram",
        )

        fun initMeta(meta: LuaTable) {
            meta.set("__index", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val key = args.arg(2).tojstring()
                    val w = self.rawget("__pxrp_object").checkuserdata() as ServerWorld

                    return when (key) {
                        "name" -> LuaValue.valueOf(w.registryKey.value.path)
                        "time" -> LuaValue.valueOf(w.timeOfDay.toDouble())
                        "raining" -> if (w.isRaining) LuaValue.TRUE else LuaValue.FALSE
                        "thundering" -> if (w.isThundering) LuaValue.TRUE else LuaValue.FALSE
                        "players" -> {
                            val t = LuaTable()
                            w.players.forEachIndexed { i, p ->
                                val cached = playerCache?.get(p.uuid)
                                if (cached != null) {
                                    t.set(i + 1, cached)
                                } else {
                                    t.set(i + 1, PlayerWrapper(p).toLuaValue())
                                }
                            }
                            t
                        }
                        else -> meta.get(key)
                    }
                }
            })

            meta.set("__newindex", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val key = args.arg(2).tojstring()
                    val value = args.arg(3)
                    val w = self.rawget("__pxrp_object").checkuserdata() as ServerWorld

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

            meta.set("__pairs", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1)
                    val keys = worldKeys
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

            meta.rawset("spawn", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val w = self.rawget("__pxrp_object").checkuserdata() as ServerWorld
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

                    return when (entity) {
                        is MobEntity -> MobWrapper(entity).toLuaValue()
                        else -> EntityWrapper(entity).toLuaValue()
                    }
                }
            })

            meta.rawset("spawnHologram", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val w = self.rawget("__pxrp_object").checkuserdata() as ServerWorld
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
                            HologramWrapper.applyFlagFromLua(entity, DisplayEntity.TextDisplayEntity.SHADOW_FLAG, it.toboolean())
                        }
                        o.get("seeThrough").takeIf { it.isboolean() }?.let {
                            HologramWrapper.applyFlagFromLua(entity, DisplayEntity.TextDisplayEntity.SEE_THROUGH_FLAG, it.toboolean())
                        }
                        o.get("glowing").takeIf { it.isboolean() }?.let { entity.isGlowing = it.toboolean() }
                    }

                    if (!w.spawnEntity(entity)) {
                        return LuaValue.NIL
                    }

                    return HologramManager.create(entity, w).toLuaValue()
                }
            })

            meta.rawset("setBlock", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val w = self.rawget("__pxrp_object").checkuserdata() as ServerWorld
                    val pos = args.arg(2).toBlockPos()
                    val blockId = resolveBlockId(args.arg(3).checkjstring())

                    val block = Registries.BLOCK.get(Identifier.of(blockId))
                        ?: throw LuaError("Блок '$blockId' не найден")

                    w.setBlockState(pos, block.defaultState, 0x03)
                    return LuaValue.NIL
                }
            })

            meta.rawset("getBlock", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val w = self.rawget("__pxrp_object").checkuserdata() as ServerWorld
                    val pos = args.arg(2).toBlockPos()
                    val block = w.getBlockState(pos).block
                    return LuaValue.valueOf(Registries.BLOCK.getId(block).toString())
                }
            })

            meta.rawset("fill", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val w = self.rawget("__pxrp_object").checkuserdata() as ServerWorld
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
            })

            meta.rawset("particle", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val w = self.rawget("__pxrp_object").checkuserdata() as ServerWorld
                    val id = args.arg(2).checkjstring()
                    val resolvedId = if (id.contains(':')) id else "minecraft:$id"
                    val key = RegistryKey.of(RegistryKeys.PARTICLE_TYPE, Identifier.of(resolvedId))
                    val particleType = Registries.PARTICLE_TYPE.get(key)
                        ?: throw LuaError("Частица '$id' не найдена")

                    val (x, y, z) = resolveOperand(args.arg(3))

                    val count: Int
                    val deltaX: Double; val deltaY: Double; val deltaZ: Double
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
                        data = opts.get("data").opttable(null)
                        if (data == null) {
                            val known = setOf("count", "spread", "speed", "data")
                            if (opts.keys().any { !known.contains(it.tojstring()) }) {
                                data = opts
                            }
                        }
                    } else {
                        count = 1; deltaX = 0.0; deltaY = 0.0; deltaZ = 0.0; speed = 0.0; data = null
                    }

                    val effect = buildParticleEffect(particleType, data, w, resolvedId)

                    w.players.forEach { player ->
                        w.spawnParticles(player, effect, true, false, x, y, z, count, deltaX, deltaY, deltaZ, speed)
                    }
                    return LuaValue.NIL
                }
            })

            meta.rawset("playSound", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val w = self.rawget("__pxrp_object").checkuserdata() as ServerWorld
                    val id = args.arg(2).checkjstring()
                    val x = args.arg(3).checkdouble()
                    val y = args.arg(4).checkdouble()
                    val z = args.arg(5).checkdouble()
                    val volume = args.arg(6).optdouble(1.0).toFloat()
                    val pitch = args.arg(7).optdouble(1.0).toFloat()

                    val key = RegistryKey.of(RegistryKeys.SOUND_EVENT, Identifier.of(id))
                    val sound = Registries.SOUND_EVENT.get(key)
                        ?: throw LuaError("Звук '$id' не найден")

                    w.playSound(null, x, y, z, sound, SoundCategory.PLAYERS, volume, pitch)
                    return LuaValue.NIL
                }
            })

            meta.rawset("getEntities", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val w = self.rawget("__pxrp_object").checkuserdata() as ServerWorld
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

                    val list = LuaTable()
                    entities.forEachIndexed { i, entity ->
                        list.set(i + 1, when (entity) {
                            is MobEntity -> MobWrapper(entity).toLuaValue()
                            else -> EntityWrapper(entity).toLuaValue()
                        })
                    }
                    return list
                }
            })

            // .raycast(self, start, dir, range, includeFluids=false, includeEntities=true)
            meta.rawset("raycast", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val w = self.rawget("__pxrp_object").checkuserdata() as ServerWorld
                    val start = args.arg(2).toVec3d()
                    val dir = args.arg(3).toVec3d()
                    val range = args.arg(4).checkdouble()
                    val includeFluids = args.arg(5).optboolean(false)
                    val includeEntities = args.arg(6).optboolean(true)

                    val dirNorm = dir.normalize()
                    val end = Vec3d(start.x + dirNorm.x * range, start.y + dirNorm.y * range, start.z + dirNorm.z * range)

                    return performRaycast(start, end, range, includeFluids, includeEntities, w, null)
                }
            })

            meta.rawset("broadcastInRange", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val w = self.rawget("__pxrp_object").checkuserdata() as ServerWorld
                    val text = args.arg(2).checkjstring()
                    val x = args.arg(3).checkdouble()
                    val y = args.arg(4).checkdouble()
                    val z = args.arg(5).checkdouble()
                    val range = args.arg(6).checkdouble()
                    val overlay = if (args.narg() >= 7 && args.arg(7).isint()) args.arg(7).toint() else null

                    val rangeSquare = range * range
                    val pos = Vec3d(x, y, z)

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
                    return LuaValue.NIL
                }
            })
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

            val ops = world.registryManager.getOps(NbtOps.INSTANCE)
            val nbt = if (data != null) {
                luaToNbt(normalizeData(data)) as NbtCompound
            } else {
                NbtCompound()
            }

            return (type as ParticleType<ParticleEffect>).codec.codec()
                .parse(ops, nbt)
                .getOrThrow { msg: String -> LuaError("Частица '$id': $msg") }
        }

        private fun normalizeData(data: LuaTable): LuaTable {
            val result = LuaTable()
            for (key in data.keys()) {
                val k = key.tojstring()
                val v = data.get(key)
                when (k) {
                    "block" -> {
                        val blockId = v.checkjstring()
                        val resolved = if (blockId.contains(':')) blockId else "minecraft:$blockId"
                        result.rawset("block_state", LuaValue.valueOf(resolved))
                    }
                    "item" -> {
                        val itemId = v.checkjstring()
                        val resolved = if (itemId.contains(':')) itemId else "minecraft:$itemId"
                        val itemTable = LuaTable()
                        itemTable.rawset("id", LuaValue.valueOf(resolved))
                        itemTable.rawset("count", LuaValue.valueOf(1))
                        result.rawset("item", itemTable)
                    }
                    "color" -> {
                        val (r, g, b) = extractColorValues(v)
                        result.rawset("color", LuaValue.valueOf(rgbToInt(r, g, b)))
                    }
                    "fromColor" -> {
                        val (r, g, b) = extractColorValues(v)
                        result.rawset("from_color", LuaValue.valueOf(rgbToInt(r, g, b)))
                    }
                    "toColor" -> {
                        val (r, g, b) = extractColorValues(v)
                        result.rawset("to_color", LuaValue.valueOf(rgbToInt(r, g, b)))
                    }
                    else -> result.rawset(camelToSnake(k), v)
                }
            }
            return result
        }

        private fun extractColorValues(v: LuaValue): Triple<Double, Double, Double> {
            val ct = v.checktable()
            val r = ct.get(1).optdouble(ct.get("r").optdouble(0.0))
            val g = ct.get(2).optdouble(ct.get("g").optdouble(0.0))
            val b = ct.get(3).optdouble(ct.get("b").optdouble(0.0))
            return Triple(r, g, b)
        }

        private fun rgbToInt(r: Double, g: Double, b: Double): Int {
            val scale = if (r > 1.0 || g > 1.0 || b > 1.0) 1.0 else 255.0
            val ri = (r * scale).toInt().coerceIn(0, 255)
            val gi = (g * scale).toInt().coerceIn(0, 255)
            val bi = (b * scale).toInt().coerceIn(0, 255)
            return (ri shl 16) or (gi shl 8) or bi
        }

        private fun camelToSnake(s: String): String {
            return s.replace(Regex("([a-z])([A-Z])")) { "${it.groupValues[1]}_${it.groupValues[2].lowercase()}" }
        }
    }

    fun toLuaValue(): LuaValue {
        val t = LuaTable()
        t.setmetatable(MetaTableRegistry.WORLD)
        t.rawset("__pxrp_type", LuaValue.valueOf("world"))
        t.rawset("__pxrp_object", CoerceJavaToLua.coerce(world))
        return t
    }
}
