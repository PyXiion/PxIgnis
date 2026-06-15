package ru.pyxiion.ignis.api

import net.minecraft.entity.decoration.DisplayEntity
import net.minecraft.entity.decoration.DisplayEntity.BillboardMode
import net.minecraft.entity.decoration.DisplayEntity.TextDisplayEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.text.Text
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.lib.jse.CoerceJavaToLua

class HologramWrapper(
    internal val entity: DisplayEntity.TextDisplayEntity,
    @Suppress("unused") private val world: ServerWorld,
) {

    fun toLuaValue(): LuaValue {
        val t = LuaTable()
        t.setmetatable(MetaTableRegistry.HOLOGRAM)
        t.rawset("__pxrp_type", LuaValue.valueOf("hologram"))
        t.rawset("__pxrp_object", CoerceJavaToLua.coerce(entity))
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
        internal val hologramKeys = listOf(
            "text", "lines", "alignment", "billboard", "lineWidth",
            "background", "opacity", "shadow", "seeThrough", "glowing",
            "setLine", "destroy",
        )

        fun initMeta(meta: LuaTable) {
            meta.set("__index", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val key = args.arg(2).tojstring()
                    val e = self.rawget("__pxrp_object").checkuserdata() as DisplayEntity.TextDisplayEntity

                    when (key) {
                        "text" -> return LuaValue.valueOf(e.text.string)
                        "lines" -> {
                            val t = LuaTable()
                            val parts = e.text.string.split("\n")
                            for ((i, line) in parts.withIndex()) {
                                t.rawset(i + 1, LuaValue.valueOf(line))
                            }
                            return t
                        }
                        "alignment" -> return LuaValue.valueOf(alignmentToString(e))
                        "billboard" -> return LuaValue.valueOf(billboardToString(e.billboardMode))
                        "lineWidth" -> return LuaValue.valueOf(e.lineWidth)
                        "background" -> return LuaValue.valueOf(e.background)
                        "opacity" -> return LuaValue.valueOf(e.textOpacity.toInt())
                        "shadow" -> return LuaValue.valueOf(hasFlag(e, TextDisplayEntity.SHADOW_FLAG))
                        "seeThrough" -> return LuaValue.valueOf(hasFlag(e, TextDisplayEntity.SEE_THROUGH_FLAG))
                        "glowing" -> return LuaValue.valueOf(e.isGlowing)
                        else -> {
                            val mv = meta.get(key)
                            if (!mv.isnil()) return mv
                            val entityIndex = MetaTableRegistry.ENTITY.get("__index")
                            if (entityIndex.isfunction()) {
                                return entityIndex.invoke(LuaValue.varargsOf(arrayOf(self, LuaValue.valueOf(key))))
                            }
                            return LuaValue.NIL
                        }
                    }
                }
            })

            meta.set("__newindex", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val key = args.arg(2).tojstring()
                    val value = args.arg(3)
                    val e = self.rawget("__pxrp_object").checkuserdata() as DisplayEntity.TextDisplayEntity

                    when (key) {
                        "text" -> {
                            e.text = Text.literal(value.tojstring())
                        }
                        "lines" -> {
                            val ct = value.checktable()
                            val list = mutableListOf<String>()
                            var i = 1
                            while (true) {
                                val v = ct.rawget(i)
                                if (v.isnil()) break
                                if (v.isstring()) list.add(v.tojstring())
                                i++
                            }
                            e.text = Text.literal(list.joinToString("\n"))
                        }
                        "alignment" -> setAlignment(e, parseAlignment(value.tojstring()))
                        "billboard" -> e.billboardMode = parseBillboard(value.tojstring())
                        "lineWidth" -> e.lineWidth = value.checkint()
                        "background" -> e.background = value.checkint()
                        "opacity" -> e.textOpacity = value.checkint().toByte()
                        "shadow" -> setFlag(e, TextDisplayEntity.SHADOW_FLAG, value.toboolean())
                        "seeThrough" -> setFlag(e, TextDisplayEntity.SEE_THROUGH_FLAG, value.toboolean())
                        "glowing" -> e.isGlowing = value.toboolean()
                        else -> return LuaValue.NIL
                    }
                    return LuaValue.NIL
                }
            })

            meta.set("__pairs", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1)
                    val keys = hologramKeys + EntityWrapper.entityKeys
                    val iterator = object : VarArgFunction() {
                        private var index = 0
                        override fun invoke(args: Varargs): Varargs {
                            if (index >= keys.size) return LuaValue.NIL
                            val key = keys[index]
                            index++
                            val value = self.get(key)
                            return LuaValue.varargsOf(arrayOf(LuaValue.valueOf(key), value))
                        }
                    }
                    return LuaValue.varargsOf(arrayOf(iterator, self, LuaValue.NIL))
                }
            })

            meta.rawset("setLine", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val e = self.rawget("__pxrp_object").checkuserdata() as DisplayEntity.TextDisplayEntity
                    val idx = args.arg(2).checkint()
                    val newText = args.arg(3).checkjstring()
                    if (idx < 1) throw LuaError("Номер строки должен быть >= 1")
                    val lines = e.text.string.split("\n").toMutableList()
                    while (lines.size < idx) lines.add("")
                    lines[idx - 1] = newText
                    e.text = Text.literal(lines.joinToString("\n"))
                    return LuaValue.NIL
                }
            })

            meta.rawset("destroy", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val e = self.rawget("__pxrp_object").checkuserdata() as DisplayEntity.TextDisplayEntity
                    val wrapper = HologramManager.get(e.uuid) ?: return LuaValue.NIL
                    wrapper.destroy()
                    return LuaValue.NIL
                }
            })
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
        internal fun applyAlignmentFromLua(e: DisplayEntity.TextDisplayEntity, s: String) = setAlignment(e, parseAlignment(s))
        internal fun applyFlagFromLua(e: DisplayEntity.TextDisplayEntity, flag: Byte, value: Boolean) = setFlag(e, flag, value)

        private fun alignmentToString(e: DisplayEntity.TextDisplayEntity): String =
            TextDisplayEntity.getAlignment(e.displayFlags).asString()

        private fun billboardToString(b: BillboardMode): String = b.asString()

        private fun hasFlag(e: DisplayEntity.TextDisplayEntity, flag: Byte): Boolean =
            (e.displayFlags.toInt() and flag.toInt()) != 0

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
