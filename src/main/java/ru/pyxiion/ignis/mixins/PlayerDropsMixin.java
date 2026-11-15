package ru.pyxiion.ignis.mixins;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import ru.pyxiion.ignis.events.DropCapture;

@Mixin(Player.class)
public abstract class PlayerDropsMixin {
    // A player's inventory is thrown out entity by entity, not through spawnAtLocation, so entity.drops cannot list
    // it. Inside the capture window it waits instead, and runs after the event unless a handler cancelled it
    // (the keepInventory rule still applies, as the vanilla code is untouched).
    @WrapMethod(method = "dropEquipment")
    private void pxrp$holdInventory(ServerLevel level, Operation<Void> original) {
        if (!DropCapture.deferEquipment(this)) original.call(level);
    }
}
