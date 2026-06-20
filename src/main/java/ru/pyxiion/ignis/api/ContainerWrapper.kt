package ru.pyxiion.ignis.api

import net.minecraft.inventory.SimpleInventory
import net.minecraft.screen.ScreenHandler
import net.minecraft.server.network.ServerPlayerEntity
import org.luaj.vm2.LuaFunction
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import ru.pyxiion.ignis.unwrap


class ContainerWrapper(
    val player: ServerPlayerEntity,
    val inventory: SimpleInventory,
    val screenHandler: ScreenHandler
) {
    var onClickCallback: LuaFunction? = null

    fun toLuaValue(): LuaValue {
        val t = LuaTable()
        t.setmetatable(MetaTableRegistry.CONTAINER)
        t.rawset("__pxrp_type", LuaValue.valueOf("container"))
        t.rawset("__pxrp_object", LuaValue.userdataOf(this))
        return t
    }

    companion object {
        private val BUILT = metaTable<ContainerWrapper> {
            prop("player") { PlayerWrap.wrap(player) }
            prop("inventory") { InvWrap.wrap(inventory) }

            method("close") { args ->
                val self = args.arg(1).checktable()
                ContainerManager.close(self.unwrap<ContainerWrapper>().screenHandler)
                LuaValue.NIL
            }

            method("onClick") { args ->
                val self = args.arg(1).checktable()
                val wrapper = self.unwrap<ContainerWrapper>()
                val newCb = if (args.arg(2).isnil()) null else args.arg(2).checkfunction()
                wrapper.onClickCallback = newCb
                if (wrapper.inventory is LockableInventory) {
                    wrapper.inventory.locked = (newCb != null)
                }
                LuaValue.NIL
            }
        }

        fun initMeta(meta: LuaTable) {
            BUILT.apply(meta)
        }
    }
}
