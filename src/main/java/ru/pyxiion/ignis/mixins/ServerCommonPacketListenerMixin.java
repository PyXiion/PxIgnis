package ru.pyxiion.ignis.mixins;

import net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.pyxiion.ignis.events.GameEvents;

@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ServerCommonPacketListenerMixin {

    @Shadow
    @org.spongepowered.asm.mixin.Final
    protected MinecraftServer server;

    // player.custom_click — vanilla forwards the click to MinecraftServer.handleCustomClickAction, which has no player.
    // Game phase only. On the network thread vanilla re-queues the packet; the event runs on the second pass.
    @Inject(method = "handleCustomClickAction", at = @At("HEAD"))
    private void pxrp$onCustomClick(ServerboundCustomClickActionPacket packet, CallbackInfo ci) {
        if (!server.isSameThread()) return;
        if ((Object) this instanceof ServerGamePacketListenerImpl game) {
            GameEvents.customClick(game.player, packet.id(), packet.payload().orElse(null));
        }
    }
}
