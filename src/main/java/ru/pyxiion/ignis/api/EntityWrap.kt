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
import ru.pyxiion.ignis.luaToNbt
import ru.pyxiion.ignis.nbtToLua
import ru.pyxiion.ignis.toVec3d
import ru.pyxiion.ignis.unwrap
import java.util.UUID

object EntityWrap {

    fun wrap(entity: Entity): LuaValue {
        val t = LuaTable()
        t.setmetatable(MetaTableRegistry.ENTITY)
        t.rawset("__pxrp_type", LuaValue.valueOf("entity"))
        t.rawset("__pxrp_object", LuaValue.userdataOf(entity))
        return t
    }

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

    internal val entityKeys: List<String> get() = BUILT.keys.map { it.tojstring() }

    private val BUILT = metaTable<Entity> {
        prop("uuid") { LuaValue.valueOf(uuid.toString()) }
        prop("type") { LuaValue.valueOf(Registries.ENTITY_TYPE.getId(type).toString()) }
        prop("name") { LuaValue.valueOf(name.literalString ?: name.string) }
        prop("displayName") { LuaValue.valueOf(displayName?.string ?: name.literalString!!) }
        prop("customName",
            get = {
                val cn = customName
                if (cn != null) LuaValue.valueOf(cn.string) else LuaValue.NIL
            },
            set = { v -> customName = if (v.isnil()) null else Text.literal(v.tojstring()) }
        )
        prop("world") { WorldWrap.wrap(entityWorld as ServerWorld) }

        lazy("pos",
            factory = { e -> livePosTable(e) },
            set = { v ->
                val vec = v.toVec3d()
                setPosition(vec.x, vec.y, vec.z)
            }
        )

        prop("dir") { Vector.fromMc(rotationVector).toLuaValue() }
        prop("bodyDir") { Vector.fromRotation(bodyYaw, 0.0f).toLuaValue() }

        prop("fallDistance",
            get = { LuaValue.valueOf(fallDistance.toDouble()) },
            set = { v -> fallDistance = v.todouble() }
        )
        prop("fireTicks",
            get = { LuaValue.valueOf(fireTicks) },
            set = { v -> fireTicks = v.toint() }
        )
        prop("glowing",
            get = { if (isGlowing) LuaValue.TRUE else LuaValue.FALSE },
            set = { v -> isGlowing = v.toboolean() }
        )
        prop("invulnerable",
            get = { if (isInvulnerable) LuaValue.TRUE else LuaValue.FALSE },
            set = { v -> isInvulnerable = v.toboolean() }
        )
        prop("isSneaking",
            get = { if (isSneaking) LuaValue.TRUE else LuaValue.FALSE },
            set = { v -> isSneaking = v.toboolean() }
        )
        prop("isSprinting",
            get = { if (isSprinting) LuaValue.TRUE else LuaValue.FALSE },
            set = { v -> isSprinting = v.toboolean() }
        )
        prop("air",
            get = { LuaValue.valueOf(air) },
            set = { v -> air = v.toint() }
        )
        prop("maxAir") { LuaValue.valueOf(maxAir) }
        prop("removed") { if (isRemoved) LuaValue.TRUE else LuaValue.FALSE }

        prop("health",
            get = {
                val liv = this as? LivingEntity
                if (liv != null) LuaValue.valueOf(liv.health.toDouble()) else LuaValue.NIL
            },
            set = { v -> (this as? LivingEntity)?.health = v.tofloat() }
        )
        prop("maxHealth",
            get = {
                val v = (this as? LivingEntity)?.getAttributeInstance(EntityAttributes.MAX_HEALTH)?.value
                if (v != null) LuaValue.valueOf(v) else LuaValue.NIL
            },
            set = { v ->
                (this as? LivingEntity)?.let { liv ->
                    liv.getAttributeInstance(EntityAttributes.MAX_HEALTH)?.let { attr ->
                        attr.baseValue = v.todouble()
                        liv.health = Math.min(liv.health, attr.value.toFloat())
                    }
                }
            }
        )

        map(attributeAccessors,
            getter = { e, attr ->
                val v = (e as? LivingEntity)?.getAttributeInstance(attr)?.value
                if (v != null) LuaValue.valueOf(v) else LuaValue.NIL
            },
            setter = { e, attr, v ->
                (e as? LivingEntity)?.getAttributeInstance(attr)?.baseValue = v.todouble()
            }
        )

        map(equipmentSlots,
            getter = { e, slot ->
                val stack = (e as? LivingEntity)?.getEquippedStack(slot)
                if (stack != null && !stack.isEmpty) ItemStackWrapper.wrap(stack) else LuaValue.NIL
            },
            setter = { e, slot, v ->
                val stack = if (v.isnil()) ItemStack.EMPTY
                            else ItemStackWrapper.unwrap(v)
                                ?: throw LuaError("setItem: ожидается ItemStack от mc.createItem")
                (e as? LivingEntity)?.equipStack(slot, stack)
            }
        )

        lazy("tags", factory = { e -> tagsTable(e) })

        method("readNbt") { args ->
            val self = args.arg(1).checktable()
            val e = self.unwrap<Entity>()
            val writeView = NbtWriteView.create(ErrorReporter.EMPTY)
            e.saveData(writeView)
            nbtToLua(writeView.getNbt())
        }

        method("writeNbt") { args ->
            val self = args.arg(1).checktable()
            val nbtTable = args.arg(2)
            if (!nbtTable.istable()) throw LuaError("writeNbt: ожидается таблица")
            val compound = luaToNbt(nbtTable) as NbtCompound
            val e = self.unwrap<Entity>()
            val world = e.entityWorld as ServerWorld
            val readView = NbtReadView.create(ErrorReporter.EMPTY, world.registryManager, compound)
            e.readData(readView)
            LuaValue.NIL
        }

        method("damage") { args ->
            val self = args.arg(1).checktable()
            val e = self.unwrap<Entity>()
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
            LuaValue.NIL
        }

        method("raycast") { args ->
            val self = args.arg(1).checktable()
            val e = self.unwrap<Entity>()
            val range = args.arg(2).checkdouble()
            val includeFluids = args.arg(3).optboolean(false)
            val includeEntities = args.arg(4).optboolean(true)

            val start = e.eyePos
            val dir = e.rotationVector.normalize()
            val end = Vec3d(start.x + dir.x * range, start.y + dir.y * range, start.z + dir.z * range)

            performRaycast(start, end, range, includeFluids, includeEntities, e.entityWorld, e)
        }

        method("addEffect") { args ->
            val self = args.arg(1).checktable()
            val e = self.unwrap<Entity>()
            val liv = e as? LivingEntity ?: return@method LuaValue.NIL
            val effectId = args.arg(2).checkjstring()
            val duration = args.arg(3).checkint()
            val amplifier = args.arg(4).optint(0)
            val particles = args.arg(5).optboolean(true)
            val icon = args.arg(6).optboolean(true)

            val effect = Registries.STATUS_EFFECT.getEntry(Identifier.of(effectId))
                .orElseThrow { LuaError("Эффект '$effectId' не найден") }
            val instance = StatusEffectInstance(effect, duration, amplifier, false, particles, icon)
            LuaValue.valueOf(liv.addStatusEffect(instance))
        }

        method("removeEffect") { args ->
            val self = args.arg(1).checktable()
            val e = self.unwrap<Entity>()
            val liv = e as? LivingEntity ?: return@method LuaValue.NIL
            val effectId = args.arg(2).checkjstring()
            val effect = Registries.STATUS_EFFECT.getEntry(Identifier.of(effectId))
                .orElseThrow { LuaError("Эффект '$effectId' не найден") }
            LuaValue.valueOf(liv.removeStatusEffect(effect))
        }

        method("hasEffect") { args ->
            val self = args.arg(1).checktable()
            val e = self.unwrap<Entity>()
            val liv = e as? LivingEntity ?: return@method LuaValue.FALSE
            val effectId = args.arg(2).checkjstring()
            val effect = Registries.STATUS_EFFECT.getEntry(Identifier.of(effectId))
                .orElseThrow { LuaError("Эффект '$effectId' не найден") }
            LuaValue.valueOf(liv.hasStatusEffect(effect))
        }

        method("setOnFireFor") { args ->
            val self = args.arg(1).checktable()
            val e = self.unwrap<Entity>()
            val ticks = args.arg(2).checkint()
            e.fireTicks = ticks
            e.setOnFire(true)
            LuaValue.NIL
        }
    }

    fun initMeta(meta: LuaTable) {
        BUILT.apply(meta)
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
