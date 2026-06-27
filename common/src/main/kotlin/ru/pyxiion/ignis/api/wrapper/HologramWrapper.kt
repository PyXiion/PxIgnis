package ru.pyxiion.ignis.api.wrapper

import net.minecraft.world.entity.Display
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
    internal val entity: Display.TextDisplay,
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
        if (!entity.isRemoved) entity.discard()
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
                    get = { LuaValue.valueOf(getText().string) },
                    set = { v -> setText(Component.literal(v.tojstring())) }
                )
                prop(
                    "lines",
                    get = {
                        getText().string.split("\n").map(LuaValue::valueOf).toLuaArray()
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
                        setText(Component.literal(list.joinToString("\n")))
                    }
                )
                prop(
                    "alignment",
                    get = { LuaValue.valueOf(TextDisplay.getAlign(getFlags()).getSerializedName()) },
                    set = { v -> setAlignment(this, parseAlignment(v.tojstring())) }
                )
                prop(
                    "billboard",
                    get = { LuaValue.valueOf(getBillboardConstraints().getSerializedName()) },
                    set = { v -> setBillboardConstraints(parseBillboard(v.tojstring())) }
                )
                prop(
                    "lineWidth",
                    get = { LuaValue.valueOf(getLineWidth()) },
                    set = { v -> setLineWidth(v.checkint()) }
                )
                prop(
                    "background",
                    get = { LuaValue.valueOf(getBackgroundColor()) },
                    set = { v -> setBackgroundColor(v.checkint()) }
                )
                prop(
                    "opacity",
                    get = { LuaValue.valueOf(getTextOpacity().toInt()) },
                    set = { v -> setTextOpacity(v.checkint().toByte()) }
                )
                prop(
                    "shadow",
                    get = {
                        LuaValue.valueOf((getFlags().toInt() and TextDisplay.FLAG_SHADOW.toInt()) != 0)
                    },
                    set = { v -> setFlag(this, TextDisplay.FLAG_SHADOW, v.toboolean()) }
                )
                prop(
                    "seeThrough",
                    get = {
                        LuaValue.valueOf((getFlags().toInt() and TextDisplay.FLAG_SEE_THROUGH.toInt()) != 0)
                    },
                    set = { v -> setFlag(this, TextDisplay.FLAG_SEE_THROUGH, v.toboolean()) }
                )
                prop(
                    "glowing",
                    get = { LuaValue.valueOf(hasGlowingTag()) },
                    set = { v -> setGlowingTag(v.toboolean()) }
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
                    e.setText(Component.literal(lines.joinToString("\n")))
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

        private fun parseAlignment(s: String): TextDisplay.Align = when (s) {
            "left" -> TextDisplay.Align.LEFT
            "right" -> TextDisplay.Align.RIGHT
            "center" -> TextDisplay.Align.CENTER
            else -> throw LuaError("Неизвестное выравнивание '$s' (допустимо: left, center, right)")
        }

        private fun parseBillboard(s: String): Display.BillboardConstraints = when (s) {
            "fixed" -> Display.BillboardConstraints.FIXED
            "vertical" -> Display.BillboardConstraints.VERTICAL
            "horizontal" -> Display.BillboardConstraints.HORIZONTAL
            "center" -> Display.BillboardConstraints.CENTER
            else -> throw LuaError("Неизвестный billboard '$s' (допустимо: fixed, vertical, horizontal, center)")
        }

        internal fun parseBillboardFromLua(s: String): Display.BillboardConstraints = parseBillboard(s)
        internal fun applyAlignmentFromLua(e: Display.TextDisplay, s: String) =
            setAlignment(e, parseAlignment(s))

        internal fun applyFlagFromLua(e: Display.TextDisplay, flag: Byte, value: Boolean) =
            setFlag(e, flag, value)

        private fun setFlag(e: Display.TextDisplay, flag: Byte, value: Boolean) {
            val current = e.flags.toInt()
            val newFlags = if (value) {
                current or flag.toInt()
            } else {
                current and flag.toInt().inv()
            }
            e.setFlags(newFlags.toByte())
        }

        private fun setAlignment(e: Display.TextDisplay, alignment: TextDisplay.Align) {
            val current = e.flags.toInt()
            val cleared = current and
                    (TextDisplay.FLAG_ALIGN_LEFT.toInt().inv()) and
                    (TextDisplay.FLAG_ALIGN_RIGHT.toInt().inv())
            val updated = when (alignment) {
                TextDisplay.Align.LEFT ->
                    cleared or TextDisplay.FLAG_ALIGN_LEFT.toInt()

                TextDisplay.Align.RIGHT ->
                    cleared or TextDisplay.FLAG_ALIGN_RIGHT.toInt()

                TextDisplay.Align.CENTER -> cleared
            }
            e.setFlags(updated.toByte())
        }
    }
}

internal object HologramDefaults {
    const val BACKGROUND = 0x40000000
    const val LINE_WIDTH = 200
}
