package ru.pyxiion.ignis.api.wrapper

import ru.pyxiion.ignis.wrapObject
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
import ru.pyxiion.ignis.api.util.ItemStackCodec
import ru.pyxiion.ignis.api.util.metaTable
import ru.pyxiion.ignis.unwrap

object InvWrap {

    fun wrap(inv: SimpleContainer, containers: ContainerManager): LuaValue {
        return wrapObject(MetaTableRegistry.INVENTORY, "inventory", inv).also {
            it.rawset("__pxrp_containers", LuaValue.userdataOf(containers))
        }
    }

    /** Lua slot (1-based) to container index; an out-of-range slot is an error, not a silent no-op. */
    private fun slotIndex(inv: SimpleContainer, slot: Int, op: String): Int {
        if (slot < 1 || slot > inv.containerSize)
            throw LuaError("$op: слот $slot вне диапазона 1..${inv.containerSize}")
        return slot - 1
    }

    private val BUILT = metaTable<SimpleContainer> {
        prop("size") { LuaValue.valueOf(containerSize) }

        method("getItem") { args ->
            val self = args.arg(1).checktable()
            val inv = self.unwrap<SimpleContainer>()
            val stack = inv.getItem(slotIndex(inv, args.arg(2).checkint(), "inv:getItem()"))
            if (stack.isEmpty) LuaValue.NIL else ItemStackWrap.wrap(stack)
        }

        method("setItem") { args ->
            val self = args.arg(1).checktable()
            val inv = self.unwrap<SimpleContainer>()
            val slot = slotIndex(inv, args.arg(2).checkint(), "inv:setItem()")
            if (args.arg(3).isnil()) {
                inv.setItem(slot, ItemStack.EMPTY)
            } else {
                val stack = ItemStackWrap.unwrap(args.arg(3))
                    ?: throw LuaError("inv:setItem(): ожидается предмет")
                inv.setItem(slot, stack)
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
            for (i in 0 until inv.containerSize) {
                inv.setItem(i, stack.copy())
            }
            LuaValue.NIL
        }

        method("clear") { args ->
            val self = args.arg(1).checktable()
            self.unwrap<SimpleContainer>().clearContent()
            LuaValue.NIL
        }

        method("open") { args ->
            val self = args.arg(1).checktable()
            val inv = self.unwrap<SimpleContainer>()
            val player = args.arg(2).unwrap<ServerPlayer>()
            val title = args.arg(3).optjstring("Container")

            val rows = inv.containerSize / 9
            if (inv.containerSize % 9 != 0 || rows !in 1..6)
                throw LuaError("inv:open(): размер ${inv.containerSize} не годится для сундука (нужно 9 × 1..6)")

            val containers = self.rawget("__pxrp_containers").checkuserdata() as ContainerManager
            containers.open(player, inv, rows, Component.literal(title)).toLuaValue()
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
            val elem = if (stack.isEmpty) {
                JsonNull.INSTANCE
            } else {
                val result = ItemStack.CODEC.encodeStart(ops, stack)
                result.result().orElseThrow {
                    LuaError("Не удалось сериализовать слот ${i + 1}: ${ItemStackCodec.codecError(result)}")
                }
            }
            items.add(elem)
        }
        val root = JsonObject()
        root.addProperty("size", inv.containerSize)
        root.add("items", items)
        return root.toString()
    }

    fun deserialise(json: String, lookup: HolderLookup.Provider): SimpleContainer {
        val ops = RegistryOps.create(JsonOps.INSTANCE, lookup)
        val root = JsonParser.parseString(json).asJsonObject
        val size = root.get("size").asInt
        val items = root.getAsJsonArray("items")
        val inv = SimpleContainer(size)
        for (i in 0 until minOf(size, items.size())) {
            val elem = items[i]
            if (elem.isJsonNull) continue
            val result = ItemStack.CODEC.parse(ops, elem)
            val stack = result.result().orElseThrow {
                LuaError("Не удалось десериализовать слот ${i + 1}: ${ItemStackCodec.codecError(result)}")
            }
            inv.setItem(i, stack)
        }
        return inv
    }
}
