package ru.pyxiion.ignis.api.util

import com.google.gson.JsonParser
import com.mojang.serialization.Codec
import com.mojang.serialization.JsonOps
import net.minecraft.world.item.ItemStack
import net.minecraft.core.RegistryAccess
import net.minecraft.resources.RegistryOps
import org.luaj.vm2.LuaError

object ItemStackCodec {
    fun encode(stack: ItemStack, lookup: RegistryAccess.Frozen): String {
        val ops = RegistryOps.create(JsonOps.INSTANCE, lookup)
        return (ItemStack.CODEC as Codec<ItemStack>).encodeStart(ops, stack)
            .result()
            .orElseThrow { LuaError("Не удалось сериализовать предмет") }
            .toString()
    }

    fun decode(json: String, lookup: RegistryAccess.Frozen): ItemStack {
        val ops = RegistryOps.create(JsonOps.INSTANCE, lookup)
        return (ItemStack.CODEC as Codec<ItemStack>).parse(ops, JsonParser.parseString(json))
            .result()
            .orElseThrow { LuaError("Не удалось десериализовать предмет") }
    }
}
