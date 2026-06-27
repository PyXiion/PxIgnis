package ru.pyxiion.ignis.mixins;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandSourceStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;
import java.util.function.Predicate;


@Mixin(CommandNode.class)
public interface    CommandNodeMixin {
    @Accessor
    Map<String, CommandNode<?>> getChildren();

    @Accessor
    Map<String, CommandNode<?>> getLiterals();

    @Accessor
    Command<?> getCommand();

    @Accessor
    void setCommand(Command<?> command);

    @Accessor
    @Mutable
    void setRequirement(Predicate<CommandSourceStack> requirement);
}
