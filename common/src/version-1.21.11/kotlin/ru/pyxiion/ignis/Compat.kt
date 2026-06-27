package ru.pyxiion.ignis

import net.minecraft.server.permissions.Permission
import net.minecraft.server.permissions.PermissionLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EntityProcessor
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Mth

object Compat {
    fun fastCos(v: Double): Double = Mth.cos(v).toDouble()
    fun fastSin(v: Double): Double = Mth.sin(v).toDouble()
    fun isAdmin(player: ServerPlayer): Boolean =
        player.permissions().hasPermission(Permission.HasCommandLevel(PermissionLevel.ADMINS))
    fun loadEntities(nbt: CompoundTag, world: ServerLevel, reason: EntitySpawnReason): Entity? =
        EntityType.loadEntityRecursive(nbt, world, reason, EntityProcessor.NOP)
}
