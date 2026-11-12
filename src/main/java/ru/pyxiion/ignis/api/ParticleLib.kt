package ru.pyxiion.ignis.api

import net.minecraft.core.RegistryAccess
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.core.particles.ParticleType
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaFunction
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import ru.pyxiion.ignis.api.Vector.Companion.toVec3d
import ru.pyxiion.ignis.asObject
import ru.pyxiion.ignis.asSequence
import ru.pyxiion.ignis.luaFunction
import ru.pyxiion.ignis.luaTableOf
import ru.pyxiion.ignis.luaVarFunction
import ru.pyxiion.ignis.unwrapOrNull

/**
 * `particle(id, data?)` builds an immutable [Handle] once; `world:emit` / `world:emitMany` send it.
 * The handle holds no Lua state, so it is safe to keep across script reloads.
 */
object ParticleLib {
    class Handle(val id: String, val options: ParticleOptions)

    private val META by lazy {
        luaTableOf().also { mt ->
            mt.set("__index", luaFunction { self, key ->
                val h = self.asObject<Handle>() ?: throw LuaError("expected particle, got ${self.typename()}")
                if (key.optjstring(null) == "id") LuaValue.valueOf(h.id) else LuaValue.NIL
            })
            mt.set("__newindex", luaFunction { _, _, _ -> throw LuaError("Particle is immutable") })
            mt.set("__tostring", luaFunction { self ->
                val h = self.asObject<Handle>() ?: throw LuaError("expected particle, got ${self.typename()}")
                LuaValue.valueOf("particle(${h.id})")
            })
        }
    }

    fun constructor(registries: () -> RegistryAccess): LuaFunction = luaVarFunction { args ->
        val id = args.arg(1).checkjstring()
        val data = args.arg(2).let { if (it.isnil()) null else it.checktable() }
        val resolvedId = if (id.contains(':')) id else "minecraft:$id"
        val key = ResourceKey.create(Registries.PARTICLE_TYPE, Identifier.parse(resolvedId))
        val type = BuiltInRegistries.PARTICLE_TYPE.getValue(key)
            ?: throw LuaError("Частица '$id' не найдена")
        val options = buildParticleEffect(type, data, registries(), resolvedId)
        LuaValue.userdataOf(Handle(resolvedId, options), META)
    }

    private class Opts(
        val count: Int,
        val dx: Double,
        val dy: Double,
        val dz: Double,
        val speed: Double,
        val alwaysShow: Boolean,
        val viewers: List<ServerPlayer>?,
    )

    private val OPT_KEYS = setOf("count", "spread", "speed", "velocity", "alwaysShow", "viewers")

    private fun particleArg(args: Varargs): Handle =
        args.arg(2).asObject<Handle>()
            ?: throw LuaError("emit: expected a particle (create one with particle(id, data)), got ${args.arg(2).typename()}")

    private fun parseOpts(v: LuaValue): Opts {
        if (v.isnil()) return Opts(1, 0.0, 0.0, 0.0, 0.0, false, null)
        val t = v.checktable()
        for ((k, _) in t.asSequence()) {
            val name = if (k.type() == LuaValue.TSTRING) k.tojstring() else null
            if (name == null || name !in OPT_KEYS) throw LuaError("emit: unknown option '${k.tojstring()}'")
        }

        val alwaysShow = t.get("alwaysShow").let { if (it.isnil()) false else it.checkboolean() }
        val viewers = parseViewers(t.get("viewers"))

        val velocity = t.get("velocity")
        if (!velocity.isnil()) {
            if (!t.get("count").isnil() || !t.get("spread").isnil())
                throw LuaError("emit: 'velocity' cannot be combined with 'count' or 'spread'")
            val d = velocity.toVec3d()
            return Opts(0, d.x, d.y, d.z, t.get("speed").optdouble(1.0), alwaysShow, viewers)
        }

        val count = t.get("count").optint(1)
        if (count < 1) throw LuaError("emit: count must be >= 1")
        val spread = t.get("spread").let { if (it.isnil()) null else resolveOperand(it) }
        return Opts(
            count,
            spread?.x ?: 0.0, spread?.y ?: 0.0, spread?.z ?: 0.0,
            t.get("speed").optdouble(0.0),
            alwaysShow, viewers,
        )
    }

    private fun parseViewers(v: LuaValue): List<ServerPlayer>? {
        if (v.isnil()) return null
        v.unwrapOrNull<ServerPlayer>()?.let { return listOf(it) }
        val bad = { LuaError("emit: viewers must be a Player or a list of Players") }
        if (!v.istable()) throw bad()
        val t = v.checktable()
        return (1..t.length()).map { i -> t.get(i).unwrapOrNull<ServerPlayer>() ?: throw bad() }
    }

    private fun send(world: ServerLevel, particle: Handle, x: Double, y: Double, z: Double, o: Opts) {
        if (o.viewers == null) {
            world.sendParticles(particle.options, true, o.alwaysShow, x, y, z, o.count, o.dx, o.dy, o.dz, o.speed)
            return
        }
        for (p in o.viewers) {
            if (p.level() !== world) continue
            world.sendParticles(p, particle.options, true, o.alwaysShow, x, y, z, o.count, o.dx, o.dy, o.dz, o.speed)
        }
    }

    /** `world:emit(particle, pos, opts?)`: args are `(self, particle, pos, opts?)`. */
    fun emit(world: ServerLevel, args: Varargs) {
        val particle = particleArg(args)
        val pos = args.arg(3).toVec3d()
        val opts = parseOpts(args.arg(4))
        send(world, particle, pos.x, pos.y, pos.z, opts)
    }

    /** `world:emitMany(particle, positions, opts?)`: args are `(self, particle, positions, opts?)`. */
    fun emitMany(world: ServerLevel, args: Varargs) {
        val particle = particleArg(args)
        val list = args.arg(3).checktable()
        val positions = (1..list.length()).map { list.get(it).toVec3d() }
        val opts = parseOpts(args.arg(4))
        for (pos in positions) send(world, particle, pos.x, pos.y, pos.z, opts)
    }

    private fun resolveBlockId(id: String): String {
        return if (id.contains(':')) id else "minecraft:$id"
    }

    @Suppress("UNCHECKED_CAST")
    private fun buildParticleEffect(
        type: ParticleType<*>,
        data: LuaTable?,
        registries: RegistryAccess,
        id: String
    ): ParticleOptions {
        if (type is ParticleOptions) return type

        val nbt = buildParticleDataNbt(type, data, id)
        val ops = registries.createSerializationContext(NbtOps.INSTANCE)
        return (type as ParticleType<ParticleOptions>).codec().codec()
            .parse(ops, nbt)
            .getOrThrow { msg: String -> LuaError("Частица '$id': $msg") }
    }

    private fun buildParticleDataNbt(
        type: ParticleType<*>,
        data: LuaTable?,
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
                val name = BuiltInRegistries.PARTICLE_TYPE.getKey(type)?.toString() ?: id
                throw LuaError("Частица '$name' не поддерживается в data")
            }
        }
        return c
    }
}
