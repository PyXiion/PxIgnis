package ru.pyxiion.ignis.api.wrapper

import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.component.CustomModelData
import net.minecraft.world.item.component.ItemLore
import net.minecraft.world.item.ItemStack
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
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
        prop("id") { LuaValue.valueOf(BuiltInRegistries.ITEM.getId(item).toString()) }

        prop(
            "count",
            get = { LuaValue.valueOf(count) },
            set = { v -> count = v.toint() }
        )

        prop(
            "name",
            get = {
                val cn = get(DataComponents.CUSTOM_NAME)
                if (cn != null) LuaValue.valueOf(cn.getString()) else LuaValue.NIL
            },
            set = { v ->
                if (v.isnil()) {
                    remove(DataComponents.CUSTOM_NAME)
                } else {
                    set(DataComponents.CUSTOM_NAME, Component.literal(v.tojstring()))
                }
            }
        )

        prop(
            "unbreakable",
            get = { LuaValue.valueOf(get(DataComponents.UNBREAKABLE) != null) },
            set = { v ->
                if (v.toboolean()) {
                    set(DataComponents.UNBREAKABLE, Unit.INSTANCE)
                } else {
                    remove(DataComponents.UNBREAKABLE)
                }
            }
        )

        prop(
            "custom_model_data",
            get = {
                val cmd = get(DataComponents.CUSTOM_MODEL_DATA)
                val first = cmd?.floats?.firstOrNull()
                if (first != null) LuaValue.valueOf(first.toInt()) else LuaValue.NIL
            },
            set = { v ->
                if (v.isnil()) {
                    remove(DataComponents.CUSTOM_MODEL_DATA)
                } else if (v.isint()) {
                    set(
                        DataComponents.CUSTOM_MODEL_DATA,
                        CustomModelData(listOf(v.toint().toFloat()), emptyList(), emptyList(), emptyList())
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
                get(DataComponents.LORE)?.lines()?.map { LuaValue.valueOf(it.getString()) }.toLuaArray()
            },
            set = { v ->
                if (v.isnil()) {
                    remove(DataComponents.LORE)
                } else if (v.istable()) {
                    val lines = mutableListOf<Component>()
                    val table = v.checktable()
                    for (i in 1..table.length()) {
                        lines.add(Component.literal(table.get(i).tojstring()))
                    }
                    set(DataComponents.LORE, ItemLore(lines))
                }
            }
        )


        toString { "ItemStack{${BuiltInRegistries.ITEM.getId(item)} x$count}" }
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
