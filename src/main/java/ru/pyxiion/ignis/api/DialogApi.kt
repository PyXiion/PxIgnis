package ru.pyxiion.ignis.api

import com.google.gson.JsonElement
import com.mojang.brigadier.exceptions.CommandSyntaxException
import com.mojang.serialization.JsonOps
import net.minecraft.core.Holder
import net.minecraft.core.HolderLookup
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.Tag
import net.minecraft.network.protocol.common.ClientboundClearDialogPacket
import net.minecraft.resources.Identifier
import net.minecraft.resources.RegistryOps
import net.minecraft.resources.ResourceKey
import net.minecraft.server.dialog.Dialog
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import ru.pyxiion.luanova.vm2.LuaError
import ru.pyxiion.luanova.vm2.LuaTable
import ru.pyxiion.luanova.vm2.LuaValue
import ru.pyxiion.luanova.vm2.Varargs
import ru.pyxiion.ignis.api.util.ItemStackCodec
import ru.pyxiion.ignis.api.wrapper.ItemStackWrap
import ru.pyxiion.ignis.storage.LuaJson

/** Raw dialog API of `Player` (`showDialog`, `closeDialog`, `runCommand`). The convenient layer is `core:dialog`. */
object DialogApi {
    /**
     * Shows [spec] to [player]: a registry dialog id, or a table in vanilla dialog JSON shape
     * (an `Item` is accepted wherever an item stack is expected).
     */
    fun show(player: ServerPlayer, spec: LuaValue) {
        val holder: Holder<Dialog> = when {
            spec.isstring() -> {
                val id = Identifier.tryParse(spec.tojstring())
                    ?: throw LuaError("showDialog: '${spec.tojstring()}' is not a valid dialog id")
                player.registryAccess().lookupOrThrow(Registries.DIALOG).get(ResourceKey.create(Registries.DIALOG, id))
                    .orElseThrow { LuaError("showDialog: dialog '$id' not found") }
            }
            spec.istable() -> Holder.direct(decode(player.registryAccess(), spec.checktable()))
            else -> throw LuaError("showDialog: expected a dialog id or a table, got ${spec.typename()}")
        }
        player.openDialog(holder)
    }

    /** Decodes [spec] like [show] does, without showing it: raises the codec's error if the game would reject it. */
    fun check(registries: HolderLookup.Provider, spec: LuaTable) {
        decode(registries, spec)
    }

    private fun decode(registries: HolderLookup.Provider, spec: LuaTable): Dialog {
        val ops = RegistryOps.create(JsonOps.INSTANCE, registries)
        val json = LuaJson.toJson(spec, "dialog") { t -> itemJson(ops, t) }
        val result = Dialog.DIRECT_CODEC.parse(ops, json)
        return result.result().orElseThrow { LuaError("showDialog: ${ItemStackCodec.codecError(result)}") }
    }

    private fun itemJson(ops: RegistryOps<JsonElement>, t: LuaTable): JsonElement? {
        val stack = ItemStackWrap.unwrap(t) ?: return null
        val result = ItemStack.CODEC.encodeStart(ops, stack)
        return result.result().orElseThrow { LuaError("showDialog: ${ItemStackCodec.codecError(result)}") }
    }

    fun close(player: ServerPlayer) {
        player.connection.send(ClientboundClearDialogPacket.INSTANCE)
    }

    /** Runs [cmd] as the player: their own permissions, position and rotation. `true, result` or `false, message`. */
    fun runCommand(player: ServerPlayer, cmd: String): Varargs {
        val server = player.level().server
        return try {
            val result = server.commands.dispatcher.execute(cmd.removePrefix("/"), player.createCommandSourceStack())
            LuaValue.varargsOf(LuaValue.TRUE, LuaValue.valueOf(result))
        } catch (e: CommandSyntaxException) {
            LuaValue.varargsOf(LuaValue.FALSE, LuaValue.valueOf(e.message ?: "Syntax error"))
        } catch (e: Exception) {
            LuaValue.varargsOf(LuaValue.FALSE, LuaValue.valueOf(e.message ?: "Unknown error"))
        }
    }

    /** A click payload as plain Lua values: compound -> table, list -> array, numbers and strings as they are. */
    fun payloadToLua(tag: Tag): LuaValue = LuaJson.fromJson(NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, tag))
}
