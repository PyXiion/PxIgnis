package ru.pyxiion.ignis.api

import com.mojang.brigadier.exceptions.CommandSyntaxException
import net.minecraft.commands.arguments.blocks.BlockStateParser
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Display
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.Interaction
import net.minecraft.world.entity.Entity
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.phys.Vec3
import net.minecraft.util.Brightness
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.pyxiion.ignis.Compat
import ru.pyxiion.ignis.unwrapOrNull
import ru.pyxiion.ignis.api.manager.DisplayHandle
import ru.pyxiion.ignis.api.manager.DisplayManager
import ru.pyxiion.ignis.api.manager.TransformSpec
import ru.pyxiion.ignis.api.util.ItemBuilder
import ru.pyxiion.ignis.api.util.Text
import ru.pyxiion.ignis.api.wrapper.ItemStackWrap
import ru.pyxiion.luanova.vm2.LuaError
import ru.pyxiion.luanova.vm2.LuaTable
import ru.pyxiion.luanova.vm2.LuaValue

/**
 * `world:display(kind, pos, props)` and `handle:set(props)`: one flat property dictionary for both. Values are
 * written through the vanilla setters of the handle's private entity object; the manager flushes the resulting diff
 * once per tick (see [DisplayHandle]).
 */
object DisplayLib {
    val KINDS = listOf("item", "block", "text", "interaction")

    private val DISPLAY_COMMON = setOf(
        "transform", "interp", "teleport", "billboard", "brightness", "glow", "viewRange", "shadow", "size",
    )
    private val GENERIC = setOf("viewers", "dieWithHost")
    private val ALLOWED: Map<String, Set<String>> = mapOf(
        "item" to DISPLAY_COMMON + GENERIC + setOf("item", "mode"),
        "block" to DISPLAY_COMMON + GENERIC + setOf("block"),
        "text" to DISPLAY_COMMON + GENERIC + setOf("text", "background", "opacity", "lineWidth", "align", "textShadow", "seeThrough"),
        "interaction" to GENERIC + setOf("hitbox"),
    )

    fun spawn(world: ServerLevel, kind: String, pos: Vec3, props: LuaTable?): DisplayHandle {
        val type: EntityType<*> = when (kind) {
            "item" -> Compat.itemDisplayType()
            "block" -> Compat.blockDisplayType()
            "text" -> Compat.textDisplayType()
            "interaction" -> Compat.interactionType()
            else -> throw LuaError("display: unknown kind '$kind' (known: ${KINDS.joinToString()})")
        }
        val entity: Entity = type.create(world, EntitySpawnReason.COMMAND)
            ?: throw LuaError("display: cannot create '$kind'")
        val handle = DisplayHandle(kind, world, entity, pos)
        if (props != null) apply(handle, props)
        DisplayManager.register(handle)
        return handle
    }

    fun apply(h: DisplayHandle, props: LuaTable) {
        val allowed = ALLOWED.getValue(h.kind)
        var viewers: LuaValue = LuaValue.NIL
        for (key in props.keys()) {
            val name = key.checkjstring()
            if (name !in allowed) {
                throw LuaError("display '${h.kind}': unknown property '$name' (known: ${allowed.sorted().joinToString()})")
            }
            val v = props.get(key)
            if (name == "viewers") viewers = v else applyOne(h, name, v)
        }
        if (!viewers.isnil()) applyViewers(h, viewers)
    }

    private fun applyViewers(h: DisplayHandle, v: LuaValue) {
        when {
            v.type() == LuaValue.TSTRING && v.tojstring() == "auto" -> {
                h.mode = DisplayHandle.Mode.AUTO
                h.needsViewerCheck = true
            }
            v.type() == LuaValue.TSTRING && v.tojstring() == "manual" -> h.mode = DisplayHandle.Mode.MANUAL
            v.istable() -> {
                h.mode = DisplayHandle.Mode.MANUAL
                val wanted = ArrayList<net.minecraft.server.level.ServerPlayer>()
                val t = v.checktable()
                var i = 1
                while (!t.rawget(i).isnil()) {
                    wanted.add(t.rawget(i).let { p ->
                        p.unwrapOrNull<net.minecraft.server.level.ServerPlayer>()
                            ?: throw LuaError("display viewers: element $i is not a player")
                    })
                    i++
                }
                h.hideExcept(wanted)
                wanted.forEach { h.show(it) }
            }
            else -> throw LuaError("display viewers: expected \"auto\", \"manual\" or a list of players")
        }
    }

    private fun applyOne(h: DisplayHandle, name: String, v: LuaValue) {
        val e = h.entity
        val d = e as? Display
        when (name) {
            "transform" -> { h.transform = parseTransform(v); h.applyTransform() }
            "interp" -> d!!.transformationInterpolationDuration = intIn(name, v, 0, Int.MAX_VALUE)
            "teleport" -> d!!.posRotInterpolationDuration = intIn(name, v, 0, 59)
            "billboard" -> d!!.billboardConstraints = parseBillboard(v.checkjstring())
            "brightness" -> d!!.brightnessOverride = parseBrightness(v)
            "glow" -> {
                if (v.isboolean()) {
                    e.setGlowingTag(v.toboolean())
                    d!!.glowColorOverride = -1
                } else {
                    e.setGlowingTag(true)
                    d!!.glowColorOverride = v.checklong().toInt() and 0xFFFFFF
                }
            }
            "viewRange" -> { h.viewRange = v.checkdouble().toFloat(); d!!.viewRange = h.viewRange }
            "shadow" -> {
                if (v.isnumber()) {
                    d!!.shadowRadius = v.tofloat()
                } else {
                    val t = v.checktable()
                    d!!.shadowRadius = num(t, "radius", 1, 0.0).toFloat()
                    d.shadowStrength = num(t, "strength", 2, 1.0).toFloat()
                }
            }
            "size" -> {
                val t = v.checktable()
                d!!.width = num(t, "w", 1, 0.0).toFloat()
                d.height = num(t, "h", 2, 0.0).toFloat()
            }
            "dieWithHost" -> h.dieWithHost = v.toboolean()
            "item" -> (e as Display.ItemDisplay).itemStack =
                ItemStackWrap.unwrap(v) ?: ItemBuilder.fromLua(v)
            "mode" -> {
                val s = v.checkjstring()
                (e as Display.ItemDisplay).setItemTransform(
                    ItemDisplayContext.entries.firstOrNull { it.serializedName == s }
                        ?: throw LuaError("display mode '$s' (known: ${ItemDisplayContext.entries.joinToString { it.serializedName }})")
                )
            }
            "block" -> (e as Display.BlockDisplay).setBlockState(parseBlock(v.checkjstring()))
            "text" -> (e as Display.TextDisplay).text = Text.fromLua(v, "display text")
            "background" -> (e as Display.TextDisplay).backgroundColor = v.checklong().toInt()
            "opacity" -> (e as Display.TextDisplay).textOpacity = intIn(name, v, 0, 255).toByte()
            "lineWidth" -> (e as Display.TextDisplay).lineWidth = intIn(name, v, 1, Int.MAX_VALUE)
            "align" -> setAlign(e as Display.TextDisplay, v.checkjstring())
            "textShadow" -> setFlag(e as Display.TextDisplay, Display.TextDisplay.FLAG_SHADOW, v.toboolean())
            "seeThrough" -> setFlag(e as Display.TextDisplay, Display.TextDisplay.FLAG_SEE_THROUGH, v.toboolean())
            "hitbox" -> {
                val i = e as Interaction
                val t = v.checktable()
                i.width = num(t, "w", 1, 1.0).toFloat()
                i.height = num(t, "h", 2, 1.0).toFloat()
                val r = t.get("responsive")
                i.setResponse(if (r.isnil()) t.get(3).toboolean() else r.toboolean())
            }
        }
    }

    private fun intIn(name: String, v: LuaValue, min: Int, max: Int): Int {
        val n = v.checkint()
        if (n < min || n > max) throw LuaError("display $name: expected $min..$max, got $n")
        return n
    }

    /** Named field, else positional, else [default]. */
    private fun num(t: LuaTable, key: String, index: Int, default: Double): Double {
        val named = t.get(key)
        if (!named.isnil()) return named.checkdouble()
        val pos = t.get(index)
        return if (pos.isnil()) default else pos.checkdouble()
    }

    private fun parseBillboard(s: String): Display.BillboardConstraints = when (s) {
        "fixed" -> Display.BillboardConstraints.FIXED
        "vertical" -> Display.BillboardConstraints.VERTICAL
        "horizontal" -> Display.BillboardConstraints.HORIZONTAL
        "center" -> Display.BillboardConstraints.CENTER
        else -> throw LuaError("display billboard '$s' (known: fixed, vertical, horizontal, center)")
    }

    /** `false` clears the override (world light); a number is both channels; `{block, sky}` or `{block=, sky=}`. */
    private fun parseBrightness(v: LuaValue): Brightness? {
        if (v.isboolean() && !v.toboolean()) return null
        val (b, s) = if (v.isnumber()) v.checkint().let { it to it } else {
            val t = v.checktable()
            num(t, "block", 1, 0.0).toInt() to num(t, "sky", 2, 0.0).toInt()
        }
        if (b !in 0..15 || s !in 0..15) throw LuaError("display brightness: levels are 0..15, got $b/$s")
        return Brightness(b, s)
    }

    private fun parseBlock(s: String) = try {
        BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, s, false).blockState()
    } catch (e: CommandSyntaxException) {
        throw LuaError("display block '$s': ${e.message}")
    }

    private fun setFlag(e: Display.TextDisplay, flag: Byte, on: Boolean) {
        val cur = e.flags.toInt()
        e.flags = (if (on) cur or flag.toInt() else cur and flag.toInt().inv()).toByte()
    }

    private fun setAlign(e: Display.TextDisplay, s: String) {
        val cleared = e.flags.toInt() and Display.TextDisplay.FLAG_ALIGN_LEFT.toInt().inv() and
            Display.TextDisplay.FLAG_ALIGN_RIGHT.toInt().inv()
        e.flags = when (s) {
            "left" -> cleared or Display.TextDisplay.FLAG_ALIGN_LEFT.toInt()
            "right" -> cleared or Display.TextDisplay.FLAG_ALIGN_RIGHT.toInt()
            "center" -> cleared
            else -> throw LuaError("display align '$s' (known: left, center, right)")
        }.toByte()
    }

    // -- transform ---------------------------------------------------------------------------------------------

    private fun vec3(v: LuaValue, what: String): Vector3f {
        val t = v.checktable()
        if (t.get("x").isnil() && t.get(1).isnil()) throw LuaError("display transform.$what: expected {x, y, z}")
        return Vector3f(num(t, "x", 1, 0.0).toFloat(), num(t, "y", 2, 0.0).toFloat(), num(t, "z", 3, 0.0).toFloat())
    }

    private fun quat(v: LuaValue, what: String): Quaternionf {
        val t = v.checktable()
        val named = !t.get("x").isnil()
        fun c(name: String, i: Int) = t.get(if (named) LuaValue.valueOf(name) else LuaValue.valueOf(i)).let {
            if (it.isnil()) throw LuaError("display transform.$what: expected a quaternion {x, y, z, w}") else it.checkdouble().toFloat()
        }
        return Quaternionf(c("x", 1), c("y", 2), c("z", 3), c("w", 4))
    }

    /** `{t=, l=, s=, r=}` (translation, left rotation, scale, right rotation) or 16 numbers, row-major. */
    internal fun parseTransform(v: LuaValue): TransformSpec {
        val t = v.checktable()
        if (!t.get(16).isnil()) {
            val col = FloatArray(16)
            for (r in 0 until 4) for (c in 0 until 4) col[c * 4 + r] = t.get(r * 4 + c + 1).checkdouble().toFloat()
            return TransformSpec(null, null, null, null, Matrix4f().set(col))
        }
        val tr = t.get("t").let { if (it.isnil()) Vector3f() else vec3(it, "t") }
        val l = t.get("l").let { if (it.isnil()) Quaternionf() else quat(it, "l") }
        val s = t.get("s").let {
            when {
                it.isnil() -> Vector3f(1f, 1f, 1f)
                it.isnumber() -> Vector3f(it.tofloat(), it.tofloat(), it.tofloat())
                else -> vec3(it, "s")
            }
        }
        val r = t.get("r").let { if (it.isnil()) Quaternionf() else quat(it, "r") }
        return TransformSpec(tr, l, s, r, null)
    }
}
