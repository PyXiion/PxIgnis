package ru.pyxiion.ignis

import net.minecraft.entity.Entity
import net.minecraft.entity.EntityType
import net.minecraft.entity.SpawnReason
import net.minecraft.nbt.NbtCompound
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.MathHelper

object Compat {
    fun fastCos(v: Double): Double = MathHelper.cos(v.toFloat()).toDouble()
    fun fastSin(v: Double): Double = MathHelper.sin(v.toFloat()).toDouble()
    fun isAdmin(player: ServerPlayerEntity): Boolean = player.hasPermissionLevel(3)
    fun loadEntities(nbt: NbtCompound, world: ServerWorld, reason: SpawnReason): Entity? =
        EntityType.loadEntityWithPassengers(nbt, world, reason) { it }
}
