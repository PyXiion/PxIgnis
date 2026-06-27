package ru.pyxiion.ignis

import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Mth

object Compat {
    fun fastCos(v: Double): Double = Mth.cos(v.toFloat()).toDouble()
    fun fastSin(v: Double): Double = Mth.sin(v.toFloat()).toDouble()
    fun isAdmin(player: ServerPlayer): Boolean = player.hasPermissionLevel(3)
    fun loadEntities(nbt: CompoundTag, world: ServerLevel, reason: EntitySpawnReason): Entity? =
        EntityType.loadEntityWithPassengers(nbt, world, reason) { it }
}
