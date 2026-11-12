package ru.pyxiion.ignis.api.manager

import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.SimpleContainer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.MenuProvider
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.MenuType
import net.minecraft.server.level.ServerPlayer
import net.minecraft.network.chat.Component
import org.luaj.vm2.LuaValue
import ru.pyxiion.ignis.PxIgnis
import ru.pyxiion.ignis.api.wrapper.ContainerWrapper
import ru.pyxiion.ignis.api.wrapper.ItemStackWrap
import ru.pyxiion.ignis.api.wrapper.PlayerWrap
import ru.pyxiion.ignis.runtime.ScriptErrors
import ru.pyxiion.ignis.runtime.ScriptWatchdog

/** Открытые GUI-меню. Принадлежит [ru.pyxiion.ignis.IgnisRuntime]; обёртки хранят ссылку на своего владельца. */
class ContainerManager {
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
            override fun createMenu(syncId: Int, inv: Inventory, player: Player): AbstractContainerMenu {
                return ChestMenu(type, syncId, inv, inventory, rows).also {
                    screenHandler = it
                }
            }
        }

        player.openMenu(factory)
        val sh = screenHandler!!
        val wrapper = ContainerWrapper(this, player, inventory, sh)
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

    /**
     * Every click in a menu that has an `onClick` callback is shown to it: GUI slots, the player's own inventory
     * slots (menu slot numbers, `inGui = false`) and clicks outside the window (`slot = nil`). The click goes
     * through only if the callback returns `true`; `nil`, `false` or a Lua error cancel it.
     * A menu without a callback behaves like a vanilla chest.
     */
    fun shouldAllowClick(
        sh: AbstractContainerMenu,
        slot: Int,
        button: Int,
        actionType: Enum<*>,
        player: ServerPlayer
    ): Boolean {
        val wrapper = containers[sh] ?: return true
        val cb = wrapper.onClickCallback ?: return true

        val inWindow = slot >= 0 && slot < sh.slots.size
        val inGui = slot >= 0 && slot < wrapper.inventory.containerSize

        val luaSlot = if (inWindow) LuaValue.valueOf(slot + 1) else LuaValue.NIL
        val slotStack = if (inWindow) sh.getSlot(slot).item else ItemStack.EMPTY
        val luaItem = if (slotStack.isEmpty) LuaValue.NIL else ItemStackWrap.wrap(slotStack.copy())
        val cursorStack = sh.carried
        val luaCursor = if (cursorStack.isEmpty) LuaValue.NIL else ItemStackWrap.wrap(cursorStack.copy())

        val allowed = try {
            val result = ScriptWatchdog.guard {
                cb.invoke(LuaValue.varargsOf(arrayOf(
                    PlayerWrap.wrap(player),
                    luaSlot,
                    LuaValue.valueOf(actionType.name.lowercase()),
                    luaItem,
                    luaCursor,
                    LuaValue.valueOf(inGui),
                )))
            }
            result.arg1() == LuaValue.TRUE
        } catch (e: Throwable) {
            ScriptErrors.error("container click", e)
            false
        }

        // The client has already predicted the click; put it back in sync with the untouched server state.
        if (!allowed) sh.sendAllDataToRemote()
        return allowed
    }
}
