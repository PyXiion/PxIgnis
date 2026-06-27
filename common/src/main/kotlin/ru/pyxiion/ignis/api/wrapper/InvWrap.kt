package ru.pyxiion.ignis.api.wrapper

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojang.serialization.JsonOps
import net.minecraft.world.SimpleContainer
import net.minecraft.world.item.ItemStack
import net.minecraft.resources.RegistryOps
import net.minecraft.core.HolderLookup
import net.minecraft.server.level.ServerPlayer
import net.minecraft.network.chat.Component
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import ru.pyxiion.ignis.api.MetaTableRegistry
import ru.pyxiion.ignis.api.manager.ContainerManager
import ru.pyxiion.ignis.api.manager.LockableInventory
import ru.pyxiion.ignis.api.util.metaTable
import ru.pyxiion.ignis.unwrap

object InvWrap {

    fun wrap(inv: SimpleContainer): LuaValue {
        val t = LuaTable()
        t.setmetatable(MetaTableRegistry.INVENTORY)
        t.rawset("__pxrp_type", LuaValue.valueOf("inventory"))
        t.rawset("__pxrp_object", LuaValue.userdataOf(inv))
        return t
    }

    private fun unlock(inv: SimpleContainer, action: () -> Unit) {
        if (inv is LockableInventory) {
            inv.unlocked { action() }
        } else {
            action()
        }
    }

    private val BUILT = metaTable<SimpleContainer> {
        prop("size") { LuaValue.valueOf(containerSize) }

        method("getItem") { args ->
            val self = args.arg(1).checktable()
            val inv = self.unwrap<SimpleContainer>()
            val slot = args.arg(2).checkint() - 1
            if (slot < 0 || slot >= inv.containerSize) return@method LuaValue.NIL
            val stack = inv.getItem(slot)
            if (stack.isEmpty) LuaValue.NIL else ItemStackWrap.wrap(stack)
        }

        method("setItem") { args ->
            val self = args.arg(1).checktable()
            val inv = self.unwrap<SimpleContainer>()
            val slot = args.arg(2).checkint() - 1
            if (slot < 0 || slot >= inv.containerSize) return@method LuaValue.NIL
            unlock(inv) {
                if (args.arg(3).isnil()) {
                    inv.setItem(slot, ItemStack.EMPTY)
                } else {
                    val stack = ItemStackWrap.unwrap(args.arg(3))
                        ?: throw LuaError("inv:setItem(): ожидается предмет")
                    inv.setItem(slot, stack)
                }
            }
            LuaValue.NIL
        }

        method("fill") { args ->
            val self = args.arg(1).checktable()
            val inv = self.unwrap<SimpleContainer>()
            val stack = if (args.arg(2).isnil()) {
                ItemStack.EMPTY
            } else {
                ItemStackWrap.unwrap(args.arg(2))
                    ?: throw LuaError("inv:fill(): ожидается предмет")
            }
            unlock(inv) {
                for (i in 0 until inv.containerSize) {
                    inv.setItem(i, stack.copy())
                }
            }
            LuaValue.NIL
        }

        method("clear") { args ->
            val self = args.arg(1).checktable()
            val inv = self.unwrap<SimpleContainer>()
            unlock(inv) { inv.clearContent() }
            LuaValue.NIL
        }

        method("open") { args ->
            val self = args.arg(1).checktable()
            val inv = self.unwrap<SimpleContainer>()
            val playerArg = args.arg(2)
            val player = playerArg.unwrap<ServerPlayer>()
            val title = args.arg(3).optjstring("Container")

            val rows = inv.containerSize / 9
            if (inv.containerSize % 9 != 0 || rows !in 1..6) return@method LuaValue.NIL

            val container = ContainerManager.open(player, inv, rows, Component.literal(title))
            container.toLuaValue()
        }
    }

    fun initMeta(meta: LuaTable) {
        BUILT.apply(meta)
    }

    fun serialise(inv: SimpleContainer, lookup: HolderLookup.Provider): String {
        val ops = RegistryOps.create(JsonOps.INSTANCE, lookup)
        val items = JsonArray()
        for (i in 0 until inv.containerSize) {
            val stack = inv.getItem(i)
            val elem = if (stack.isEmpty) JsonNull.INSTANCE
            else ItemStack.CODEC.encodeStart(ops, stack).result()
                .orElseThrow { LuaError("Не удалось сериализовать слот $i") }
            items.add(elem)
        }
        val root = JsonObject()
        root.addProperty("size", inv.containerSize)
        root.add("items", items)
        return root.toString()
    }

    fun deserialise(json: String, lookup: HolderLookup.Provider): LockableInventory {
        val ops = RegistryOps.create(JsonOps.INSTANCE, lookup)
        val root = JsonParser.parseString(json).asJsonObject
        val size = root.get("size").asInt
        val items = root.getAsJsonArray("items")
        val inv = LockableInventory(size)
        for (i in 0 until minOf(size, items.size())) {
            val elem = items[i]
            if (elem.isJsonNull) continue
            val stack = ItemStack.CODEC.parse(ops, elem).result()
                .orElseThrow { LuaError("Не удалось десериализовать слот $i") }
            inv.setItem(i, stack)
        }
        return inv
    }
}
