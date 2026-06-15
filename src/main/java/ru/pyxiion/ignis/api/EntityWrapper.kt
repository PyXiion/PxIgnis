package ru.pyxiion.ignis.api

import net.minecraft.entity.Entity
import net.minecraft.entity.EquipmentSlot
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.attribute.EntityAttributes
import net.minecraft.entity.effect.StatusEffectInstance
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.item.ItemStack
import net.minecraft.nbt.NbtCompound
import net.minecraft.registry.Registries
import net.minecraft.server.world.ServerWorld
import net.minecraft.storage.NbtReadView
import net.minecraft.storage.NbtWriteView
import net.minecraft.text.Text
import net.minecraft.util.ErrorReporter
import net.minecraft.util.Identifier
import net.minecraft.util.math.Vec3d
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.lib.jse.CoerceJavaToLua
import ru.pyxiion.ignis.luaToNbt
import ru.pyxiion.ignis.nbtToLua
import ru.pyxiion.ignis.toVec3d
import java.util.UUID

class EntityWrapper(private val entity: Entity) {

    fun toLuaValue(): LuaValue {
        val t = LuaTable()
        t.setmetatable(MetaTableRegistry.ENTITY)
        t.rawset("__pxrp_type", LuaValue.valueOf("entity"))
        t.rawset("__pxrp_object", CoerceJavaToLua.coerce(entity))
        return t
    }

    companion object {
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

        internal val entityKeys = listOf(
            "uuid", "type", "name", "displayName", "customName",
            "world", "pos", "dir", "bodyDir",
            "fallDistance", "fireTicks", "glowing", "invulnerable",
            "isSneaking", "isSprinting", "air", "maxAir", "removed",
            "health", "maxHealth",
            "speed", "armor", "armorToughness", "attackDamage", "attackSpeed",
            "knockbackResistance", "luck", "stepHeight", "blockBreakSpeed",
            "gravity", "scale", "safeFallDistance", "flyingSpeed",
            "mainhand", "offhand", "head", "chest", "legs", "feet",
            "tags", "damage", "readNbt", "writeNbt",
            "raycast", "addEffect", "removeEffect", "hasEffect", "setOnFireFor",
        )

        fun initMeta(meta: LuaTable) {
            meta.set("__index", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val key = args.arg(2).tojstring()
                    val e = self.rawget("__pxrp_object").checkuserdata() as Entity
                    val liv = e as? LivingEntity

                    return when (key) {
                        "uuid" -> LuaValue.valueOf(e.uuid.toString())
                        "type" -> LuaValue.valueOf(Registries.ENTITY_TYPE.getId(e.type).toString())
                        "name" -> LuaValue.valueOf(e.name.literalString ?: e.name.string)
                        "displayName" -> LuaValue.valueOf(e.displayName?.string ?: e.name.literalString!!)
                        "customName" -> {
                            val cn = e.customName
                            if (cn != null) LuaValue.valueOf(cn.string) else LuaValue.NIL
                        }
                        "world" -> WorldWrapper(e.entityWorld as ServerWorld).toLuaValue()
                        "pos" -> {
                            val cached = self.rawget("pos")
                            if (!cached.isnil()) return cached
                            val proxy = livePosTable(e)
                            self.rawset("pos", proxy)
                            proxy
                        }
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

                        "tags" -> {
                            val cached = self.rawget("tags")
                            if (!cached.isnil()) return cached
                            val proxy = tagsTable(e)
                            self.rawset("tags", proxy)
                            proxy
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
                    val e = self.rawget("__pxrp_object").checkuserdata() as Entity
                    val liv = e as? LivingEntity

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

            meta.set("__pairs", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1)
                    val keys = entityKeys
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

            meta.rawset("readNbt", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val e = self.rawget("__pxrp_object").checkuserdata() as Entity
                    val writeView = NbtWriteView.create(ErrorReporter.EMPTY)
                    e.saveData(writeView)
                    return nbtToLua(writeView.getNbt())
                }
            })

            meta.rawset("writeNbt", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val nbtTable = args.arg(2)
                    if (!nbtTable.istable()) throw LuaError("writeNbt: ожидается таблица")
                    val compound = luaToNbt(nbtTable) as NbtCompound
                    val e = self.rawget("__pxrp_object").checkuserdata() as Entity
                    val world = e.entityWorld as ServerWorld
                    val readView = NbtReadView.create(ErrorReporter.EMPTY, world.registryManager, compound)
                    e.readData(readView)
                    return LuaValue.NIL
                }
            })

            meta.rawset("damage", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val e = self.rawget("__pxrp_object").checkuserdata() as Entity
                    val amount = args.arg(2).checkdouble().toFloat()
                    val world = e.entityWorld as ServerWorld
                    val sourceEntity = if (args.narg() >= 3 && args.arg(3).istable()) {
                        val uuid = args.arg(3).checktable().get("uuid")
                        if (uuid.isstring()) world.getEntity(UUID.fromString(uuid.tojstring())) else null
                    } else null
                    val damageSource = when (sourceEntity) {
                        is PlayerEntity -> world.damageSources.playerAttack(sourceEntity)
                        is LivingEntity -> world.damageSources.mobAttack(sourceEntity)
                        else -> world.damageSources.generic()
                    }
                    e.damage(world, damageSource, amount)
                    return LuaValue.NIL
                }
            })

            // .raycast(self, range, includeFluids=false, includeEntities=true)
            meta.rawset("raycast", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val e = self.rawget("__pxrp_object").checkuserdata() as Entity
                    val range = args.arg(2).checkdouble()
                    val includeFluids = args.arg(3).optboolean(false)
                    val includeEntities = args.arg(4).optboolean(true)

                    val start = e.eyePos
                    val dir = e.rotationVector.normalize()
                    val end = Vec3d(start.x + dir.x * range, start.y + dir.y * range, start.z + dir.z * range)

                    return performRaycast(start, end, range, includeFluids, includeEntities, e.entityWorld, e)
                }
            })

            meta.rawset("addEffect", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val e = self.rawget("__pxrp_object").checkuserdata() as Entity
                    val liv = e as? LivingEntity ?: return LuaValue.NIL
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
            })

            meta.rawset("removeEffect", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val e = self.rawget("__pxrp_object").checkuserdata() as Entity
                    val liv = e as? LivingEntity ?: return LuaValue.NIL
                    val effectId = args.arg(2).checkjstring()
                    val effect = Registries.STATUS_EFFECT.getEntry(Identifier.of(effectId))
                        .orElseThrow { LuaError("Эффект '$effectId' не найден") }
                    return LuaValue.valueOf(liv.removeStatusEffect(effect))
                }
            })

            meta.rawset("hasEffect", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val e = self.rawget("__pxrp_object").checkuserdata() as Entity
                    val liv = e as? LivingEntity ?: return LuaValue.FALSE
                    val effectId = args.arg(2).checkjstring()
                    val effect = Registries.STATUS_EFFECT.getEntry(Identifier.of(effectId))
                        .orElseThrow { LuaError("Эффект '$effectId' не найден") }
                    return LuaValue.valueOf(liv.hasStatusEffect(effect))
                }
            })

            meta.rawset("setOnFireFor", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val e = self.rawget("__pxrp_object").checkuserdata() as Entity
                    val ticks = args.arg(2).checkint()
                    e.fireTicks = ticks
                    e.setOnFire(true)
                    return LuaValue.NIL
                }
            })
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
    }
}
