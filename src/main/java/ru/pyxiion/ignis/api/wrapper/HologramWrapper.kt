package ru.pyxiion.ignis.api.wrapper

import net.minecraft.world.entity.Display
import net.minecraft.world.entity.Display.BillboardConstraints
import net.minecraft.world.entity.Display.TextDisplay
import net.minecraft.server.level.ServerLevel
import net.minecraft.network.chat.Component
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import ru.pyxiion.ignis.api.MetaTableRegistry
import ru.pyxiion.ignis.api.manager.HologramManager
import ru.pyxiion.ignis.api.util.metaTable
import ru.pyxiion.ignis.toLuaArray
import ru.pyxiion.ignis.unwrap


class HologramWrapper(
    internal val entity: Display.TextDisplayEntity,
    @Suppress("unused") private val world: ServerLevel,
) {

    fun toLuaValue(): LuaValue {
        val t = LuaTable()
        t.setmetatable(MetaTableRegistry.HOLOGRAM)
        t.rawset("__pxrp_type", LuaValue.valueOf("hologram"))
        t.rawset("__pxrp_object", LuaValue.userdataOf(entity))
        return t
    }

    internal fun destroyInternals() {
        if (!entity.isRemoved) entity.setRemovalReasonIfNotSet()
    }

    fun destroy() {
        destroyInternals()
        HologramManager.unregister(this)
    }

    companion object {
        private val BUILT by lazy {
            metaTable<TextDisplay> {
                inherit { MetaTableRegistry.ENTITY }

                prop(
                    "text",
                    get = { LuaValue.valueOf(text.string) },
                    set = { v -> text = Component.literal(v.tojstring()) }
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
                        text = Component.literal(list.joinToString("\n"))
                    }
                )
                prop(
                    "alignment",
                    get = { LuaValue.valueOf(TextDisplay.getAlign(flags).asString()) },
                    set = { v -> setAlignment(this, parseAlignment(v.tojstring())) }
                )
                prop(
                    "billboard",
                    get = { LuaValue.valueOf(billboardConstraints.asString()) },
                    set = { v -> billboardConstraints = parseBillboard(v.tojstring()) }
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
                        LuaValue.valueOf((flags.toInt() and TextDisplay.FLAG_SHADOW.toInt()) != 0)
                    },
                    set = { v -> setFlag(this, TextDisplay.FLAG_SHADOW, v.toboolean()) }
                )
                prop(
                    "seeThrough",
                    get = {
                        LuaValue.valueOf((flags.toInt() and TextDisplay.FLAG_SEE_THROUGH.toInt()) != 0)
                    },
                    set = { v -> setFlag(this, TextDisplay.FLAG_SEE_THROUGH, v.toboolean()) }
                )
                prop(
                    "glowing",
                    get = { LuaValue.valueOf(isGlowing) },
                    set = { v -> isGlowing = v.toboolean() }
                )

                method("setLine") { args ->
                    val self = args.arg(1).checktable()
                    val e = self.unwrap<TextDisplay>()
                    val idx = args.arg(2).checkint()
                    val newText = args.arg(3).checkjstring()
                    if (idx < 1) throw LuaError("Номер строки должен быть >= 1")
                    val lines = e.text.string.split("\n").toMutableList()
                    while (lines.size < idx) lines.add("")
                    lines[idx - 1] = newText
                    e.text = Component.literal(lines.joinToString("\n"))
                    LuaValue.NIL
                }

                method("destroy") { args ->
                    val self = args.arg(1).checktable()
                    val e = self.unwrap<TextDisplay>()
                    val wrapper = HologramManager.get(e.uuid) ?: return@method LuaValue.NIL
                    wrapper.destroy()
                    LuaValue.NIL
                }
            }
        }

        fun initMeta(meta: LuaTable) {
            BUILT.apply(meta)
        }

        private fun parseAlignment(s: String): TextDisplay.TextAlignment = when (s) {
            "left" -> TextDisplay.TextAlignment.LEFT
            "right" -> TextDisplay.TextAlignment.RIGHT
            "center" -> TextDisplay.TextAlignment.CENTER
            else -> throw LuaError("Неизвестное выравнивание '$s' (допустимо: left, center, right)")
        }

        private fun parseBillboard(s: String): BillboardConstraints = when (s) {
            "fixed" -> BillboardConstraints.FIXED
            "vertical" -> BillboardConstraints.VERTICAL
            "horizontal" -> BillboardConstraints.HORIZONTAL
            "center" -> BillboardConstraints.CENTER
            else -> throw LuaError("Неизвестный billboard '$s' (допустимо: fixed, vertical, horizontal, center)")
        }

        internal fun parseBillboardFromLua(s: String): BillboardConstraints = parseBillboard(s)
        internal fun applyAlignmentFromLua(e: Display.TextDisplayEntity, s: String) =
            setAlignment(e, parseAlignment(s))

        internal fun applyFlagFromLua(e: Display.TextDisplayEntity, flag: Byte, value: Boolean) =
            setFlag(e, flag, value)

        private fun setFlag(e: Display.TextDisplayEntity, flag: Byte, value: Boolean) {
            val current = e.flags.toInt()
            val newFlags = if (value) {
                current or flag.toInt()
            } else {
                current and flag.toInt().inv()
            }
            e.flags = newFlags.toByte()
        }

        private fun setAlignment(e: Display.TextDisplayEntity, alignment: TextDisplay.TextAlignment) {
            val current = e.flags.toInt()
            val cleared = current and
                    (TextDisplay.FLAG_ALIGN_LEFT.toInt().inv()) and
                    (TextDisplay.FLAG_ALIGN_RIGHT.toInt().inv())
            val updated = when (alignment) {
                TextDisplay.TextAlignment.LEFT ->
                    cleared or TextDisplay.FLAG_ALIGN_LEFT.toInt()

                TextDisplay.TextAlignment.RIGHT ->
                    cleared or TextDisplay.FLAG_ALIGN_RIGHT.toInt()

                TextDisplay.TextAlignment.CENTER -> cleared
            }
            e.flags = updated.toByte()
        }
    }
}

internal object HologramDefaults {
    const val BACKGROUND = 0x40000000
    const val LINE_WIDTH = 200
}
