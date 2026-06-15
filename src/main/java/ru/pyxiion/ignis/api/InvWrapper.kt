package ru.pyxiion.ignis.api

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
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.lib.jse.CoerceJavaToLua

class InvWrapper(private val inv: SimpleInventory) {
    fun toLuaValue(): LuaValue {
        val t = LuaTable()
        t.setmetatable(MetaTableRegistry.INVENTORY)
        t.rawset("__pxrp_type", LuaValue.valueOf("inventory"))
        t.rawset("__pxrp_object", CoerceJavaToLua.coerce(inv))
        return t
    }

    companion object {
        private val inventoryKeys = listOf("size")

        private fun unlock(inv: SimpleInventory, action: () -> Unit) {
            if (inv is LockableInventory) {
                inv.unlocked { action() }
            } else {
                action()
            }
        }

        fun initMeta(meta: LuaTable) {
            meta.set("__index", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val key = args.arg(2).tojstring()
                    val inv = self.rawget("__pxrp_object").checkuserdata() as SimpleInventory

                    return when (key) {
                        "size" -> LuaValue.valueOf(inv.size())
                        else -> meta.get(key)
                    }
                }
            })

            meta.set("__pairs", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1)
                    val keys = inventoryKeys
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

            meta.rawset("getItem", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val inv = self.rawget("__pxrp_object").checkuserdata() as SimpleInventory
                    val slot = args.arg(2).checkint() - 1
                    if (slot < 0 || slot >= inv.size()) return LuaValue.NIL
                    val stack = inv.getStack(slot)
                    return if (stack.isEmpty) LuaValue.NIL else ItemStackWrapper.wrap(stack)
                }
            })

            meta.rawset("setItem", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val inv = self.rawget("__pxrp_object").checkuserdata() as SimpleInventory
                    val slot = args.arg(2).checkint() - 1
                    if (slot < 0 || slot >= inv.size()) return LuaValue.NIL
                    unlock(inv) {
                        if (args.arg(3).isnil()) {
                            inv.setStack(slot, ItemStack.EMPTY)
                        } else {
                            val stack = ItemStackWrapper.unwrap(args.arg(3))
                                ?: throw LuaError("inv:setItem(): ожидается предмет")
                            inv.setStack(slot, stack)
                        }
                    }
                    return LuaValue.NIL
                }
            })

            meta.rawset("fill", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val inv = self.rawget("__pxrp_object").checkuserdata() as SimpleInventory
                    val stack = if (args.arg(2).isnil()) {
                        ItemStack.EMPTY
                    } else {
                        ItemStackWrapper.unwrap(args.arg(2))
                            ?: throw LuaError("inv:fill(): ожидается предмет")
                    }
                    unlock(inv) {
                        for (i in 0 until inv.size()) {
                            inv.setStack(i, stack.copy())
                        }
                    }
                    return LuaValue.NIL
                }
            })

            meta.rawset("clear", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val inv = self.rawget("__pxrp_object").checkuserdata() as SimpleInventory
                    unlock(inv) { inv.clear() }
                    return LuaValue.NIL
                }
            })

            meta.rawset("open", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val inv = self.rawget("__pxrp_object").checkuserdata() as SimpleInventory
                    val playerArg = args.arg(2)
                    val player = playerArg.rawget("__pxrp_object").checkuserdata() as ServerPlayerEntity
                    val title = args.arg(3).optjstring("Container")

                    val rows = inv.size() / 9
                    if (inv.size() % 9 != 0 || rows !in 1..6) return LuaValue.NIL

                    val container = ContainerManager.open(player, inv, rows, Text.literal(title))
                    return container.toLuaValue()
                }
            })
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
}
