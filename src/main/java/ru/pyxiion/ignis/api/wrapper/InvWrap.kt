package ru.pyxiion.ignis.api.wrapper

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojang.serialization.JsonOps
import net.minecraft.inventory.SimpleInventory
import net.minecraft.item.ItemStack
import net.minecraft.registry.RegistryOps
import net.minecraft.registry.RegistryWrapper
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import ru.pyxiion.ignis.api.MetaTableRegistry
import ru.pyxiion.ignis.api.manager.ContainerManager
import ru.pyxiion.ignis.api.manager.LockableInventory
import ru.pyxiion.ignis.api.util.metaTable
import ru.pyxiion.ignis.unwrap

object InvWrap {

    fun wrap(inv: SimpleInventory): LuaValue {
        val t = LuaTable()
        t.setmetatable(MetaTableRegistry.INVENTORY)
        t.rawset("__pxrp_type", LuaValue.valueOf("inventory"))
        t.rawset("__pxrp_object", LuaValue.userdataOf(inv))
        return t
    }

    private fun unlock(inv: SimpleInventory, action: () -> Unit) {
        if (inv is LockableInventory) {
            inv.unlocked { action() }
        } else {
            action()
        }
    }

    private val BUILT = metaTable<SimpleInventory> {
        prop("size") { LuaValue.valueOf(size()) }

        method("getItem") { args ->
            val self = args.arg(1).checktable()
            val inv = self.unwrap<SimpleInventory>()
            val slot = args.arg(2).checkint() - 1
            if (slot < 0 || slot >= inv.size()) return@method LuaValue.NIL
            val stack = inv.getStack(slot)
            if (stack.isEmpty) LuaValue.NIL else ItemStackWrap.wrap(stack)
        }

        method("setItem") { args ->
            val self = args.arg(1).checktable()
            val inv = self.unwrap<SimpleInventory>()
            val slot = args.arg(2).checkint() - 1
            if (slot < 0 || slot >= inv.size()) return@method LuaValue.NIL
            unlock(inv) {
                if (args.arg(3).isnil()) {
                    inv.setStack(slot, ItemStack.EMPTY)
                } else {
                    val stack = ItemStackWrap.unwrap(args.arg(3))
                        ?: throw LuaError("inv:setItem(): ожидается предмет")
                    inv.setStack(slot, stack)
                }
            }
            LuaValue.NIL
        }

        method("fill") { args ->
            val self = args.arg(1).checktable()
            val inv = self.unwrap<SimpleInventory>()
            val stack = if (args.arg(2).isnil()) {
                ItemStack.EMPTY
            } else {
                ItemStackWrap.unwrap(args.arg(2))
                    ?: throw LuaError("inv:fill(): ожидается предмет")
            }
            unlock(inv) {
                for (i in 0 until inv.size()) {
                    inv.setStack(i, stack.copy())
                }
            }
            LuaValue.NIL
        }

        method("clear") { args ->
            val self = args.arg(1).checktable()
            val inv = self.unwrap<SimpleInventory>()
            unlock(inv) { inv.clear() }
            LuaValue.NIL
        }

        method("open") { args ->
            val self = args.arg(1).checktable()
            val inv = self.unwrap<SimpleInventory>()
            val playerArg = args.arg(2)
            val player = playerArg.unwrap<ServerPlayerEntity>()
            val title = args.arg(3).optjstring("Container")

            val rows = inv.size() / 9
            if (inv.size() % 9 != 0 || rows !in 1..6) return@method LuaValue.NIL

            val container = ContainerManager.open(player, inv, rows, Text.literal(title))
            container.toLuaValue()
        }
    }

    fun initMeta(meta: LuaTable) {
        BUILT.apply(meta)
    }

    fun serialise(inv: SimpleInventory, lookup: RegistryWrapper.WrapperLookup): String {
        val ops = RegistryOps.of(JsonOps.INSTANCE, lookup)
        val items = JsonArray()
        for (i in 0 until inv.size()) {
            val stack = inv.getStack(i)
            val elem = if (stack.isEmpty) JsonNull.INSTANCE
            else ItemStack.CODEC.encodeStart(ops, stack).result()
                .orElseThrow { LuaError("Не удалось сериализовать слот $i") }
            items.add(elem)
        }
        val root = JsonObject()
        root.addProperty("size", inv.size())
        root.add("items", items)
        return root.toString()
    }

    fun deserialise(json: String, lookup: RegistryWrapper.WrapperLookup): LockableInventory {
        val ops = RegistryOps.of(JsonOps.INSTANCE, lookup)
        val root = JsonParser.parseString(json).asJsonObject
        val size = root.get("size").asInt
        val items = root.getAsJsonArray("items")
        val inv = LockableInventory(size)
        for (i in 0 until minOf(size, items.size())) {
            val elem = items[i]
            if (elem.isJsonNull) continue
            val stack = ItemStack.CODEC.parse(ops, elem).result()
                .orElseThrow { LuaError("Не удалось десериализовать слот $i") }
            inv.setStack(i, stack)
        }
        return inv
    }
}
