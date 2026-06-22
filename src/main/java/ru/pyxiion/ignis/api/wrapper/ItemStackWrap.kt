package ru.pyxiion.ignis.api.wrapper

import net.minecraft.component.DataComponentTypes
import net.minecraft.component.type.CustomModelDataComponent
import net.minecraft.component.type.LoreComponent
import net.minecraft.item.ItemStack
import net.minecraft.registry.Registries
import net.minecraft.text.Text
import net.minecraft.util.Unit
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import ru.pyxiion.ignis.api.MetaTableRegistry
import ru.pyxiion.ignis.api.util.metaTable
import ru.pyxiion.ignis.toLuaArray
import ru.pyxiion.ignis.unwrap
import ru.pyxiion.ignis.unwrapOrNull

object ItemStackWrap {
    private val BUILT = metaTable<ItemStack> {
        prop("id") { LuaValue.valueOf(Registries.ITEM.getId(item).toString()) }

        prop(
            "count",
            get = { LuaValue.valueOf(count) },
            set = { v -> count = v.toint() }
        )

        prop(
            "name",
            get = {
                val cn = get(DataComponentTypes.CUSTOM_NAME)
                if (cn != null) LuaValue.valueOf(cn.string) else LuaValue.NIL
            },
            set = { v ->
                if (v.isnil()) {
                    remove(DataComponentTypes.CUSTOM_NAME)
                } else {
                    set(DataComponentTypes.CUSTOM_NAME, Text.literal(v.tojstring()))
                }
            }
        )

        prop(
            "unbreakable",
            get = { LuaValue.valueOf(contains(DataComponentTypes.UNBREAKABLE)) },
            set = { v ->
                if (v.toboolean()) {
                    set(DataComponentTypes.UNBREAKABLE, Unit.INSTANCE)
                } else {
                    remove(DataComponentTypes.UNBREAKABLE)
                }
            }
        )

        prop(
            "custom_model_data",
            get = {
                val cmd = get(DataComponentTypes.CUSTOM_MODEL_DATA)
                val first = cmd?.floats?.firstOrNull()
                if (first != null) LuaValue.valueOf(first.toInt()) else LuaValue.NIL
            },
            set = { v ->
                if (v.isnil()) {
                    remove(DataComponentTypes.CUSTOM_MODEL_DATA)
                } else if (v.isint()) {
                    set(
                        DataComponentTypes.CUSTOM_MODEL_DATA,
                        CustomModelDataComponent(listOf(v.toint().toFloat()), emptyList(), emptyList(), emptyList())
                    )
                }
            }
        )

        method("copy") { args ->
            val self = args.arg(1).checktable()
            val stack = self.unwrap<ItemStack>().copy()
            wrap(stack)
        }

        prop(
            "lore",
            get = {
                get(DataComponentTypes.LORE)?.lines()?.map { LuaValue.valueOf(it.string) }.toLuaArray()
            },
            set = { v ->
                if (v.isnil()) {
                    remove(DataComponentTypes.LORE)
                } else if (v.istable()) {
                    val lines = mutableListOf<Text>()
                    val table = v.checktable()
                    for (i in 1..table.length()) {
                        lines.add(Text.literal(table.get(i).tojstring()))
                    }
                    set(DataComponentTypes.LORE, LoreComponent(lines))
                }
            }
        )


        toString { "ItemStack{${Registries.ITEM.getId(item)} x$count}" }
    }

    fun wrap(stack: ItemStack): LuaValue {
        val t = LuaTable()
        t.setmetatable(MetaTableRegistry.ITEM)
        t.rawset("__pxrp_type", LuaValue.valueOf("item"))
        t.rawset("__pxrp_object", LuaValue.userdataOf(stack))
        return t
    }

    fun unwrap(value: LuaValue): ItemStack? =
        if (value.istable()) value.checktable().unwrapOrNull<ItemStack>()?.copy() else null

    fun initMeta(meta: LuaTable) {
        BUILT.apply(meta)
    }
}
