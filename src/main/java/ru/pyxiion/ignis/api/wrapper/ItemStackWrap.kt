package ru.pyxiion.ignis.api.wrapper

import ru.pyxiion.ignis.wrapObject
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.component.CustomModelData
import net.minecraft.world.item.component.ItemLore
import net.minecraft.world.item.ItemStack
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.util.Unit
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import ru.pyxiion.ignis.api.MetaTableRegistry
import ru.pyxiion.ignis.api.util.ItemComponents
import ru.pyxiion.ignis.api.util.LuaNbt
import ru.pyxiion.ignis.api.util.metaTable
import ru.pyxiion.ignis.toLuaArray
import ru.pyxiion.ignis.unwrap
import ru.pyxiion.ignis.unwrapOrNull

object ItemStackWrap {
    private val BUILT = metaTable<ItemStack> {
        prop("id") { LuaValue.valueOf(BuiltInRegistries.ITEM.getKey(item).toString()) }

        prop(
            "count",
            get = { LuaValue.valueOf(count) },
            set = { v ->
                val n = v.checkint()
                if (n !in 0..99) throw LuaError("item.count: ожидается число от 0 до 99, получено $n")
                count = n
            }
        )

        prop(
            "name",
            get = {
                val cn = get(DataComponents.CUSTOM_NAME)
                if (cn != null) LuaValue.valueOf(cn.string) else LuaValue.NIL
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
            get = { LuaValue.valueOf(has(DataComponents.UNBREAKABLE)) },
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

        method("getComponent") { args ->
            ItemComponents.get(args.arg(1).checktable().unwrap<ItemStack>(), args.arg(2).checkjstring())
        }

        method("setComponent") { args ->
            ItemComponents.set(args.arg(1).checktable().unwrap<ItemStack>(), args.arg(2).checkjstring(), args.arg(3))
            LuaValue.NIL
        }

        method("removeComponent") { args ->
            ItemComponents.remove(args.arg(1).checktable().unwrap<ItemStack>(), args.arg(2).checkjstring())
            LuaValue.NIL
        }

        method("resetComponent") { args ->
            ItemComponents.reset(args.arg(1).checktable().unwrap<ItemStack>(), args.arg(2).checkjstring())
            LuaValue.NIL
        }

        for (name in ItemComponents.SHORTCUTS) {
            prop(name, get = { ItemComponents.getShortcut(this, name) }, set = { v -> ItemComponents.setShortcut(this, name, v) })
        }

        method("getData") { args ->
            val stack = args.arg(1).checktable().unwrap<ItemStack>()
            val tag = stack.get(DataComponents.CUSTOM_DATA)?.copyTag()?.get(args.arg(2).checkjstring())
            if (tag == null) LuaValue.NIL else LuaNbt.fromTag(tag)
        }

        method("setData") { args ->
            val stack = args.arg(1).checktable().unwrap<ItemStack>()
            val key = args.arg(2).checkjstring()
            val tag = LuaNbt.toTag(args.arg(3))
            val data = stack.get(DataComponents.CUSTOM_DATA)?.copyTag() ?: CompoundTag()
            if (tag == null) data.remove(key) else data.put(key, tag)
            if (data.isEmpty) stack.remove(DataComponents.CUSTOM_DATA) else stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data))
            LuaValue.NIL
        }

        prop(
            "lore",
            get = {
                get(DataComponents.LORE)?.lines()?.map { LuaValue.valueOf(it.string) }.toLuaArray()
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


        toString { "ItemStack{${BuiltInRegistries.ITEM.getKey(item)} x$count}" }
    }

    fun wrap(stack: ItemStack): LuaValue {
        return wrapObject(MetaTableRegistry.ITEM, "item", stack)
    }

    fun unwrap(value: LuaValue): ItemStack? =
        if (value.istable()) value.checktable().unwrapOrNull<ItemStack>()?.copy() else null

    fun initMeta(meta: LuaTable) {
        BUILT.apply(meta)
    }
}
