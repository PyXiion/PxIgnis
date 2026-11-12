package ru.pyxiion.ignis.api.wrapper

import ru.pyxiion.ignis.wrapObject
import net.minecraft.world.SimpleContainer
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.server.level.ServerPlayer
import org.luaj.vm2.LuaFunction
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import ru.pyxiion.ignis.api.MetaTableRegistry
import ru.pyxiion.ignis.api.manager.ContainerManager
import ru.pyxiion.ignis.api.util.metaTable
import ru.pyxiion.ignis.unwrap


class ContainerWrapper(
    val manager: ContainerManager,
    val player: ServerPlayer,
    val inventory: SimpleContainer,
    val screenHandler: AbstractContainerMenu
) {
    var onClickCallback: LuaFunction? = null

    fun toLuaValue(): LuaValue {
        return wrapObject(MetaTableRegistry.CONTAINER, "container", this)
    }

    companion object {
        private val BUILT = metaTable<ContainerWrapper> {
            lazy("player") { PlayerWrap.wrap(player) }
            lazy("inventory") { InvWrap.wrap(inventory, manager) }

            method("close") { args ->
                val self = args.arg(1).checktable()
                self.unwrap<ContainerWrapper>().let { it.manager.close(it.screenHandler) }
                LuaValue.NIL
            }

            method("onClick") { args ->
                val self = args.arg(1).checktable()
                val wrapper = self.unwrap<ContainerWrapper>()
                val newCb = if (args.arg(2).isnil()) null else args.arg(2).checkfunction()
                wrapper.onClickCallback = newCb
                LuaValue.NIL
            }
        }

        fun initMeta(meta: LuaTable) {
            BUILT.apply(meta)
        }
    }
}
