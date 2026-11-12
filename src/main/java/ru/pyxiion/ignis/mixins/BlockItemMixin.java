package ru.pyxiion.ignis.mixins;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.luaj.vm2.LuaValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import ru.pyxiion.ignis.PxIgnis;
import ru.pyxiion.ignis.api.Vector;
import ru.pyxiion.ignis.api.wrapper.PlayerWrap;

@Mixin(BlockItem.class)
public abstract class BlockItemMixin {

    // player_block_place. Wraps the placeBlock call inside BlockItem.place, not placeBlock itself: BedItem overrides
    // placeBlock without calling super, and DoubleHighBlockItem sets the upper half before calling it, so a hook
    // inside placeBlock is skipped or fires too late. Here the context and the state are the ones about to be placed
    // (the position is the placement position, not the clicked block). Returning false makes place() answer
    // InteractionResult.FAIL.
    @WrapOperation(
        method = "place",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/item/BlockItem;placeBlock(Lnet/minecraft/world/item/context/BlockPlaceContext;Lnet/minecraft/world/level/block/state/BlockState;)Z"
        )
    )
    private boolean pxrp$onPlaceBlock(BlockItem item, BlockPlaceContext context, BlockState state, Operation<Boolean> original) {
        if (context.getPlayer() instanceof ServerPlayer player) {
            var rt = PxIgnis.getRuntimeOrNull();
            if (rt != null) {
                BlockPos pos = context.getClickedPos();
                if (rt.getEventManager().fireCancellable("player_block_place", () -> new LuaValue[]{
                    PlayerWrap.INSTANCE.wrap(player),
                    Vector.Companion.of(pos.getX(), pos.getY(), pos.getZ()).toLuaValue(),
                    LuaValue.valueOf(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString())
                })) {
                    return false;
                }
            }
        }
        return original.call(item, context, state);
    }
}
