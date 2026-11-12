package ru.pyxiion.ignis.api.wrapper

import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.Display
import net.minecraft.world.entity.Mob
import net.minecraft.server.level.ServerPlayer
import ru.pyxiion.ignis.api.manager.HologramManager
import org.luaj.vm2.LuaValue

object EntityFactory {
    fun wrap(entity: Entity): LuaValue = when (entity) {
        is ServerPlayer -> PlayerWrap.wrap(entity)
        is Mob -> MobWrap.wrap(entity)
        is Display.TextDisplay ->
            HologramManager.get(entity.uuid)?.toLuaValue() ?: HologramWrapper.untracked(entity)
        else -> EntityWrap.wrap(entity)
    }
}
