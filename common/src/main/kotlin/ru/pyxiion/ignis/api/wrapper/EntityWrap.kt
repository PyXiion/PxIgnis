package ru.pyxiion.ignis.api.wrapper

import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Pose
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.item.ItemStack
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerLevel
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.phys.Vec3
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.VarArgFunction
import ru.pyxiion.ignis.api.MetaTableRegistry
import ru.pyxiion.ignis.api.Vector
import ru.pyxiion.ignis.api.Vector.Companion.toVec3d
import ru.pyxiion.ignis.api.util.metaTable
import ru.pyxiion.ignis.api.util.performRaycast
import ru.pyxiion.ignis.unwrap
import java.util.*

object EntityWrap {
    var sharedPlayerCache: MutableMap<UUID, LuaValue> = mutableMapOf()
    var sharedTickProvider: () -> Long = { 0L }

    fun wrap(entity: Entity): LuaValue {
        val t = LuaTable()
        t.setmetatable(MetaTableRegistry.ENTITY)
        t.rawset("__pxrp_type", LuaValue.valueOf("entity"))
        t.rawset("__pxrp_object", LuaValue.userdataOf(entity))
        return t
    }

    private val attributeAccessors = mapOf(
        "speed" to Attributes.MOVEMENT_SPEED,
        "armor" to Attributes.ARMOR,
        "armorToughness" to Attributes.ARMOR_TOUGHNESS,
        "attackDamage" to Attributes.ATTACK_DAMAGE,
        "attackSpeed" to Attributes.ATTACK_SPEED,
        "knockbackResistance" to Attributes.KNOCKBACK_RESISTANCE,
        "luck" to Attributes.LUCK,
        "stepHeight" to Attributes.STEP_HEIGHT,
        "blockBreakSpeed" to Attributes.BLOCK_BREAK_SPEED,
        "gravity" to Attributes.GRAVITY,
        "scale" to Attributes.SCALE,
        "safeFallDistance" to Attributes.SAFE_FALL_DISTANCE,
        "flyingSpeed" to Attributes.FLYING_SPEED,
    )

    private val equipmentSlots = mapOf(
        "mainhand" to EquipmentSlot.MAINHAND,
        "offhand" to EquipmentSlot.OFFHAND,
        "head" to EquipmentSlot.HEAD,
        "chest" to EquipmentSlot.CHEST,
        "legs" to EquipmentSlot.LEGS,
        "feet" to EquipmentSlot.FEET,
    )

    internal val BUILT = metaTable<Entity> {
        prop("uuid") { LuaValue.valueOf(uuid.toString()) }
        prop("type") { LuaValue.valueOf(BuiltInRegistries.ENTITY_TYPE.getId(type).toString()) }
        prop("name") { LuaValue.valueOf(name.string) }
        prop("displayName") { LuaValue.valueOf(displayName?.string ?: name.string) }
        prop(
            "customName",
            get = {
                val cn = customName
                if (cn != null) LuaValue.valueOf(cn.string) else LuaValue.NIL
            },
            set = { v -> customName = if (v.isnil()) null else Component.literal(v.tojstring()) }
        )
        prop("world") { WorldWrap.wrap(level() as ServerLevel, sharedPlayerCache, sharedTickProvider) }

        prop(
            "pos",
            get = { Vector.fromMc(position()).toLuaValue() },
            set = { v ->
                val vec = v.toVec3d()
                setPos(vec.x, vec.y, vec.z)
            }
        )

        prop("dir") { Vector.fromMc(lookAngle).toLuaValue() }
        prop("bodyDir") {
            val yaw = if (this is LivingEntity) (this as LivingEntity).yBodyRot else yRot
            Vector.fromRotation(yaw, 0.0f).toLuaValue()
        }

        prop(
            "fallDistance",
            get = { LuaValue.valueOf(fallDistance) },
            set = { v -> fallDistance = v.todouble() }
        )
        prop(
            "fireTicks",
            get = { LuaValue.valueOf(remainingFireTicks) },
            set = { v -> remainingFireTicks = v.toint() }
        )
        prop(
            "glowing",
            get = { if (hasGlowingTag()) LuaValue.TRUE else LuaValue.FALSE },
            set = { v -> setGlowingTag(v.toboolean()) }
        )
        prop(
            "invulnerable",
            get = { if (isInvulnerable) LuaValue.TRUE else LuaValue.FALSE },
            set = { v -> isInvulnerable = v.toboolean() }
        )
        prop(
            "isSneaking",
            get = { if (isCrouching) LuaValue.TRUE else LuaValue.FALSE },
            set = { v -> setPose(if (v.toboolean()) Pose.CROUCHING else Pose.STANDING) }
        )
        prop(
            "isSprinting",
            get = { if (isSprinting) LuaValue.TRUE else LuaValue.FALSE },
            set = { v -> setSprinting(v.toboolean()) }
        )
        prop(
            "air",
            get = { LuaValue.valueOf(airSupply) },
            set = { v -> airSupply = v.toint() }
        )
        prop("maxAir") { LuaValue.valueOf(maxAirSupply) }
        prop("removed") { if (isRemoved) LuaValue.TRUE else LuaValue.FALSE }

        prop(
            "health",
            get = {
                val liv = this as? LivingEntity
                if (liv != null) LuaValue.valueOf(liv.health.toDouble()) else LuaValue.NIL
            },
            set = { v -> (this as? LivingEntity)?.health = v.tofloat() }
        )
        prop(
            "maxHealth",
            get = {
                val v = (this as? LivingEntity)?.getAttribute(Attributes.MAX_HEALTH)?.value
                if (v != null) LuaValue.valueOf(v) else LuaValue.NIL
            },
            set = { v ->
                (this as? LivingEntity)?.let { liv ->
                    liv.getAttribute(Attributes.MAX_HEALTH)?.let { attr ->
                        attr.baseValue = v.todouble()
                        liv.health = Math.min(liv.health, attr.value.toFloat())
                    }
                }
            }
        )

        map(
            attributeAccessors,
            getter = { e, attr ->
                val v = (e as? LivingEntity)?.getAttribute(attr)?.value
                if (v != null) LuaValue.valueOf(v) else LuaValue.NIL
            },
            setter = { e, attr, v ->
                (e as? LivingEntity)?.getAttribute(attr)?.baseValue = v.todouble()
            }
        )

        map(
            equipmentSlots,
            getter = { e, slot ->
                val stack = (e as? LivingEntity)?.getItemBySlot(slot)
                if (stack != null && !stack.isEmpty) ItemStackWrap.wrap(stack) else LuaValue.NIL
            },
            setter = { e, slot, v ->
                val stack = if (v.isnil()) ItemStack.EMPTY
                else ItemStackWrap.unwrap(v)
                    ?: throw LuaError("setItem: ожидается ItemStack от mc.createItem")
                (e as? LivingEntity)?.setItemSlot(slot, stack)
            }
        )

        lazy("tags", factory = { tagsTable(this) })

        // Deferred, because Lua->NBT conversion is under question
        // Lua has only two types right now: int & double, but NBT has
        // Byte, Short, Int, Long, etc.
        // I'll need a wrapper I guess? And a whole NbtConversionFactory or similar
        // LuaNbtValue: LuaNbtByte, LuaNbtShort, ..., LuaNbtCompound, LuaNbtList
        // And map everything with metatables.
        // That's too much work for something I don't use right now.
        // Though I could just stick to smth like
        // local nbt = entity:readNbt()
        // nbt:setByte("path.to.byte", 5)
        // But it's not in PxIgnis way, API must be simple
        // TODO

//        method("readNbt") { args ->
//            val self = args.arg(1).checktable()
//            val e = self.unwrap<Entity>()
//            val writeView = NbtWriteView.create(ErrorReporter.EMPTY)
//            e.saveData(writeView)
//            nbtToLua(writeView.getNbt())
//        }
//
//        method("writeNbt") { args ->
//            val self = args.arg(1).checktable()
//            val nbtTable = args.arg(2)
//            if (!nbtTable.istable()) throw LuaError("writeNbt: ожидается таблица")
//            val compound = luaToNbt(nbtTable) as CompoundTag
//            val e = self.unwrap<Entity>()
//            val world = e.level as ServerLevel
//            val readView = NbtReadView.create(ErrorReporter.EMPTY, world.registryAccess, compound)
//            e.readData(readView)
//            LuaValue.NIL
//        }

        method("damage") { args ->
            val self = args.arg(1).checktable()
            val e = self.unwrap<Entity>()
            val amount = args.arg(2).checkdouble().toFloat()
            val world = e.level() as ServerLevel
            val sourceEntity = if (args.narg() >= 3 && args.arg(3).istable()) {
                val uuid = args.arg(3).checktable().get("uuid")
                if (uuid.isstring()) world.getEntity(UUID.fromString(uuid.tojstring())) else null
            } else null
            val damageSource = when (sourceEntity) {
                is Player -> world.damageSources().playerAttack(sourceEntity)
                is LivingEntity -> world.damageSources().mobAttack(sourceEntity)
                else -> world.damageSources().generic()
            }
            e.hurt(damageSource, amount)
            LuaValue.NIL
        }

        method("raycast") { args ->
            val self = args.arg(1).checktable()
            val e = self.unwrap<Entity>()
            val range = args.arg(2).checkdouble()
            val includeFluids = args.arg(3).optboolean(false)
            val includeEntities = args.arg(4).optboolean(true)

            val start = e.getEyePosition()
            val dir = e.lookAngle
            val end = Vec3(start.x + dir.x * range, start.y + dir.y * range, start.z + dir.z * range)

            performRaycast(start, end, range, includeFluids, includeEntities, e.level(), e)
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

            val effect = BuiltInRegistries.MOB_EFFECT.get(Identifier.parse(effectId))
                .orElseThrow { LuaError("Эффект '$effectId' не найден") }
            val instance = MobEffectInstance(effect, duration, amplifier, false, particles, icon)
            LuaValue.valueOf(liv.addEffect(instance))
        }

        method("removeEffect") { args ->
            val self = args.arg(1).checktable()
            val e = self.unwrap<Entity>()
            val liv = e as? LivingEntity ?: return@method LuaValue.NIL
            val effectId = args.arg(2).checkjstring()
            val effect = BuiltInRegistries.MOB_EFFECT.get(Identifier.parse(effectId))
                .orElseThrow { LuaError("Эффект '$effectId' не найден") }
            LuaValue.valueOf(liv.removeEffect(effect))
        }

        method("hasEffect") { args ->
            val self = args.arg(1).checktable()
            val e = self.unwrap<Entity>()
            val liv = e as? LivingEntity ?: return@method LuaValue.FALSE
            val effectId = args.arg(2).checkjstring()
            val effect = BuiltInRegistries.MOB_EFFECT.get(Identifier.parse(effectId))
                .orElseThrow { LuaError("Эффект '$effectId' не найден") }
            LuaValue.valueOf(liv.hasEffect(effect))
        }

        method("setOnFireFor") { args ->
            val self = args.arg(1).checktable()
            val e = self.unwrap<Entity>()
            val ticks = args.arg(2).checkint()
            e.remainingFireTicks = ticks
            LuaValue.NIL
        }

        toString { "[Entity $type, $uuid]" }
    }

    fun initMeta(meta: LuaTable) {
        BUILT.apply(meta)
    }

    private fun tagsTable(entity: Entity): LuaValue {
        val meta = LuaTable()
        meta.set("__index", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val tag = args.arg(2).tojstring()
                return valueOf(entity.tags.contains(tag))
            }
        })
        meta.set("__newindex", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val tag = args.arg(2).tojstring()
                val value = args.arg(3)
                if (value.toboolean()) {
                    entity.addTag(tag)
                } else {
                    entity.removeTag(tag)
                }
                return NIL
            }
        })
        meta.set("__pairs", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val tags = entity.tags.toList()
                val iterator = object : VarArgFunction() {
                    private var index = 0
                    override fun invoke(args: Varargs): Varargs {
                        if (index >= tags.size) return NIL
                        val tag = tags[index]
                        index++
                        return varargsOf(arrayOf(valueOf(tag), valueOf(true)))
                    }
                }
                return varargsOf(arrayOf(iterator, NIL, NIL))
            }
        })
        val table = LuaTable()
        table.setmetatable(meta)
        return table
    }
}