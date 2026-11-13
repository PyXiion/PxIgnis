package ru.pyxiion.ignis.api.util

import com.mojang.serialization.DataResult
import net.minecraft.core.component.DataComponentType
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.resources.RegistryOps
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomModelData
import net.minecraft.world.item.component.UseCooldown
import ru.pyxiion.luanova.vm2.LuaError
import ru.pyxiion.luanova.vm2.LuaTable
import ru.pyxiion.luanova.vm2.LuaValue
import ru.pyxiion.ignis.PxIgnis
import java.util.Optional

/**
 * Data components of an item as Lua values: the generic accessors (`getComponent` and friends, through each
 * component's own codec and [LuaNbt]) and the shortcut properties built on them.
 */
object ItemComponents {
    private fun ops(): RegistryOps<Tag> {
        val rt = PxIgnis.runtimeOrNull ?: throw LuaError("components are not available before the server has started")
        return RegistryOps.create(NbtOps.INSTANCE, rt.registries)
    }

    fun normalizeId(id: String): Identifier =
        Identifier.tryParse(if (':' in id) id else "minecraft:$id") ?: throw LuaError("bad id '$id'")

    @Suppress("UNCHECKED_CAST")
    fun type(id: String): DataComponentType<Any> {
        val t = BuiltInRegistries.DATA_COMPONENT_TYPE.getOptional(normalizeId(id)).orElseThrow { LuaError("component '$id' not found") }
        return t as DataComponentType<Any>
    }

    private fun message(result: DataResult<*>): String = ItemStackCodec.codecError(result)

    /** The component's value as a Lua value, nil when the stack has no such component. */
    fun get(stack: ItemStack, id: String): LuaValue {
        val t = type(id)
        val value = stack.get(t) ?: return LuaValue.NIL
        val codec = t.codec() ?: throw LuaError("component '$id' cannot be read (it is not serialisable)")
        val result = codec.encodeStart(ops(), value)
        return LuaNbt.fromTag(result.result().orElseThrow { LuaError("component '$id': ${message(result)}") })
    }

    /** Sets the component from a Lua value; nil removes it (see [remove]). */
    fun set(stack: ItemStack, id: String, value: LuaValue) {
        val t = type(id)
        val tag = LuaNbt.toTag(value)
        if (tag == null) {
            stack.remove(t)
            return
        }
        val codec = t.codec() ?: throw LuaError("component '$id' cannot be set (it is not serialisable)")
        var result = codec.parse(ops(), tag)
        if (result.result().isEmpty) {
            // an empty Lua table is an empty compound, but may stand for an empty list
            val retry = codec.parse(ops(), emptyCompoundsToLists(tag))
            if (retry.result().isPresent) result = retry
        }
        stack.set(t, result.result().orElseThrow { LuaError("component '$id': ${message(result)}") })
    }

    /** Explicit removal: the stack has no such component, even one that the item type has by default. */
    fun remove(stack: ItemStack, id: String) {
        stack.remove(type(id))
    }

    /** Back to the item type's default (the component is present again if the item type has it). */
    fun reset(stack: ItemStack, id: String) {
        val t = type(id)
        stack.set(t, stack.item.components().get(t))
    }

    private fun emptyCompoundsToLists(tag: Tag): Tag = when (tag) {
        is CompoundTag -> if (tag.isEmpty) ListTag() else CompoundTag().also { c ->
            for (k in tag.keySet()) c.put(k, emptyCompoundsToLists(tag.get(k)!!))
        }
        is ListTag -> ListTag().also { l -> tag.forEach { l.add(emptyCompoundsToLists(it)) } }
        else -> tag
    }

    // --- shortcuts ------------------------------------------------------------------------------------------

    /** Shortcut name to component id, for shortcuts whose Lua value is the component's own. */
    val PLAIN_SHORTCUTS = mapOf(
        "item_model" to "minecraft:item_model",
        "tooltip_style" to "minecraft:tooltip_style",
        "rarity" to "minecraft:rarity",
        "glint" to "minecraft:enchantment_glint_override",
        "max_stack_size" to "minecraft:max_stack_size",
    )

    /** All shortcut names (plain ones plus `item_name`, `use_cooldown`, `custom_model`). */
    val SHORTCUTS = PLAIN_SHORTCUTS.keys + listOf("item_name", "use_cooldown", "custom_model")

    fun getShortcut(stack: ItemStack, name: String): LuaValue = when (name) {
        "item_name" -> stack.get(net.minecraft.core.component.DataComponents.ITEM_NAME)?.let { LuaValue.valueOf(it.string) } ?: LuaValue.NIL
        "use_cooldown" -> stack.get(net.minecraft.core.component.DataComponents.USE_COOLDOWN)?.let { uc ->
            LuaTable().also {
                it.rawset("seconds", LuaValue.valueOf(uc.seconds().toDouble()))
                uc.cooldownGroup().ifPresent { g -> it.rawset("group", LuaValue.valueOf(g.toString())) }
            }
        } ?: LuaValue.NIL
        "custom_model" -> stack.get(net.minecraft.core.component.DataComponents.CUSTOM_MODEL_DATA)?.let { cmd ->
            LuaTable().also {
                it.rawset("floats", seq(cmd.floats()) { f -> LuaValue.valueOf(f.toDouble()) })
                it.rawset("flags", seq(cmd.flags()) { b -> LuaValue.valueOf(b) })
                it.rawset("strings", seq(cmd.strings()) { s -> LuaValue.valueOf(s) })
                it.rawset("colors", seq(cmd.colors()) { c -> LuaValue.valueOf(c) })
            }
        } ?: LuaValue.NIL
        else -> get(stack, PLAIN_SHORTCUTS[name] ?: throw LuaError("unknown item property '$name'"))
    }

    /** nil resets the property to the item type's default (`custom_model` and `use_cooldown`: removes it). */
    fun setShortcut(stack: ItemStack, name: String, v: LuaValue) {
        when (name) {
            "item_name" -> if (v.isnil()) reset(stack, "minecraft:item_name")
                else stack.set(net.minecraft.core.component.DataComponents.ITEM_NAME, Component.literal(v.checkjstring()))
            "use_cooldown" -> if (v.isnil()) stack.remove(net.minecraft.core.component.DataComponents.USE_COOLDOWN) else {
                val t = v.checktable()
                val seconds = t.get("seconds").checkdouble().toFloat()
                if (!(seconds > 0f)) throw LuaError("use_cooldown.seconds must be positive")
                val group = t.get("group").let { if (it.isnil()) Optional.empty() else Optional.of(normalizeId(it.checkjstring())) }
                stack.set(net.minecraft.core.component.DataComponents.USE_COOLDOWN, UseCooldown(seconds, group))
            }
            "custom_model" -> if (v.isnil()) stack.remove(net.minecraft.core.component.DataComponents.CUSTOM_MODEL_DATA) else {
                val t = v.checktable()
                stack.set(
                    net.minecraft.core.component.DataComponents.CUSTOM_MODEL_DATA,
                    CustomModelData(
                        list(t.get("floats"), "floats") { it.checkdouble().toFloat() },
                        list(t.get("flags"), "flags") { it.checkboolean() },
                        list(t.get("strings"), "strings") { it.checkjstring() },
                        list(t.get("colors"), "colors") { it.checkint() },
                    )
                )
            }
            else -> {
                val id = PLAIN_SHORTCUTS[name] ?: throw LuaError("unknown item property '$name'")
                if (v.isnil()) reset(stack, id) else set(stack, id, v)
            }
        }
    }

    private fun <T> seq(items: List<T>, conv: (T) -> LuaValue): LuaTable =
        LuaTable().also { t -> items.forEachIndexed { i, x -> t.rawset(i + 1, conv(x)) } }

    private fun <T> list(v: LuaValue, what: String, conv: (LuaValue) -> T): List<T> {
        if (v.isnil()) return emptyList()
        val t = v.checktable()
        return (1..t.length()).map { i ->
            try { conv(t.get(i)) } catch (e: LuaError) { throw LuaError("custom_model.$what[$i]: ${e.message}") }
        }
    }
}
