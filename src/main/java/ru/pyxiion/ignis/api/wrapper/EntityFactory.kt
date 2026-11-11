package ru.pyxiion.ignis.api.wrapper

import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.Display
import net.minecraft.world.entity.Mob
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.level.ServerLevel
import org.luaj.vm2.LuaValue

object EntityFactory {
    fun wrap(entity: Entity): LuaValue = when (entity) {
        is ServerPlayer -> PlayerWrap.wrap(entity)
        is Mob -> MobWrap.wrap(entity)
        is Display.TextDisplay -> HologramWrapper(entity, entity.level() as ServerLevel).toLuaValue()
        else -> EntityWrap.wrap(entity)
    }
}
