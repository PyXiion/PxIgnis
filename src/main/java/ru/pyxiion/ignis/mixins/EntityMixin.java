package ru.pyxiion.ignis.mixins;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.pyxiion.ignis.api.manager.RegionManager;

@Mixin(Entity.class)
public class EntityMixin {
    @Unique
    private Vec3d pxrp$lastPos = null;

    @Inject(method = "baseTick", at = @At("TAIL"))
    private void pxrp$capturePos(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (self.getEntityWorld().isClient()) return;
        Vec3d cur = self.getEntityPos();
        if (pxrp$lastPos == null) {
            pxrp$lastPos = cur;
            return;
        }
        if (!cur.equals(pxrp$lastPos)) {
            Vec3d prev = pxrp$lastPos;
            pxrp$lastPos = cur;
            RegionManager.INSTANCE.onEntityMoved(self, prev, cur);
        }
    }
}
