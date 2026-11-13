package ru.pyxiion.ignis.api.util

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.IntTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.NumericTag
import net.minecraft.nbt.StringTag
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.ComponentSerialization
import net.minecraft.resources.RegistryOps
import ru.pyxiion.luanova.vm2.LuaError
import ru.pyxiion.luanova.vm2.LuaValue
import ru.pyxiion.ignis.PxIgnis

/**
 * Lua text as a [Component]: a string is literal text, a table is a text component in the vanilla JSON/NBT shape,
 * parsed by the game's own codec (so every vanilla field works): `text`, `color` (`"#RRGGBB"`, a name, or a number),
 * `font`, `shadow_color` (number ARGB or `"#AARRGGBB"`, 0 = no shadow), `bold`/`italic`/`underlined`/`strikethrough`/
 * `obfuscated`, `extra` (components or strings), `translate`, `object`, ... A sequence of components is a list.
 */
object Text {
    fun fromLua(value: LuaValue, what: String = "text"): Component {
        if (value.type() == LuaValue.TSTRING) return Component.literal(value.tojstring())
        if (!value.istable()) throw LuaError("$what: a string or a component table is expected, got ${value.typename()}")
        val tag = fix(LuaNbt.toTag(value)!!)
        val rt = PxIgnis.runtimeOrNull ?: throw LuaError("$what: not available before the server has started")
        var result = ComponentSerialization.CODEC.parse(RegistryOps.create(NbtOps.INSTANCE, rt.registries), tag)
        if (result.result().isEmpty) { // an empty Lua table may stand for an empty list
            val retry = ComponentSerialization.CODEC.parse(RegistryOps.create(NbtOps.INSTANCE, rt.registries), emptyToList(tag))
            if (retry.result().isPresent) result = retry
        }
        return result.result().orElseThrow { LuaError("$what: ${ItemStackCodec.codecError(result)}") }
    }

    /** `shadow_color = "#AARRGGBB"` and `color = 0xRRGGBB` to the forms the codec reads; recursive over `extra`/`with`. */
    private fun fix(tag: Tag): Tag = when (tag) {
        is ListTag -> ListTag().also { l -> tag.forEach { l.add(fix(it)) } }
        is CompoundTag -> CompoundTag().also { c ->
            for (k in tag.keySet()) {
                val v = tag.get(k)!!
                c.put(k, when {
                    k == "shadow_color" && v is StringTag -> argb(v.value())?.let { IntTag.valueOf(it) } ?: v
                    k == "color" && v is NumericTag -> StringTag.valueOf("#%06X".format(java.util.Locale.ROOT, v.intValue() and 0xFFFFFF))
                    k == "extra" || k == "with" -> fix(v)
                    else -> v
                })
            }
        }
        else -> tag
    }

    private fun argb(s: String): Int? =
        if (s.length == 9 && s[0] == '#') s.substring(1).toLongOrNull(16)?.toInt() else null

    private fun emptyToList(tag: Tag): Tag = when (tag) {
        is CompoundTag -> if (tag.isEmpty) ListTag() else CompoundTag().also { c -> for (k in tag.keySet()) c.put(k, emptyToList(tag.get(k)!!)) }
        is ListTag -> ListTag().also { l -> tag.forEach { l.add(emptyToList(it)) } }
        else -> tag
    }
}
