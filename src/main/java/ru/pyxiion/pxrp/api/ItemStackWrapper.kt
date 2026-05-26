package ru.pyxiion.pxrp.api

import net.minecraft.component.DataComponentTypes
import net.minecraft.component.type.AttributeModifierSlot
import net.minecraft.component.type.AttributeModifiersComponent
import net.minecraft.component.type.CustomModelDataComponent
import net.minecraft.component.type.LoreComponent
import net.minecraft.entity.attribute.EntityAttributeModifier
import net.minecraft.entity.attribute.EntityAttributes
import net.minecraft.item.ItemStack
import net.minecraft.registry.Registries
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import net.minecraft.util.Unit
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.lib.jse.CoerceJavaToLua

object ItemStackWrapper {

    private const val MARKER = "__pxrp_item"
    private const val STORAGE = "_stack"

    fun wrap(stack: ItemStack): LuaValue {
        val t = LuaTable()
        t.rawset("id", LuaValue.valueOf(Registries.ITEM.getId(stack.item).toString()))
        t.rawset("count", LuaValue.valueOf(stack.count))
        t.rawset(MARKER, LuaValue.valueOf(true))
        t.rawset(STORAGE, CoerceJavaToLua.coerce(stack))
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
        if (!table.get(MARKER).toboolean()) return null
        return (table.get(STORAGE).checkuserdata(ItemStack::class.java) as ItemStack).copy()
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
                    Identifier.of("pxrp", "attack_damage"),
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
