package ru.pyxiion.ignis.mixins;

import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSetCursorItemPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
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

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerPlayNetworkHandlerMixin {

    @Shadow
    public ServerPlayer player;

    // player_drop_item — intercept DROP_ITEM / DROP_ALL_ITEMS (hotbar Q)
    @Inject(method = "handlePlayerAction", at = @At("HEAD"), cancellable = true)
    private void pxrp$onDropItem(ServerboundPlayerActionPacket packet, CallbackInfo ci) {
        var action = packet.getAction();
        if (action == ServerboundPlayerActionPacket.Action.DROP_ITEM
            || action == ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS) {

            ItemStack mainStack = player.getMainHandItem();
            if (mainStack.isEmpty()) return;

            int count = action == ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS
                ? mainStack.getCount() : 1;

            if (fireDropEvent(mainStack, count)) {
                ci.cancel();
                var inventory = player.getInventory();
                var stack = inventory.getSelectedItem().copy();
                var handler = player.containerMenu;
                player.connection.send(
                    new ClientboundContainerSetSlotPacket(
                        handler.containerId, handler.incrementStateId(), 36 + inventory.getSelectedSlot(), stack
                    )
                );
            }
        }
    }

    // player_drop_item — intercept THROW + PICKUP(outside) from inventory (inventory Q)
    @Inject(method = "handleContainerClick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/AbstractContainerMenu;getStateId()I"), cancellable = true)
    private void pxrp$onClickSlotDrop(ServerboundContainerClickPacket packet, CallbackInfo ci) {
        var handler = player.containerMenu;
        int syncSlot;

        if (packet.clickType() == ClickType.THROW) {
            int slot = packet.slotNum();
            if (slot == -1) {
                syncSlot = -1;
            } else {
                if (slot < 0 || slot >= handler.slots.size()) return;
                if (handler.getSlot(slot).getItem().isEmpty()) return;
                syncSlot = slot;
            }
        } else if (packet.clickType() == ClickType.PICKUP && packet.slotNum() == -999) {
            syncSlot = -1;
        } else {
            return;
        }

        ItemStack dropStack = syncSlot == -1
            ? handler.getCarried()
            : handler.getSlot(syncSlot).getItem();
        if (dropStack.isEmpty()) return;
        int count = packet.buttonNum() == 1 ? dropStack.getCount() : 1;

            if (fireDropEvent(dropStack, count)) {
                ci.cancel();
                if (syncSlot == -1) {
                    player.connection.send(
                        new ClientboundSetCursorItemPacket(handler.getCarried().copy())
                    );
                } else {
                    player.connection.send(
                        new ClientboundContainerSetSlotPacket(handler.containerId, handler.incrementStateId(), syncSlot, handler.getSlot(syncSlot).getItem().copy())
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
