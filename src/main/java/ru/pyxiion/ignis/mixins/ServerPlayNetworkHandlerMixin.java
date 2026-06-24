package ru.pyxiion.ignis.mixins;

import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.SetCursorItemS2CPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.luaj.vm2.LuaValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.pyxiion.ignis.PxIgnis;
import ru.pyxiion.ignis.api.wrapper.ItemStackWrap;
import ru.pyxiion.ignis.api.wrapper.PlayerWrap;

@Mixin(ServerPlayNetworkHandler.class)
public abstract class ServerPlayNetworkHandlerMixin {

    @Shadow
    public ServerPlayerEntity player;

    // player_drop_item — intercept DROP_ITEM / DROP_ALL_ITEMS (hotbar Q)
    @Inject(method = "onPlayerAction", at = @At("HEAD"), cancellable = true)
    private void pxrp$onDropItem(PlayerActionC2SPacket packet, CallbackInfo ci) {
        var action = packet.getAction();
        if (action == PlayerActionC2SPacket.Action.DROP_ITEM
            || action == PlayerActionC2SPacket.Action.DROP_ALL_ITEMS) {

            ItemStack mainStack = player.getMainHandStack();
            if (mainStack.isEmpty()) return;

            int count = action == PlayerActionC2SPacket.Action.DROP_ALL_ITEMS
                ? mainStack.getCount() : 1;

            if (fireDropEvent(mainStack, count)) {
                ci.cancel();
                var inventory = player.getInventory();
                var stack = inventory.getSelectedStack().copy();
                var handler = player.currentScreenHandler;
                player.networkHandler.sendPacket(
                    new ScreenHandlerSlotUpdateS2CPacket(
                        handler.syncId, handler.nextRevision(), 36 + inventory.getSelectedSlot(), stack
                    )
                );
            }
        }
    }

    // player_drop_item — intercept THROW + PICKUP(outside) from inventory (inventory Q)
    @Inject(method = "onClickSlot", at = @At(value = "INVOKE", target = "Lnet/minecraft/screen/ScreenHandler;getRevision()I"), cancellable = true)
    private void pxrp$onClickSlotDrop(ClickSlotC2SPacket packet, CallbackInfo ci) {
        var handler = player.currentScreenHandler;
        int syncSlot;

        if (packet.actionType() == SlotActionType.THROW) {
            int slot = packet.slot();
            if (slot == -1) {
                syncSlot = -1;
            } else {
                if (slot < 0 || slot >= handler.slots.size()) return;
                if (handler.getSlot(slot).getStack().isEmpty()) return;
                syncSlot = slot;
            }
        } else if (packet.actionType() == SlotActionType.PICKUP && packet.slot() == -999) {
            syncSlot = -1;
        } else {
            return;
        }

        ItemStack dropStack = syncSlot == -1
            ? handler.getCursorStack()
            : handler.getSlot(syncSlot).getStack();
        if (dropStack.isEmpty()) return;
        int count = packet.button() == 1 ? dropStack.getCount() : 1;

            if (fireDropEvent(dropStack, count)) {
                ci.cancel();
                if (syncSlot == -1) {
                    player.networkHandler.sendPacket(
                        new SetCursorItemS2CPacket(handler.getCursorStack().copy())
                    );
                } else {
                    player.networkHandler.sendPacket(
                        new ScreenHandlerSlotUpdateS2CPacket(handler.syncId, handler.nextRevision(), syncSlot, handler.getSlot(syncSlot).getStack().copy())
                    );
                }
            }
    }

    @Unique
    private boolean fireDropEvent(ItemStack stack, int count) {
        var results = PxIgnis.instance.runtime.getEventManager()
            .fireWithResults("player_drop_item",
                PlayerWrap.INSTANCE.wrap(player),
                ItemStackWrap.INSTANCE.wrap(stack),
                LuaValue.valueOf(count));
        return results.stream().anyMatch(r -> r.isboolean() && !r.toboolean());
    }
}
