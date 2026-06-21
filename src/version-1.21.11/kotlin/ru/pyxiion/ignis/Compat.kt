package ru.pyxiion.ignis

import net.minecraft.command.DefaultPermissions
import net.minecraft.entity.Entity
import net.minecraft.entity.EntityType
import net.minecraft.entity.LoadedEntityProcessor
import net.minecraft.entity.SpawnReason
import net.minecraft.nbt.NbtCompound
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.MathHelper

object Compat {
    fun fastCos(v: Double): Double = MathHelper.cos(v).toDouble()
    fun fastSin(v: Double): Double = MathHelper.sin(v).toDouble()
    fun isAdmin(player: ServerPlayerEntity): Boolean =
        player.getPermissions().hasPermission(DefaultPermissions.ADMINS)
    fun loadEntities(nbt: NbtCompound, world: ServerWorld, reason: SpawnReason): Entity? =
        EntityType.loadEntityWithPassengers(nbt, world, reason, LoadedEntityProcessor.NOOP)
}
