package ru.pyxiion.ignis.api.manager

import net.minecraft.world.MenuProvider
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.ClickType
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.item.ItemStack
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import org.luaj.vm2.LuaValue
import ru.pyxiion.ignis.api.wrapper.ContainerWrapper
import ru.pyxiion.ignis.api.wrapper.ItemStackWrap
import ru.pyxiion.ignis.api.wrapper.PlayerWrap

class LockableInventory(size: Int) : SimpleContainer(size) {
    var locked = false

    inline fun <T> unlocked(block: () -> T): T {
        val was = locked
        locked = false
        try { return block() } finally { locked = was }
    }

    override fun removeItemNoUpdate(slot: Int): ItemStack {
        if (locked) return ItemStack.EMPTY
        return super.removeItemNoUpdate(slot)
    }

    override fun removeItem(slot: Int, amount: Int): ItemStack {
        if (locked) return ItemStack.EMPTY
        return super.removeItem(slot, amount)
    }

    override fun setItem(slot: Int, stack: ItemStack) {
        super.setItem(slot, stack)
    }

    override fun clearContent() {
        if (locked) return
        super.clearContent()
    }
}

object ContainerManager {
    private val containers = mutableMapOf<AbstractContainerMenu, ContainerWrapper>()

    fun open(
        player: ServerPlayer,
        inventory: SimpleContainer,
        rows: Int,
        title: Component
    ): ContainerWrapper {
        require(rows in 1..6)

        val type: MenuType<*> = when (rows) {
            1 -> MenuType.GENERIC_9x1
            2 -> MenuType.GENERIC_9x2
            3 -> MenuType.GENERIC_9x3
            4 -> MenuType.GENERIC_9x4
            5 -> MenuType.GENERIC_9x5
            6 -> MenuType.GENERIC_9x6
            else -> throw IllegalArgumentException()
        }

        var screenHandler: AbstractContainerMenu? = null
        val factory = object : MenuProvider {
            override fun getDisplayName(): Component = title
            override fun createMenu(containerId: Int, inv: Inventory, player: Player): AbstractContainerMenu {
                return ChestMenu(type, containerId, inv, inventory, rows).also {
                    screenHandler = it
                }
            }
        }

        player.openMenu(factory)
        val sh = screenHandler!!
        val wrapper = ContainerWrapper(player, inventory, sh)
        containers[sh] = wrapper
        return wrapper
    }

    fun close(sh: AbstractContainerMenu) {
        val wrapper = containers.remove(sh) ?: return
        wrapper.player.closeContainer()
    }

    fun onScreenClosed(sh: AbstractContainerMenu) {
        containers.remove(sh)
    }

    fun closeAll(player: ServerPlayer) {
        if (containers.any { it.value.player == player }) {
            containers.entries.removeIf { it.value.player == player }
            player.closeContainer()
        }
    }

    fun closeAll() {
        containers.keys.toList().forEach { sh ->
            val wrapper = containers[sh] ?: return@forEach
            wrapper.player.closeContainer()
        }
        containers.clear()
    }

    fun shouldAllowClick(
        sh: AbstractContainerMenu,
        slot: Int,
        button: Int,
        actionType: ClickType,
        player: ServerPlayer
    ): Boolean {
        val wrapper = containers[sh] ?: return true
        val cb = wrapper.onClickCallback ?: return true

        if (slot < 0 || slot >= wrapper.inventory.containerSize) return true

        val luaPlayer = PlayerWrap.wrap(player)
        val slotStack = wrapper.inventory.getItem(slot)
        val luaItem = if (slotStack.isEmpty) LuaValue.NIL else ItemStackWrap.wrap(slotStack)

        val carried = player.containerMenu.carried
        val luaCursor = if (carried.isEmpty) LuaValue.NIL else ItemStackWrap.wrap(carried.copy())

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
