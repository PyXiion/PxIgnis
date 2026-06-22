package ru.pyxiion.ignis.api.wrapper

import net.minecraft.entity.decoration.DisplayEntity
import net.minecraft.entity.decoration.DisplayEntity.BillboardMode
import net.minecraft.entity.decoration.DisplayEntity.TextDisplayEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.text.Text
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import ru.pyxiion.ignis.api.MetaTableRegistry
import ru.pyxiion.ignis.api.manager.HologramManager
import ru.pyxiion.ignis.api.util.metaTable
import ru.pyxiion.ignis.toLuaArray
import ru.pyxiion.ignis.unwrap


class HologramWrapper(
    internal val entity: DisplayEntity.TextDisplayEntity,
    @Suppress("unused") private val world: ServerWorld,
) {

    fun toLuaValue(): LuaValue {
        val t = LuaTable()
        t.setmetatable(MetaTableRegistry.HOLOGRAM)
        t.rawset("__pxrp_type", LuaValue.valueOf("hologram"))
        t.rawset("__pxrp_object", LuaValue.userdataOf(entity))
        return t
    }

    internal fun destroyInternals() {
        if (!entity.isRemoved) entity.discard()
    }

    fun destroy() {
        destroyInternals()
        HologramManager.unregister(this)
    }

    companion object {
        private val BUILT by lazy {
            metaTable<TextDisplayEntity> {
                inherit { MetaTableRegistry.ENTITY }

                prop(
                    "text",
                    get = { LuaValue.valueOf(text.string) },
                    set = { v -> text = Text.literal(v.tojstring()) }
                )
                prop(
                    "lines",
                    get = {
                        text.string.split("\n").map(LuaValue::valueOf).toLuaArray()
                    },
                    set = { v ->
                        val ct = v.checktable()
                        val list = mutableListOf<String>()
                        var i = 1
                        while (true) {
                            val el = ct.rawget(i)
                            if (el.isnil()) break
                            if (el.isstring()) list.add(el.tojstring())
                            i++
                        }
                        text = Text.literal(list.joinToString("\n"))
                    }
                )
                prop(
                    "alignment",
                    get = { LuaValue.valueOf(TextDisplayEntity.getAlignment(displayFlags).asString()) },
                    set = { v -> setAlignment(this, parseAlignment(v.tojstring())) }
                )
                prop(
                    "billboard",
                    get = { LuaValue.valueOf(billboardMode.asString()) },
                    set = { v -> billboardMode = parseBillboard(v.tojstring()) }
                )
                prop(
                    "lineWidth",
                    get = { LuaValue.valueOf(lineWidth) },
                    set = { v -> lineWidth = v.checkint() }
                )
                prop(
                    "background",
                    get = { LuaValue.valueOf(background) },
                    set = { v -> background = v.checkint() }
                )
                prop(
                    "opacity",
                    get = { LuaValue.valueOf(textOpacity.toInt()) },
                    set = { v -> textOpacity = v.checkint().toByte() }
                )
                prop(
                    "shadow",
                    get = {
                        LuaValue.valueOf((displayFlags.toInt() and TextDisplayEntity.SHADOW_FLAG.toInt()) != 0)
                    },
                    set = { v -> setFlag(this, TextDisplayEntity.SHADOW_FLAG, v.toboolean()) }
                )
                prop(
                    "seeThrough",
                    get = {
                        LuaValue.valueOf((displayFlags.toInt() and TextDisplayEntity.SEE_THROUGH_FLAG.toInt()) != 0)
                    },
                    set = { v -> setFlag(this, TextDisplayEntity.SEE_THROUGH_FLAG, v.toboolean()) }
                )
                prop(
                    "glowing",
                    get = { LuaValue.valueOf(isGlowing) },
                    set = { v -> isGlowing = v.toboolean() }
                )

                method("setLine") { args ->
                    val self = args.arg(1).checktable()
                    val e = self.unwrap<TextDisplayEntity>()
                    val idx = args.arg(2).checkint()
                    val newText = args.arg(3).checkjstring()
                    if (idx < 1) throw LuaError("Номер строки должен быть >= 1")
                    val lines = e.text.string.split("\n").toMutableList()
                    while (lines.size < idx) lines.add("")
                    lines[idx - 1] = newText
                    e.text = Text.literal(lines.joinToString("\n"))
                    LuaValue.NIL
                }

                method("destroy") { args ->
                    val self = args.arg(1).checktable()
                    val e = self.unwrap<TextDisplayEntity>()
                    val wrapper = HologramManager.get(e.uuid) ?: return@method LuaValue.NIL
                    wrapper.destroy()
                    LuaValue.NIL
                }
            }
        }

        fun initMeta(meta: LuaTable) {
            BUILT.apply(meta)
        }

        private fun parseAlignment(s: String): TextDisplayEntity.TextAlignment = when (s) {
            "left" -> TextDisplayEntity.TextAlignment.LEFT
            "right" -> TextDisplayEntity.TextAlignment.RIGHT
            "center" -> TextDisplayEntity.TextAlignment.CENTER
            else -> throw LuaError("Неизвестное выравнивание '$s' (допустимо: left, center, right)")
        }

        private fun parseBillboard(s: String): BillboardMode = when (s) {
            "fixed" -> BillboardMode.FIXED
            "vertical" -> BillboardMode.VERTICAL
            "horizontal" -> BillboardMode.HORIZONTAL
            "center" -> BillboardMode.CENTER
            else -> throw LuaError("Неизвестный billboard '$s' (допустимо: fixed, vertical, horizontal, center)")
        }

        internal fun parseBillboardFromLua(s: String): BillboardMode = parseBillboard(s)
        internal fun applyAlignmentFromLua(e: DisplayEntity.TextDisplayEntity, s: String) =
            setAlignment(e, parseAlignment(s))

        internal fun applyFlagFromLua(e: DisplayEntity.TextDisplayEntity, flag: Byte, value: Boolean) =
            setFlag(e, flag, value)

        private fun setFlag(e: DisplayEntity.TextDisplayEntity, flag: Byte, value: Boolean) {
            val current = e.displayFlags.toInt()
            val newFlags = if (value) {
                current or flag.toInt()
            } else {
                current and flag.toInt().inv()
            }
            e.displayFlags = newFlags.toByte()
        }

        private fun setAlignment(e: DisplayEntity.TextDisplayEntity, alignment: TextDisplayEntity.TextAlignment) {
            val current = e.displayFlags.toInt()
            val cleared = current and
                    (TextDisplayEntity.LEFT_ALIGNMENT_FLAG.toInt().inv()) and
                    (TextDisplayEntity.RIGHT_ALIGNMENT_FLAG.toInt().inv())
            val updated = when (alignment) {
                TextDisplayEntity.TextAlignment.LEFT ->
                    cleared or TextDisplayEntity.LEFT_ALIGNMENT_FLAG.toInt()

                TextDisplayEntity.TextAlignment.RIGHT ->
                    cleared or TextDisplayEntity.RIGHT_ALIGNMENT_FLAG.toInt()

                TextDisplayEntity.TextAlignment.CENTER -> cleared
            }
            e.displayFlags = updated.toByte()
        }
    }
}

internal object HologramDefaults {
    const val BACKGROUND = 0x40000000
    const val LINE_WIDTH = 200
}
