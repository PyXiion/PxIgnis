package ru.pyxiion.ignis.api

import net.minecraft.inventory.SimpleInventory
import net.minecraft.screen.ScreenHandler
import net.minecraft.server.network.ServerPlayerEntity
import org.luaj.vm2.LuaFunction
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.lib.jse.CoerceJavaToLua

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
        t.rawset("__pxrp_object", CoerceJavaToLua.coerce(this))
        return t
    }

    companion object {
        private val containerKeys = listOf("player", "inventory")

        fun initMeta(meta: LuaTable) {
            meta.set("__index", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val key = args.arg(2).tojstring()
                    val wrapper = self.rawget("__pxrp_object").checkuserdata() as ContainerWrapper

                    return when (key) {
                        "player" -> PlayerWrapper(wrapper.player).toLuaValue()
                        "inventory" -> InvWrapper(wrapper.inventory).toLuaValue()
                        else -> meta.get(key)
                    }
                }
            })

            meta.set("__pairs", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1)
                    val keys = containerKeys
                    val iterator = object : VarArgFunction() {
                        private var index = 0
                        override fun invoke(args: Varargs): Varargs {
                            if (index >= keys.size) return LuaValue.NIL
                            val key = keys[index]
                            index++
                            val value = self.get(key)
                            return LuaValue.varargsOf(arrayOf(LuaValue.valueOf(key), value))
                        }
                    }
                    return LuaValue.varargsOf(arrayOf(iterator, self, LuaValue.NIL))
                }
            })

            meta.rawset("close", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val wrapper = self.rawget("__pxrp_object").checkuserdata() as ContainerWrapper
                    ContainerManager.close(wrapper.screenHandler)
                    return LuaValue.NIL
                }
            })

            meta.rawset("onClick", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val wrapper = self.rawget("__pxrp_object").checkuserdata() as ContainerWrapper
                    val newCb = if (args.arg(2).isnil()) null else args.arg(2).checkfunction()
                    wrapper.onClickCallback = newCb
                    if (wrapper.inventory is LockableInventory) {
                        wrapper.inventory.locked = (newCb != null)
                    }
                    return LuaValue.NIL
                }
            })
        }
    }
}
