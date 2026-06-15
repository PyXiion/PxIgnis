package ru.pyxiion.ignis.mixins;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.pyxiion.ignis.LuaCmdLoader;
import ru.pyxiion.ignis.PxIgnis;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;

@Mixin(MinecraftServer.class)
public class MinecraftServerMixin {
    @Inject(method = "net.minecraft.server.MinecraftServer.reloadResources", at = @At("TAIL"))
    private void reloadRes(Collection<String> dataPacks, CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        PxIgnis.instance.luaLoader.reload();
    }
}
