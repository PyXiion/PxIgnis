package ru.pyxiion.ignis.mixins;

import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.pyxiion.ignis.api.PlayerMoveDispatcher;
import ru.pyxiion.ignis.api.manager.RegionManager;

@Mixin(Entity.class)
public class EntityMixin {
    @Unique
    private Vec3 pxrp$lastPos = null;

    @Inject(method = "baseTick", at = @At("TAIL"))
    private void pxrp$capturePos(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (self.level().isClientSide()) return;
        Vec3 cur = self.position();
        if (pxrp$lastPos == null) {
            pxrp$lastPos = cur;
            return;
        }
        if (!cur.equals(pxrp$lastPos)) {
            Vec3 prev = pxrp$lastPos;
            pxrp$lastPos = cur;
            RegionManager.INSTANCE.onEntityMoved(self, prev, cur);

            if (self instanceof ServerPlayer player) {
                PlayerMoveDispatcher.INSTANCE.onPlayerMoved(player, prev, cur);
            }
        }
    }
}
