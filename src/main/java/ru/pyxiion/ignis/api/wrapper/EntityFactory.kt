package ru.pyxiion.ignis.api.wrapper

import net.minecraft.entity.Entity
import net.minecraft.entity.decoration.DisplayEntity
import net.minecraft.entity.mob.MobEntity
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import org.luaj.vm2.LuaValue

object EntityFactory {
    fun wrap(entity: Entity): LuaValue = when (entity) {
        is ServerPlayerEntity -> PlayerWrap.wrap(entity)
        is MobEntity -> MobWrap.wrap(entity)
        is DisplayEntity.TextDisplayEntity -> HologramWrapper(entity, entity.entityWorld as ServerWorld).toLuaValue()
        else -> EntityWrap.wrap(entity)
    }
}
