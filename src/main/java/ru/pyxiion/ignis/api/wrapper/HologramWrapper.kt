package ru.pyxiion.ignis.api.wrapper

import ru.pyxiion.ignis.wrapObject
import net.minecraft.world.entity.Display.BillboardConstraints
import net.minecraft.world.entity.Display.TextDisplay
import net.minecraft.network.chat.Component
import ru.pyxiion.luanova.vm2.LuaError
import ru.pyxiion.luanova.vm2.LuaTable
import ru.pyxiion.luanova.vm2.LuaValue
import ru.pyxiion.ignis.api.MetaTableRegistry
import ru.pyxiion.ignis.api.manager.HologramManager
import ru.pyxiion.ignis.api.util.metaTable
import ru.pyxiion.ignis.toLuaArray
import ru.pyxiion.ignis.unwrap


/**
 * A script-owned hologram. The Lua table is created once; when the chunk holding the entity is reloaded the
 * entity object changes, so [rebind] swaps `__pxrp_object` inside the same table and scripts keep their handle.
 */
class HologramWrapper(entity: TextDisplay) {
    internal var entity: TextDisplay = entity
        private set

    private val lua: LuaTable = newTable(entity)

    fun toLuaValue(): LuaValue = lua

    internal fun rebind(newEntity: TextDisplay) {
        entity = newEntity
        lua.rawset("__pxrp_object", LuaValue.userdataOf(newEntity))
    }

    internal fun destroyInternals() {
        if (!entity.isRemoved) entity.discard()
    }

    fun destroy() {
        destroyInternals()
        HologramManager.unregister(this)
    }

    companion object {
        /** Options of `world:spawnHologram`, applied through the property setters. */
        val OPTION_KEYS = listOf("alignment", "billboard", "lineWidth", "background", "opacity", "shadow", "seeThrough", "glowing")

        private fun newTable(entity: TextDisplay): LuaTable {
            return wrapObject(MetaTableRegistry.HOLOGRAM, "hologram", entity)
        }

        /** A text display this runtime did not create: same properties, no manager entry. */
        fun untracked(entity: TextDisplay): LuaValue = newTable(entity)
        private val BUILT by lazy {
            metaTable<TextDisplay> {
                inherit(EntityWrap.BUILT)

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
                    get = { LuaValue.valueOf(TextDisplay.getAlign(flags).serializedName) },
                    set = { v -> setAlignment(this, parseAlignment(v.tojstring())) }
                )
                prop(
                    "billboard",
                    get = { LuaValue.valueOf(billboardConstraints.serializedName) },
                    set = { v -> billboardConstraints = parseBillboard(v.tojstring()) }
                )
                prop(
                    "lineWidth",
                    get = { LuaValue.valueOf(lineWidth) },
                    set = { v -> lineWidth = v.checkint() }
                )
                prop(
                    "background",
                    get = { LuaValue.valueOf(backgroundColor) },
                    set = { v -> backgroundColor = v.checkint() }
                )
                prop(
                    "opacity",
                    get = { LuaValue.valueOf(textOpacity.toInt() and 0xFF) },
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
                    get = { LuaValue.valueOf(isCurrentlyGlowing) },
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

        private fun parseAlignment(s: String): TextDisplay.Align = when (s) {
            "left" -> TextDisplay.Align.LEFT
            "right" -> TextDisplay.Align.RIGHT
            "center" -> TextDisplay.Align.CENTER
            else -> throw LuaError("Неизвестное выравнивание '$s' (допустимо: left, center, right)")
        }

        private fun parseBillboard(s: String): BillboardConstraints = when (s) {
            "fixed" -> BillboardConstraints.FIXED
            "vertical" -> BillboardConstraints.VERTICAL
            "horizontal" -> BillboardConstraints.HORIZONTAL
            "center" -> BillboardConstraints.CENTER
            else -> throw LuaError("Неизвестный billboard '$s' (допустимо: fixed, vertical, horizontal, center)")
        }

        private fun setFlag(e: TextDisplay, flag: Byte, value: Boolean) {
            val current = e.flags.toInt()
            val newFlags = if (value) {
                current or flag.toInt()
            } else {
                current and flag.toInt().inv()
            }
            e.flags = newFlags.toByte()
        }

        private fun setAlignment(e: TextDisplay, alignment: TextDisplay.Align) {
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
            e.flags = updated.toByte()
        }
    }
}

internal object HologramDefaults {
    const val BACKGROUND = 0x40000000
    const val LINE_WIDTH = 200
}
