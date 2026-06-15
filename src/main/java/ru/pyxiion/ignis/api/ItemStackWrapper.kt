package ru.pyxiion.ignis.api

import com.google.gson.JsonParser
import com.mojang.serialization.JsonOps
import net.minecraft.component.DataComponentTypes
import net.minecraft.component.type.AttributeModifierSlot
import net.minecraft.component.type.AttributeModifiersComponent
import net.minecraft.component.type.CustomModelDataComponent
import net.minecraft.component.type.LoreComponent
import net.minecraft.entity.attribute.EntityAttributeModifier
import net.minecraft.entity.attribute.EntityAttributes
import net.minecraft.item.ItemStack
import net.minecraft.registry.Registries
import net.minecraft.registry.RegistryOps
import net.minecraft.registry.RegistryWrapper
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import net.minecraft.util.Unit
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.lib.jse.CoerceJavaToLua

object ItemStackWrapper {

    private const val TYPE_KEY = "__pxrp_type"
    private const val OBJECT_KEY = "__pxrp_object"
    private const val TYPE_VALUE = "item"

    fun wrap(stack: ItemStack): LuaValue {
        val t = LuaTable()
        t.rawset("id", LuaValue.valueOf(Registries.ITEM.getId(stack.item).toString()))
        t.rawset("count", LuaValue.valueOf(stack.count))
        t.rawset(TYPE_KEY, LuaValue.valueOf(TYPE_VALUE))
        t.rawset(OBJECT_KEY, CoerceJavaToLua.coerce(stack))
        t.setmetatable(MetaTableRegistry.ITEM)
        val cn = stack.get(DataComponentTypes.CUSTOM_NAME)
        if (cn != null) {
            t.rawset("name", LuaValue.valueOf(cn.string))
        }
        if (stack.contains(DataComponentTypes.UNBREAKABLE)) {
            t.rawset("unbreakable", LuaValue.TRUE)
        }
        val cmd = stack.get(DataComponentTypes.CUSTOM_MODEL_DATA)
        if (cmd != null) {
            val first = cmd.floats.firstOrNull()
            if (first != null) {
                t.rawset("custom_model_data", LuaValue.valueOf(first.toInt()))
            }
        }
        return t
    }

    fun unwrap(value: LuaValue): ItemStack? {
        if (!value.istable()) return null
        val table = value.checktable()
        if (table.get(TYPE_KEY).tojstring() != TYPE_VALUE) return null
        return (table.get(OBJECT_KEY).checkuserdata(ItemStack::class.java) as ItemStack).copy()
    }

    fun toJson(stack: ItemStack, lookup: RegistryWrapper.WrapperLookup): String {
        val ops = RegistryOps.of(JsonOps.INSTANCE, lookup)
        return ItemStack.CODEC.encodeStart(ops, stack)
            .result()
            .orElseThrow { LuaError("Не удалось сериализовать предмет") }
            .toString()
    }

    fun fromJson(json: String, lookup: RegistryWrapper.WrapperLookup): ItemStack {
        val ops = RegistryOps.of(JsonOps.INSTANCE, lookup)
        return ItemStack.CODEC.parse(ops, JsonParser.parseString(json))
            .result()
            .orElseThrow { LuaError("Не удалось десериализовать предмет") }
    }

    fun createItem(id: String, countOrTable: LuaValue? = null): ItemStack {
        val item = Registries.ITEM.get(Identifier.of(id))
            ?: throw IllegalArgumentException("Item '$id' not found")

        if (countOrTable == null || countOrTable.isnumber()) {
            val count = countOrTable?.toint() ?: 1
            return ItemStack(item, count)
        }

        val table = countOrTable.checktable()
        val count = table.get("count").optint(1)
        val stack = ItemStack(item, count)

        table.get("name").let { v ->
            if (v.isstring()) {
                stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(v.tojstring()))
            }
        }

        table.get("lore").let { v ->
            if (v.istable()) {
                val loreTable = v.checktable()
                val lines = mutableListOf<Text>()
                for (i in 1..loreTable.length()) {
                    val line = loreTable.get(i)
                    if (line.isstring()) {
                        lines.add(Text.literal(line.tojstring()))
                    }
                }
                stack.set(DataComponentTypes.LORE, LoreComponent(lines))
            }
        }

        table.get("custom_model_data").let { v ->
            if (v.isint()) {
                stack.set(
                    DataComponentTypes.CUSTOM_MODEL_DATA,
                    CustomModelDataComponent(
                        listOf(v.toint().toFloat()),
                        emptyList(),
                        emptyList(),
                        emptyList()
                    )
                )
            }
        }

        table.get("unbreakable").let { v ->
            if (v.toboolean()) {
                stack.set(DataComponentTypes.UNBREAKABLE, Unit.INSTANCE)
            }
        }

        table.get("attackDamage").let { v ->
            if (v.isnumber()) {
                val attrMod = EntityAttributeModifier(
                    Identifier.of("pxignis", "attack_damage"),
                    v.todouble() - 1.0,
                    EntityAttributeModifier.Operation.ADD_VALUE
                )
                val current = stack.getOrDefault(
                    DataComponentTypes.ATTRIBUTE_MODIFIERS,
                    AttributeModifiersComponent.DEFAULT
                )
                stack.set(
                    DataComponentTypes.ATTRIBUTE_MODIFIERS,
                    current.with(EntityAttributes.ATTACK_DAMAGE, attrMod, AttributeModifierSlot.MAINHAND)
                )
            }
        }

        return stack
    }
}
