package ru.pyxiion.ignis.api.util

import com.google.gson.JsonParser
import com.mojang.serialization.DataResult
import com.mojang.serialization.JsonOps
import net.minecraft.world.item.ItemStack
import net.minecraft.resources.RegistryOps
import net.minecraft.core.HolderLookup
import org.luaj.vm2.LuaError

object ItemStackCodec {
    fun encode(stack: ItemStack, lookup: HolderLookup.Provider): String {
        val ops = RegistryOps.create(JsonOps.INSTANCE, lookup)
        val result = ItemStack.CODEC.encodeStart(ops, stack)
        return result.result()
            .orElseThrow { LuaError("Не удалось сериализовать предмет: ${codecError(result)}") }
            .toString()
    }

    fun decode(json: String, lookup: HolderLookup.Provider): ItemStack {
        val ops = RegistryOps.create(JsonOps.INSTANCE, lookup)
        val result = ItemStack.CODEC.parse(ops, JsonParser.parseString(json))
        return result.result()
            .orElseThrow { LuaError("Не удалось десериализовать предмет: ${codecError(result)}") }
    }

    /** The codec's own message, so a script sees why an item was rejected. */
    fun codecError(result: DataResult<*>): String = result.error().map { it.message() }.orElse("неизвестная ошибка")
}
