package ru.pyxiion.pxrp.api

import net.minecraft.entity.Entity
import net.minecraft.entity.EquipmentSlot
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.attribute.EntityAttributes
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.item.ItemStack
import net.minecraft.nbt.NbtByte
import net.minecraft.nbt.NbtByteArray
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtDouble
import net.minecraft.nbt.NbtElement
import net.minecraft.nbt.NbtFloat
import net.minecraft.nbt.NbtInt
import net.minecraft.nbt.NbtIntArray
import net.minecraft.nbt.NbtList
import net.minecraft.nbt.NbtLong
import net.minecraft.nbt.NbtLongArray
import net.minecraft.nbt.NbtShort
import net.minecraft.nbt.NbtString
import net.minecraft.registry.Registries
import net.minecraft.server.world.ServerWorld
import net.minecraft.storage.NbtReadView
import net.minecraft.storage.NbtWriteView
import net.minecraft.text.Text
import net.minecraft.util.ErrorReporter
import net.minecraft.util.Identifier
import net.minecraft.entity.effect.StatusEffectInstance
import net.minecraft.util.hit.HitResult
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import net.minecraft.world.RaycastContext
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.VarArgFunction
import ru.pyxiion.pxrp.luaTableOf
import ru.pyxiion.pxrp.toBlockPos
import ru.pyxiion.pxrp.toVec3d

class EntityWrapper(private val entity: Entity) {
    private val living: LivingEntity? = entity as? LivingEntity

    private val posProxy by lazy { livePosTable(entity) }

    private val attributeAccessors = mapOf(
        "speed" to EntityAttributes.MOVEMENT_SPEED,
        "armor" to EntityAttributes.ARMOR,
        "armorToughness" to EntityAttributes.ARMOR_TOUGHNESS,
        "attackDamage" to EntityAttributes.ATTACK_DAMAGE,
        "attackSpeed" to EntityAttributes.ATTACK_SPEED,
        "knockbackResistance" to EntityAttributes.KNOCKBACK_RESISTANCE,
        "luck" to EntityAttributes.LUCK,
        "stepHeight" to EntityAttributes.STEP_HEIGHT,
        "blockBreakSpeed" to EntityAttributes.BLOCK_BREAK_SPEED,
        "gravity" to EntityAttributes.GRAVITY,
        "scale" to EntityAttributes.SCALE,
        "safeFallDistance" to EntityAttributes.SAFE_FALL_DISTANCE,
        "flyingSpeed" to EntityAttributes.FLYING_SPEED,
    )

    private val equipmentSlots = mapOf(
        "mainhand" to EquipmentSlot.MAINHAND,
        "offhand" to EquipmentSlot.OFFHAND,
        "head" to EquipmentSlot.HEAD,
        "chest" to EquipmentSlot.CHEST,
        "legs" to EquipmentSlot.LEGS,
        "feet" to EquipmentSlot.FEET,
    )

    fun toLuaValue(): LuaValue {
        val e = entity
        val liv = living
        val tagsProxy = tagsTable(e)

        val metatable = LuaTable()
        metatable.set("__index", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val key = args.arg(2).tojstring()
                return when (key) {
                    "uuid" -> LuaValue.valueOf(e.uuid.toString())
                    "type" -> LuaValue.valueOf(Registries.ENTITY_TYPE.getId(e.type).toString())
                    "name" -> LuaValue.valueOf(e.name.literalString ?: e.name.string)
                    "displayName" -> LuaValue.valueOf(e.displayName?.string ?: e.name.literalString!!)
                    "customName" -> {
                        val cn = e.customName
                        if (cn != null) LuaValue.valueOf(cn.string) else LuaValue.NIL
                    }
                    "world" -> World(e.entityWorld as ServerWorld).toLuaValue()
                    "pos" -> posProxy
                    "dir" -> Vector.fromMc(e.rotationVector).toLuaValue()
                    "bodyDir" -> Vector.fromRotation(e.bodyYaw, 0.0f).toLuaValue()
                    "fallDistance" -> LuaValue.valueOf(e.fallDistance.toDouble())
                    "fireTicks" -> LuaValue.valueOf(e.fireTicks)
                    "glowing" -> LuaValue.valueOf(e.isGlowing)
                    "invulnerable" -> LuaValue.valueOf(e.isInvulnerable)
                    "isSneaking" -> LuaValue.valueOf(e.isSneaking)
                    "isSprinting" -> LuaValue.valueOf(e.isSprinting)
                    "air" -> LuaValue.valueOf(e.air)
                    "maxAir" -> LuaValue.valueOf(e.maxAir)
                    "removed" -> LuaValue.valueOf(e.isRemoved)

                    "health" -> {
                        if (liv != null) LuaValue.valueOf(liv.health.toDouble()) else LuaValue.NIL
                    }
                    "maxHealth" -> {
                        val v = liv?.getAttributeInstance(EntityAttributes.MAX_HEALTH)?.value
                        if (v != null) LuaValue.valueOf(v) else LuaValue.NIL
                    }

                    in attributeAccessors -> {
                        val attr = attributeAccessors.getValue(key)
                        val v = liv?.getAttributeInstance(attr)?.value
                        if (v != null) LuaValue.valueOf(v) else LuaValue.NIL
                    }

                    in equipmentSlots -> {
                        val slot = equipmentSlots.getValue(key)
                        val stack = liv?.getEquippedStack(slot)
                        if (stack != null && !stack.isEmpty) ItemStackWrapper.wrap(stack) else LuaValue.NIL
                    }

                    "readNbt" -> object : VarArgFunction() {
                        override fun invoke(args: Varargs): Varargs {
                            val writeView = NbtWriteView.create(ErrorReporter.EMPTY)
                            e.saveData(writeView)
                            return nbtToLua(writeView.getNbt())
                        }
                    }

                    "writeNbt" -> object : VarArgFunction() {
                        override fun invoke(args: Varargs): Varargs {
                            val nbtTable = args.arg(2)
                            if (!nbtTable.istable()) throw LuaError("writeNbt: ожидается таблица")
                            val compound = luaToNbt(nbtTable) as NbtCompound
                            val world = e.entityWorld as ServerWorld
                            val readView = NbtReadView.create(ErrorReporter.EMPTY, world.registryManager, compound)
                            e.readData(readView)
                            return LuaValue.NIL
                        }
                    }

                    else -> MetaTableRegistry.ENTITY.get(key)
                }
            }
        })

        metatable.set("__pairs", entityPairs(e, liv))

        metatable.set("__newindex", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val key = args.arg(2).tojstring()
                val value = args.arg(3)
                when (key) {
                    "pos" -> {
                        val v = value.toVec3d()
                        e.setPosition(v.x, v.y, v.z)
                    }
                    "customName" -> {
                        e.customName = if (value.isnil()) null else Text.literal(value.tojstring())
                    }
                    "fallDistance" -> e.fallDistance = value.todouble()
                    "fireTicks" -> e.fireTicks = value.toint()
                    "glowing" -> e.isGlowing = value.toboolean()
                    "invulnerable" -> e.isInvulnerable = value.toboolean()
                    "isSneaking" -> e.isSneaking = value.toboolean()
                    "isSprinting" -> e.isSprinting = value.toboolean()
                    "air" -> e.air = value.toint()

                    "health" -> liv?.let { it.health = value.tofloat() }

                    "maxHealth" -> {
                        liv?.getAttributeInstance(EntityAttributes.MAX_HEALTH)?.let { attr ->
                            attr.baseValue = value.todouble()
                            liv.health = Math.min(liv.health, attr.value.toFloat())
                        }
                    }

                    in attributeAccessors -> {
                        val attr = attributeAccessors.getValue(key)
                        liv?.getAttributeInstance(attr)?.baseValue = value.todouble()
                    }

                    in equipmentSlots -> {
                        val slot = equipmentSlots.getValue(key)
                        val stack = if (value.isnil()) {
                            ItemStack.EMPTY
                        } else {
                            ItemStackWrapper.unwrap(value)
                                ?: throw LuaError("setItem: ожидается ItemStack от mc.createItem")
                        }
                        liv?.equipStack(slot, stack)
                    }
                }
                return LuaValue.NIL
            }
        })

        val t = LuaTable()
        t.setmetatable(metatable)
        t.rawset("tags", tagsProxy)
        t.rawset("damage", entityDamageFunc(e))
        t.rawset("raycast", raycastFunc(e))
        t.rawset("addEffect", addEffectFunc(liv))
        t.rawset("removeEffect", removeEffectFunc(liv))
        t.rawset("hasEffect", hasEffectFunc(liv))
        t.rawset("setOnFireFor", entitySetOnFireForFunc(e))
        return t
    }

    private fun tagsTable(entity: Entity): LuaValue {
        val meta = LuaTable()
        meta.set("__index", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val tag = args.arg(2).tojstring()
                return LuaValue.valueOf(entity.commandTags.contains(tag))
            }
        })
        meta.set("__newindex", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val tag = args.arg(2).tojstring()
                val value = args.arg(3)
                if (value.toboolean()) {
                    entity.addCommandTag(tag)
                } else {
                    entity.removeCommandTag(tag)
                }
                return LuaValue.NIL
            }
        })
        meta.set("__pairs", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val tags = entity.commandTags.toList()
                val iterator = object : VarArgFunction() {
                    private var index = 0
                    override fun invoke(args: Varargs): Varargs {
                        if (index >= tags.size) return LuaValue.NIL
                        val tag = tags[index]
                        index++
                        return LuaValue.varargsOf(arrayOf(LuaValue.valueOf(tag), LuaValue.valueOf(true)))
                    }
                }
                return LuaValue.varargsOf(arrayOf(iterator, LuaValue.NIL, LuaValue.NIL))
            }
        })
        val table = LuaTable()
        table.setmetatable(meta)
        return table
    }

    private fun entityPairs(e: Entity, liv: LivingEntity?): LuaValue {
        val keys = mutableListOf(
            "uuid", "type", "name", "displayName", "customName",
            "world", "pos", "dir", "bodyDir",
            "fallDistance", "fireTicks", "glowing", "invulnerable",
            "isSneaking", "isSprinting", "air", "maxAir", "removed",
            "health", "maxHealth",
        )
        keys.addAll(attributeAccessors.keys)
        keys.addAll(equipmentSlots.keys)
        keys.add("tags")
        keys.add("damage")
        keys.add("readNbt")
        keys.add("writeNbt")
        keys.add("raycast")
        keys.add("addEffect")
        keys.add("removeEffect")
        keys.add("hasEffect")
        keys.add("setOnFireFor")

        return object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val self = args.arg(1)
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
        }
    }

    private fun livePosTable(e: Entity): LuaValue {
        val meta = LuaTable()
        meta.set("__index", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val field = args.arg(2).tojstring()
                val v = e.entityPos
                return when (field) {
                    "x" -> LuaValue.valueOf(v.x)
                    "y" -> LuaValue.valueOf(v.y)
                    "z" -> LuaValue.valueOf(v.z)
                    else -> LuaValue.NIL
                }
            }
        })
        meta.set("__newindex", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val field = args.arg(2).tojstring()
                val value = args.arg(3).todouble()
                val v = e.entityPos
                e.setPosition(
                    if (field == "x") value else v.x,
                    if (field == "y") value else v.y,
                    if (field == "z") value else v.z,
                )
                return LuaValue.NIL
            }
        })
        val t = LuaTable()
        t.setmetatable(meta)
        return t
    }

    private fun nbtToLua(element: NbtElement): LuaValue {
        return when (element) {
            is NbtCompound -> {
                val t = LuaTable()
                for (key in element.keys) {
                    val value = element.get(key) ?: continue
                    t.set(key, nbtToLua(value))
                }
                t
            }
            is NbtList -> {
                val t = LuaTable()
                for (i in 0 until element.size) {
                    t.set(i + 1, nbtToLua(element.get(i)))
                }
                t
            }
            is NbtByte -> LuaValue.valueOf(element.value.toInt() != 0)
            is NbtShort -> LuaValue.valueOf(element.value.toInt())
            is NbtInt -> LuaValue.valueOf(element.value)
            is NbtLong -> LuaValue.valueOf(element.value.toDouble())
            is NbtFloat -> LuaValue.valueOf(element.value.toDouble())
            is NbtDouble -> LuaValue.valueOf(element.value)
            is NbtString -> LuaValue.valueOf(element.value)
            is NbtByteArray -> {
                val t = LuaTable()
                for ((i, b) in element.getByteArray().withIndex()) {
                    t.set(i + 1, LuaValue.valueOf(b.toInt() and 0xFF))
                }
                t
            }
            is NbtIntArray -> {
                val t = LuaTable()
                for ((i, v) in element.getIntArray().withIndex()) {
                    t.set(i + 1, LuaValue.valueOf(v))
                }
                t
            }
            is NbtLongArray -> {
                val t = LuaTable()
                for ((i, v) in element.getLongArray().withIndex()) {
                    t.set(i + 1, LuaValue.valueOf(v.toDouble()))
                }
                t
            }
            else -> LuaValue.NIL
        }
    }

    private fun luaToNbt(value: LuaValue): NbtElement {
        return when {
            value.isboolean() -> NbtByte.of(if (value.toboolean()) 1 else 0)
            value.isint() -> NbtInt.of(value.toint())
            value.islong() -> NbtLong.of(value.tolong())
            value.isnumber() -> NbtDouble.of(value.todouble())
            value.isstring() -> NbtString.of(value.tojstring())
            value.istable() -> {
                val t = value.checktable()
                var hasStringKeys = false
                var k = LuaValue.NIL
                while (true) {
                    val next = t.next(k)
                    if (next.isnil(1)) break
                    val key = next.arg(1)
                    if (!key.isint() || key.toint() < 1) {
                        hasStringKeys = true
                        break
                    }
                    k = key
                }

                if (!hasStringKeys && t.length() > 0) {
                    val list = NbtList()
                    for (i in 1..t.length()) {
                        list.add(luaToNbt(t.get(i)))
                    }
                    list
                } else {
                    val compound = NbtCompound()
                    var k2 = LuaValue.NIL
                    while (true) {
                        val next = t.next(k2)
                        if (next.isnil(1)) break
                        val key = next.arg(1).tojstring()
                        val v = next.arg(2)
                        compound.put(key, luaToNbt(v))
                        k2 = next.arg(1)
                    }
                    compound
                }
            }
            else -> throw LuaError("writeNbt: неподдерживаемый тип Lua: ${value.typename()}")
        }
    }

    private fun raycastFunc(e: Entity) = object : VarArgFunction() {
        override fun invoke(args: Varargs): Varargs {
            val range = args.arg(2).checkdouble()
            val includeFluids = args.arg(3).optboolean(false)

            val start = e.eyePos
            val dir = e.rotationVector.normalize()
            val end = Vec3d(start.x + dir.x * range, start.y + dir.y * range, start.z + dir.z * range)

            val blockHit = e.entityWorld.raycast(RaycastContext(
                start, end,
                RaycastContext.ShapeType.OUTLINE,
                if (includeFluids) RaycastContext.FluidHandling.ANY else RaycastContext.FluidHandling.NONE,
                e
            ))
            var blockDist = range
            if (blockHit.type == HitResult.Type.BLOCK) {
                blockDist = start.distanceTo(blockHit.pos)
            }

            val box = e.boundingBox.stretch(dir.multiply(range)).expand(1.0)
            val nearby = e.entityWorld.getOtherEntities(e, box)
            var closestEntity: Entity? = null
            var closestDist = blockDist

            for (target in nearby) {
                if (target == e || target !is LivingEntity) continue
                val targetBox = target.boundingBox.expand(0.3)
                val hit = targetBox.raycast(start, end).orElse(null) ?: continue
                val dist = start.distanceTo(hit)
                if (dist < closestDist) {
                    closestDist = dist
                    closestEntity = target
                }
            }

            if (closestEntity != null) {
                return EntityWrapper(closestEntity).toLuaValue()
            }

            if (blockHit.type == HitResult.Type.BLOCK) {
                val pos = blockHit.blockPos
                return luaTableOf(
                    "x" to LuaValue.valueOf(pos.x.toDouble()),
                    "y" to LuaValue.valueOf(pos.y.toDouble()),
                    "z" to LuaValue.valueOf(pos.z.toDouble())
                )
            }

            return LuaValue.NIL
        }
    }

    private fun addEffectFunc(liv: LivingEntity?) = object : VarArgFunction() {
        override fun invoke(args: Varargs): Varargs {
            if (liv == null) return LuaValue.NIL
            val effectId = args.arg(2).checkjstring()
            val duration = args.arg(3).checkint()
            val amplifier = args.arg(4).optint(0)
            val particles = args.arg(5).optboolean(true)
            val icon = args.arg(6).optboolean(true)

            val effect = Registries.STATUS_EFFECT.getEntry(Identifier.of(effectId))
                .orElseThrow { LuaError("Эффект '$effectId' не найден") }
            val instance = StatusEffectInstance(effect, duration, amplifier, false, particles, icon)
            return LuaValue.valueOf(liv.addStatusEffect(instance))
        }
    }

    private fun removeEffectFunc(liv: LivingEntity?) = object : VarArgFunction() {
        override fun invoke(args: Varargs): Varargs {
            if (liv == null) return LuaValue.NIL
            val effectId = args.arg(2).checkjstring()
            val effect = Registries.STATUS_EFFECT.getEntry(Identifier.of(effectId))
                .orElseThrow { LuaError("Эффект '$effectId' не найден") }
            return LuaValue.valueOf(liv.removeStatusEffect(effect))
        }
    }

    private fun hasEffectFunc(liv: LivingEntity?) = object : VarArgFunction() {
        override fun invoke(args: Varargs): Varargs {
            if (liv == null) return LuaValue.FALSE
            val effectId = args.arg(2).checkjstring()
            val effect = Registries.STATUS_EFFECT.getEntry(Identifier.of(effectId))
                .orElseThrow { LuaError("Эффект '$effectId' не найден") }
            return LuaValue.valueOf(liv.hasStatusEffect(effect))
        }
    }

    private fun entityDamageFunc(e: Entity) = object : VarArgFunction() {
        override fun invoke(args: Varargs): Varargs {
            val amount = args.arg(2).checkdouble().toFloat()
            val world = e.entityWorld as ServerWorld
            val sourceEntity = if (args.narg() >= 3 && args.arg(3).istable()) {
                val uuid = args.arg(3).checktable().get("uuid")
                if (uuid.isstring()) world.getEntity(java.util.UUID.fromString(uuid.tojstring())) else null
            } else null
            val damageSource = when (sourceEntity) {
                is PlayerEntity -> world.damageSources.playerAttack(sourceEntity)
                is LivingEntity -> world.damageSources.mobAttack(sourceEntity)
                else -> world.damageSources.generic()
            }
            e.damage(world, damageSource, amount)
            return LuaValue.NIL
        }
    }

    private fun entitySetOnFireForFunc(e: Entity) = object : VarArgFunction() {
        override fun invoke(args: Varargs): Varargs {
            val ticks = args.arg(2).checkint()
            e.fireTicks = ticks
            e.setOnFire(true)
            return LuaValue.NIL
        }
    }
}
