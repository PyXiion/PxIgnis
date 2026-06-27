package ru.pyxiion.ignis.client

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.context.CommandContext
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.command.CommandRegistryAccess
import net.minecraft.text.Text
import ru.pyxiion.ignis.network.RegionInterestPayload

object ClientCommands {
    fun register() {
        ClientCommandRegistrationCallback.EVENT.register(ClientCommands::registerCallback)
    }

    private fun registerCallback(
        dispatcher: CommandDispatcher<FabricClientCommandSource>,
        registryAccess: CommandRegistryAccess
    ) {
        dispatcher.register(
            ClientCommandManager.literal("ignis")
                .then(
                    ClientCommandManager.literal("debug")
                        .then(
                            ClientCommandManager.literal("regions")
                                .executes(::toggleRegions)
                        )
                )
        )
    }

    private fun toggleRegions(context: CommandContext<FabricClientCommandSource>): Int {
        val source = context.source
        val player = source.player ?: return 0

        val newValue = !ClientRegionRegistry.enabled
        ClientRegionRegistry.setEnabled(newValue)
        if (!newValue) {
            ClientRegionRegistry.clear()
        }
        ClientPlayNetworking.send(RegionInterestPayload(newValue))
        source.sendFeedback(
            Text.literal("Region debug overlay: ${if (newValue) "on" else "off"}")
        )
        return 1
    }
}
