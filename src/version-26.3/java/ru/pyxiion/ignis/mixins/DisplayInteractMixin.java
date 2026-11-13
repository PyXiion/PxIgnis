package ru.pyxiion.ignis.mixins;

import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.pyxiion.ignis.api.manager.DisplayManager;

// Clicks on virtual display entities (interaction hitboxes). Vanilla would look the id up, find nothing and ignore
// the packet; here it goes to the display's callbacks. 26.x sends attacks as their own packet.
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class DisplayInteractMixin {
    @Shadow
    public ServerPlayer player;

    @Inject(method = "handleInteract", at = @At("HEAD"), cancellable = true)
    private void pxrp$virtualInteract(ServerboundInteractPacket packet, CallbackInfo ci) {
        if (!DisplayManager.isVirtual(packet.entityId())) return;
        // Network thread: requeues the packet to the server thread and throws; the second pass continues here.
        PacketUtils.ensureRunningOnSameThread(packet, (ServerGamePacketListenerImpl) (Object) this, player.level());
        DisplayManager.onInteract(player, packet.entityId(), packet.hand());
        ci.cancel();
    }

    @Inject(method = "handleAttack", at = @At("HEAD"), cancellable = true)
    private void pxrp$virtualAttack(ServerboundAttackPacket packet, CallbackInfo ci) {
        if (!DisplayManager.isVirtual(packet.entityId())) return;
        PacketUtils.ensureRunningOnSameThread(packet, (ServerGamePacketListenerImpl) (Object) this, player.level());
        DisplayManager.onInteract(player, packet.entityId(), null);
        ci.cancel();
    }
}
