package ru.pyxiion.ignis.api

import net.minecraft.entity.player.PlayerEntity
import net.minecraft.entity.player.PlayerInventory
import net.minecraft.inventory.SimpleInventory
import net.minecraft.item.ItemStack
import net.minecraft.screen.GenericContainerScreenHandler
import net.minecraft.screen.NamedScreenHandlerFactory
import net.minecraft.screen.ScreenHandler
import net.minecraft.screen.ScreenHandlerType
import net.minecraft.screen.slot.SlotActionType
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import org.luaj.vm2.LuaValue

class LockableInventory(size: Int) : SimpleInventory(size) {
    var locked = false

    inline fun <T> unlocked(block: () -> T): T {
        val was = locked
        locked = false
        try { return block() } finally { locked = was }
    }

    override fun removeStack(slot: Int): ItemStack {
        if (locked) return ItemStack.EMPTY
        return super.removeStack(slot)
    }

    override fun removeStack(slot: Int, amount: Int): ItemStack {
        if (locked) return ItemStack.EMPTY
        return super.removeStack(slot, amount)
    }

    override fun setStack(slot: Int, stack: ItemStack) {
        super.setStack(slot, stack)
    }

    override fun clear() {
        if (locked) return
        super.clear()
    }
}

object ContainerManager {
    private val containers = mutableMapOf<ScreenHandler, ContainerWrapper>()

    fun open(
        player: ServerPlayerEntity,
        inventory: SimpleInventory,
        rows: Int,
        title: Text
    ): ContainerWrapper {
        require(rows in 1..6)

        val type: ScreenHandlerType<*> = when (rows) {
            1 -> ScreenHandlerType.GENERIC_9X1
            2 -> ScreenHandlerType.GENERIC_9X2
            3 -> ScreenHandlerType.GENERIC_9X3
            4 -> ScreenHandlerType.GENERIC_9X4
            5 -> ScreenHandlerType.GENERIC_9X5
            6 -> ScreenHandlerType.GENERIC_9X6
            else -> throw IllegalArgumentException()
        }

        var screenHandler: ScreenHandler? = null
        val factory = object : NamedScreenHandlerFactory {
            override fun getDisplayName(): Text = title
            override fun createMenu(syncId: Int, inv: PlayerInventory, player: PlayerEntity): ScreenHandler {
                return GenericContainerScreenHandler(type, syncId, inv, inventory, rows).also {
                    screenHandler = it
                }
            }
        }

        player.openHandledScreen(factory)
        val sh = screenHandler!!
        val wrapper = ContainerWrapper(player, inventory, sh)
        containers[sh] = wrapper
        return wrapper
    }

    fun close(sh: ScreenHandler) {
        val wrapper = containers.remove(sh) ?: return
        wrapper.player.closeHandledScreen()
    }

    fun onScreenClosed(sh: ScreenHandler) {
        containers.remove(sh)
    }

    fun closeAll(player: ServerPlayerEntity) {
        if (containers.any { it.value.player == player }) {
            containers.entries.removeIf { it.value.player == player }
            player.closeHandledScreen()
        }
    }

    fun closeAll() {
        containers.keys.toList().forEach { sh ->
            val wrapper = containers[sh] ?: return@forEach
            wrapper.player.closeHandledScreen()
        }
        containers.clear()
    }

    fun shouldAllowClick(
        sh: ScreenHandler,
        slot: Int,
        button: Int,
        actionType: SlotActionType,
        player: ServerPlayerEntity
    ): Boolean {
        val wrapper = containers[sh] ?: return true
        val cb = wrapper.onClickCallback ?: return true

        if (slot < 0 || slot >= wrapper.inventory.size()) return true

        val luaPlayer = PlayerWrapper(player).toLuaValue()
        val slotStack = wrapper.inventory.getStack(slot)
        val luaItem = if (slotStack.isEmpty) LuaValue.NIL else ItemStackWrapper.wrap(slotStack)

        val cursorStack = player.currentScreenHandler.cursorStack
        val luaCursor = if (cursorStack.isEmpty) LuaValue.NIL else ItemStackWrapper.wrap(cursorStack.copy())

        val result = cb.invoke(LuaValue.varargsOf(arrayOf(
            luaPlayer,
            LuaValue.valueOf(slot + 1),
            LuaValue.valueOf(actionType.name.lowercase()),
            luaItem,
            luaCursor
        )))

        val firstResult = result.arg1()
        if (firstResult.isboolean() && !firstResult.toboolean())
            return false
        return true
    }
}
