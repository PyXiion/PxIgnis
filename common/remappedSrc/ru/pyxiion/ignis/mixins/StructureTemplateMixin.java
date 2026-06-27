package ru.pyxiion.ignis.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

@Mixin(StructureTemplate.class)
public interface StructureTemplateMixin {
    @Accessor
    List<StructureTemplate.StructureEntityInfo> getEntityInfoList();
}
