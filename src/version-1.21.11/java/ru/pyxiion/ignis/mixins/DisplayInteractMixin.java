package ru.pyxiion.ignis.mixins;

import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.pyxiion.ignis.api.manager.DisplayManager;

// Clicks on virtual display entities (interaction hitboxes). Vanilla would look the id up, find nothing and ignore
// the packet; here it goes to the display's callbacks. On 1.21.x attacks and interactions share one packet.
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class DisplayInteractMixin {
    @Shadow
    public ServerPlayer player;

    @Inject(method = "handleInteract", at = @At("HEAD"), cancellable = true)
    private void pxrp$virtualInteract(ServerboundInteractPacket packet, CallbackInfo ci) {
        int id = ((ServerboundInteractPacket) packet).entityId;
        if (!DisplayManager.isVirtual(id)) return;
        // Network thread: requeues the packet to the server thread and throws; the second pass continues here.
        PacketUtils.ensureRunningOnSameThread(packet, (ServerGamePacketListenerImpl) (Object) this, player.level());
        packet.dispatch(new ServerboundInteractPacket.Handler() {
            @Override
            public void onInteraction(InteractionHand hand) {
                DisplayManager.onInteract(player, id, hand);
            }

            @Override
            public void onInteraction(InteractionHand hand, Vec3 location) {
                DisplayManager.onInteract(player, id, hand);
            }

            @Override
            public void onAttack() {
                DisplayManager.onInteract(player, id, null);
            }
        });
        ci.cancel();
    }
}
