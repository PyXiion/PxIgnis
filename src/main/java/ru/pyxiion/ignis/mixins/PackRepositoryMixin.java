package ru.pyxiion.ignis.mixins;

import com.google.common.collect.ImmutableSet;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.RepositorySource;
import net.minecraft.server.packs.repository.ServerPacksSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.pyxiion.ignis.MenuPack;

import java.util.Arrays;
import java.util.Set;

@Mixin(PackRepository.class)
public abstract class PackRepositoryMixin {

    @Shadow
    @Final
    @Mutable
    private Set<RepositorySource> sources;

    // The data pack repository of a server (the one with ServerPacksSource) also offers the pxignis:menu pack.
    @Inject(method = "<init>", at = @At("TAIL"))
    private void pxrp$addMenuPack(RepositorySource[] sources, CallbackInfo ci) {
        if (Arrays.stream(sources).noneMatch(s -> s instanceof ServerPacksSource)) return;
        this.sources = ImmutableSet.<RepositorySource>builder().addAll(this.sources).add(MenuPack.INSTANCE.newSource()).build();
    }
}
