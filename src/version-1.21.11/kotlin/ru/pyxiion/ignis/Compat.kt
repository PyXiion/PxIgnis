package ru.pyxiion.ignis

import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket
import net.minecraft.world.inventory.ClickType
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerBossEvent
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.BossEvent
import net.minecraft.world.entity.Display
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityProcessor
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LightningBolt
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager

/**
 * Minecraft 1.21.11 implementation of everything whose API differs between the supported
 * versions. `src/version-26.3` declares the same members; shared code only calls these.
 */
object Compat {
    /** `ServerboundContainerClickPacket` calls its click type `clickType()` here and `containerInput()` on 26.x. */
    fun isThrowClick(packet: ServerboundContainerClickPacket): Boolean = packet.clickType() == ClickType.THROW

    /** A plain click outside the window (slot -999) drops the carried stack. */
    fun isOutsideClick(packet: ServerboundContainerClickPacket): Boolean =
        packet.clickType() == ClickType.PICKUP && packet.slotNum().toInt() == -999

    fun loadEntities(nbt: CompoundTag, world: ServerLevel, reason: EntitySpawnReason): Entity? =
        EntityType.loadEntityRecursive(nbt, world, reason, EntityProcessor.NOP)

    fun getDayTime(level: ServerLevel): Long = level.dayTime

    fun setDayTime(level: ServerLevel, ticks: Long) {
        level.dayTime = ticks
    }

    fun setWeather(level: ServerLevel, clearTime: Int, rainTime: Int, raining: Boolean, thundering: Boolean) {
        level.setWeatherParameters(clearTime, rainTime, raining, thundering)
    }

    fun isRainingFlag(level: ServerLevel): Boolean = level.levelData.isRaining

    fun isThunderingFlag(level: ServerLevel): Boolean = level.levelData.isThundering

    fun structureTemplateManager(server: MinecraftServer): StructureTemplateManager = server.structureManager

    fun readStructure(server: MinecraftServer, nbt: CompoundTag): StructureTemplate =
        server.structureManager.readStructure(nbt)

    fun entityTags(entity: Entity): Set<String> = entity.tags

    fun setInvulnerable(entity: Entity, value: Boolean) {
        entity.isInvulnerable = value
    }

    fun giveItem(player: ServerPlayer, stack: ItemStack) {
        player.inventory.placeItemBackInInventory(stack)
    }

    fun lightningBoltType(): EntityType<LightningBolt> = EntityType.LIGHTNING_BOLT

    fun textDisplayType(): EntityType<Display.TextDisplay> = EntityType.TEXT_DISPLAY

    fun createBossEvent(
        name: Component,
        color: BossEvent.BossBarColor,
        overlay: BossEvent.BossBarOverlay,
    ): ServerBossEvent = ServerBossEvent(name, color, overlay)
}
