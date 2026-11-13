package ru.pyxiion.ignis.mixins;

import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.pyxiion.ignis.api.manager.DisplayManager;

@Mixin(ClientboundSetPassengersPacket.class)
public abstract class SetPassengersPacketMixin {
    @Shadow
    @Mutable
    private int[] passengers;

    @Shadow
    private int vehicle;

    // A vanilla SetPassengers replaces the client's whole passenger list: virtual displays attached to this vehicle
    // must be part of every such packet or the next real mount/dismount would knock them off.
    @Inject(method = "<init>(Lnet/minecraft/world/entity/Entity;)V", at = @At("RETURN"))
    private void pxrp$addVirtualPassengers(Entity vehicleEntity, CallbackInfo ci) {
        int[] extra = DisplayManager.passengerIds(vehicle);
        if (extra == null) return;
        int[] merged = new int[passengers.length + extra.length];
        System.arraycopy(passengers, 0, merged, 0, passengers.length);
        System.arraycopy(extra, 0, merged, passengers.length, extra.length);
        passengers = merged;
    }
}
