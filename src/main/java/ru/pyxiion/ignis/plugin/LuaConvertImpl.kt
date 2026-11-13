package ru.pyxiion.ignis.plugin

import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3
import ru.pyxiion.ignis.api.Vector
import ru.pyxiion.ignis.api.Vector.Companion.toVec3d
import ru.pyxiion.ignis.api.wrapper.EntityFactory
import ru.pyxiion.ignis.api.wrapper.ItemStackWrap
import ru.pyxiion.ignis.api.wrapper.PlayerWrap
import ru.pyxiion.ignis.api.wrapper.WorldWrap
import ru.pyxiion.ignis.unwrapOrNull
import ru.pyxiion.luanova.vm2.LuaError
import ru.pyxiion.luanova.vm2.LuaFunction
import ru.pyxiion.luanova.vm2.LuaTable
import ru.pyxiion.luanova.vm2.LuaValue

internal fun badArgument(arg: String, expected: String, got: LuaValue): LuaError =
    LuaError("bad argument '$arg': $expected expected, got ${got.typename()}")

internal object LuaConvertImpl : LuaConvert {
    override fun toPlayer(v: LuaValue, arg: String): ServerPlayer = v.unwrapOrNull<ServerPlayer>() ?: throw badArgument(arg, "player", v)
    override fun toEntity(v: LuaValue, arg: String): Entity = v.unwrapOrNull<Entity>() ?: throw badArgument(arg, "entity", v)
    override fun toLevel(v: LuaValue, arg: String): ServerLevel = v.unwrapOrNull<ServerLevel>() ?: throw badArgument(arg, "world", v)

    override fun toVec(v: LuaValue, arg: String): Vec3 {
        if (v !is Vector && !v.istable()) throw badArgument(arg, "vec", v)
        return try {
            v.toVec3d()
        } catch (e: LuaError) {
            throw LuaError("bad argument '$arg': vec expected ({x=, y=, z=} or {x, y, z}), got an incomplete table")
        }
    }

    override fun toItem(v: LuaValue, arg: String): ItemStack = ItemStackWrap.unwrap(v) ?: throw badArgument(arg, "item", v)

    override fun wrap(player: ServerPlayer): LuaValue = PlayerWrap.wrap(player)
    override fun wrap(entity: Entity): LuaValue = EntityFactory.wrap(entity)
    override fun wrap(level: ServerLevel): LuaValue = WorldWrap.wrap(level)
    override fun wrap(vec: Vec3): LuaValue = Vector.fromMc(vec).toLuaValue()
    override fun wrap(stack: ItemStack): LuaValue = ItemStackWrap.wrap(stack)

    override fun options(v: LuaValue, fn: String): Opts {
        if (!v.isnil() && !v.istable()) throw LuaError("$fn: options must be a table, got ${v.typename()}")
        return OptsImpl(if (v.isnil()) LuaTable() else v.checktable(), fn)
    }
}

private class OptsImpl(private val t: LuaTable, private val fn: String) : Opts {
    private fun raw(key: String): LuaValue = t.get(key)

    private fun typed(key: String, expected: String, ok: (LuaValue) -> Boolean): LuaValue? {
        val v = raw(key)
        if (v.isnil()) return null
        if (!ok(v)) throw LuaError("$fn: option '$key' must be $expected, got ${v.typename()}")
        return v
    }

    override fun has(key: String) = !raw(key).isnil()
    override fun double(key: String, default: Double) = typed(key, "a number") { it.isnumber() && it.type() == LuaValue.TNUMBER }?.todouble() ?: default
    override fun float(key: String, default: Float) = double(key, default.toDouble()).toFloat()
    override fun int(key: String, default: Int) = typed(key, "a number") { it.type() == LuaValue.TNUMBER }?.toint() ?: default
    override fun boolean(key: String, default: Boolean) = typed(key, "a boolean") { it.isboolean() }?.toboolean() ?: default
    override fun string(key: String, default: String) = stringOrNull(key) ?: default
    override fun stringOrNull(key: String) = typed(key, "a string") { it.type() == LuaValue.TSTRING }?.tojstring()
    override fun entity(key: String): Entity? = convert(key, "an entity") { it.unwrapOrNull<Entity>() }
    override fun player(key: String): ServerPlayer? = convert(key, "a player") { it.unwrapOrNull<ServerPlayer>() }
    override fun level(key: String): ServerLevel? = convert(key, "a world") { it.unwrapOrNull<ServerLevel>() }
    override fun vec(key: String): Vec3? = convert(key, "a vec") { v ->
        if (v is Vector || v.istable()) try { v.toVec3d() } catch (_: LuaError) { null } else null
    }
    override fun function(key: String): LuaFunction? = typed(key, "a function") { it.isfunction() } as LuaFunction?

    private fun <T : Any> convert(key: String, expected: String, f: (LuaValue) -> T?): T? {
        val v = raw(key)
        if (v.isnil()) return null
        return f(v) ?: throw LuaError("$fn: option '$key' must be $expected, got ${v.typename()}")
    }

    override fun list(key: String): List<LuaValue> {
        val v = typed(key, "a list", { it.istable() }) ?: return emptyList()
        val list = v.checktable()
        return (1..list.length()).map { list.get(it) }
    }

    override fun rejectUnknown(vararg known: String) {
        var k: LuaValue = LuaValue.NIL
        while (true) {
            k = t.next(k).arg1()
            if (k.isnil()) break
            if (k.tojstring() !in known) {
                throw LuaError("$fn: unknown option '${k.tojstring()}' (known: ${known.joinToString()})")
            }
        }
    }
}
