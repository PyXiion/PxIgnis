package ru.pyxiion.ignis.api.util

import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.component.CustomModelData
import net.minecraft.world.item.component.ItemLore
import net.minecraft.world.item.ItemStack
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import ru.pyxiion.ignis.lookup
import net.minecraft.util.Unit
import ru.pyxiion.luanova.vm2.LuaTable
import ru.pyxiion.luanova.vm2.LuaValue
import ru.pyxiion.luanova.vm2.Varargs

object ItemBuilder {
    fun build(
        id: String,
        count: Int = 1,
        name: String? = null,
        unbreakable: Boolean = false,
        customModelData: Int? = null,
        lore: List<String>? = null,
        data: CompoundTag? = null,
    ): ItemStack {
        val item = BuiltInRegistries.ITEM.lookup("Item", id)
        val stack = ItemStack(item, count)

        if (name != null) stack.set(DataComponents.CUSTOM_NAME, Component.literal(name))
        if (unbreakable) stack.set(DataComponents.UNBREAKABLE, Unit.INSTANCE)
        if (customModelData != null) {
            stack.set(
                DataComponents.CUSTOM_MODEL_DATA,
                CustomModelData(listOf(customModelData.toFloat()), emptyList(), emptyList(), emptyList())
            )
        }
        if (lore != null) stack.set(DataComponents.LORE, ItemLore(lore.map { Component.literal(it) }))

        if (data != null && !data.isEmpty) stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data))

        return stack
    }

    /**
     * Parses Lua arguments into an [ItemStack].
     *
     * Supported call patterns:
     *   mc.createItem("id")
     *   mc.createItem("id", count)
     *   mc.createItem("id", { name=..., lore=..., unbreakable=..., custom_model_data=..., count=..., data={...}, components={...}, item_model=..., ... })
     *   mc.createItem { id="...", ... }
     */
    fun fromLua(args: Varargs): ItemStack {
        val first = args.arg(1)
        val second = if (args.narg() >= 2) args.arg(2).let { if (it.isnil()) null else it } else null

        // mc.createItem { id = "...", ... }
        if (first.istable() && args.narg() == 1) {
            return fromTable(first)
        }

        val id = first.checkjstring()
        if (second == null) return build(id)

        if (second.isnumber()) return build(id, count = second.toint())

        return fromTable(id, second.checktable())
    }

    private fun fromTable(id: String, table: LuaValue): ItemStack {
        val t = table.checktable()
        return build(
            id = id,
            count = t.get("count").optint(1),
            name = t.get("name").let { if (it.isstring()) it.tojstring() else null },
            unbreakable = t.get("unbreakable").toboolean(),
            customModelData = t.get("custom_model_data").let { if (it.isint()) it.toint() else null },
            lore = parseLore(t.get("lore")),
            data = t.get("data").let { if (it.isnil()) null else LuaNbt.toCompound(it, "createItem data") },
        ).also { applyExtras(it, t) }
    }

    private fun fromTable(table: LuaValue): ItemStack {
        val t = table.checktable()
        return build(
            id = t.get("id").checkjstring(),
            count = t.get("count").optint(1),
            name = t.get("name").let { if (it.isstring()) it.tojstring() else null },
            unbreakable = t.get("unbreakable").toboolean(),
            customModelData = t.get("custom_model_data").let { if (it.isint()) it.toint() else null },
            lore = parseLore(t.get("lore")),
            data = t.get("data").let { if (it.isnil()) null else LuaNbt.toCompound(it, "createItem data") },
        ).also { applyExtras(it, t) }
    }

    /** `item_model`, `glint`, ... and `components = { ["minecraft:x"] = v }`; the latter win on a clash. */
    private fun applyExtras(stack: ItemStack, t: LuaTable) {
        for (name in ItemComponents.SHORTCUTS) {
            val v = t.get(name)
            if (!v.isnil()) ItemComponents.setShortcut(stack, name, v)
        }
        val components = t.get("components")
        if (!components.isnil()) {
            val ct = components.checktable()
            var k: Varargs = ct.next(LuaValue.NIL)
            while (!k.arg1().isnil()) {
                ItemComponents.set(stack, k.arg1().checkjstring(), k.arg(2))
                k = ct.next(k.arg1())
            }
        }
    }

    private fun parseLore(v: LuaValue): List<String>? {
        if (!v.istable()) return null
        val lines = mutableListOf<String>()
        val t = v.checktable()
        for (i in 1..t.length()) {
            val line = t.get(i)
            if (line.isstring()) lines.add(line.tojstring())
        }
        return lines
    }
}
