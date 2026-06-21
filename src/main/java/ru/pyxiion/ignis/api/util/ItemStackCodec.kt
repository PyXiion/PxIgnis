package ru.pyxiion.ignis.api.util

import com.google.gson.JsonParser
import com.mojang.serialization.JsonOps
import net.minecraft.item.ItemStack
import net.minecraft.registry.RegistryOps
import net.minecraft.registry.RegistryWrapper
import org.luaj.vm2.LuaError

object ItemStackCodec {
    fun encode(stack: ItemStack, lookup: RegistryWrapper.WrapperLookup): String {
        val ops = RegistryOps.of(JsonOps.INSTANCE, lookup)
        return ItemStack.CODEC.encodeStart(ops, stack)
            .result()
            .orElseThrow { LuaError("Не удалось сериализовать предмет") }
            .toString()
    }

    fun decode(json: String, lookup: RegistryWrapper.WrapperLookup): ItemStack {
        val ops = RegistryOps.of(JsonOps.INSTANCE, lookup)
        return ItemStack.CODEC.parse(ops, JsonParser.parseString(json))
            .result()
            .orElseThrow { LuaError("Не удалось десериализовать предмет") }
    }
}
