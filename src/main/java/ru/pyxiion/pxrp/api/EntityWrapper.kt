package ru.pyxiion.pxrp.api

import net.minecraft.entity.Entity
import net.minecraft.entity.EquipmentSlot
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.attribute.EntityAttributes
import net.minecraft.item.ItemStack
import net.minecraft.registry.Registries
import net.minecraft.server.world.ServerWorld
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.lib.jse.CoerceJavaToLua
import ru.pyxiion.pxrp.toBlockPos
import ru.pyxiion.pxrp.toVec3d

class EntityWrapper(private val entity: Entity) {
    private val living: LivingEntity? = entity as? LivingEntity

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
                    "pos" -> CoerceJavaToLua.coerce(Vector.fromMc(e.entityPos))
                    "dir" -> CoerceJavaToLua.coerce(Vector.fromMc(e.rotationVector))
                    "bodyDir" -> CoerceJavaToLua.coerce(Vector.fromRotation(e.bodyYaw, 0.0f))
                    "fallDistance" -> LuaValue.valueOf(e.fallDistance.toDouble())
                    "fireTicks" -> LuaValue.valueOf(e.fireTicks)
                    "glowing" -> LuaValue.valueOf(e.isGlowing)
                    "invulnerable" -> LuaValue.valueOf(e.isInvulnerable)
                    "isSneaking" -> LuaValue.valueOf(e.isSneaking)
                    "isSprinting" -> LuaValue.valueOf(e.isSprinting)
                    "air" -> LuaValue.valueOf(e.air)
                    "maxAir" -> LuaValue.valueOf(e.maxAir)

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

                    else -> LuaValue.NIL
                }
            }
        })

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
        return t
    }

    private fun tagsTable(entity: Entity): LuaValue {
        val meta = LuaTable()
        meta.set("__index", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val tag = args.arg(2).tojstring()
                return LuaValue.valueOf(entity.getCommandTags().contains(tag))
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
                val tags = entity.getCommandTags().toList()
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
}
