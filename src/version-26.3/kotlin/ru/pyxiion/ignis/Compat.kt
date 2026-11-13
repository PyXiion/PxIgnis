package ru.pyxiion.ignis

import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.nbt.NbtUtils
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerBossEvent
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Prediction
import net.minecraft.util.datafix.DataFixTypes
import net.minecraft.world.BossEvent
import net.minecraft.world.entity.Display
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityProcessor
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntitySpawnRequest
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.Interaction
import net.minecraft.world.entity.LightningBolt
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager
import java.util.UUID

/**
 * Minecraft 26.3 implementation of everything whose API differs between the supported
 * versions. `src/version-1.21.11` declares the same members; shared code only calls these.
 */
object Compat {
    /** Whether dimension types name a world clock (Minecraft 26.1+). */
    const val HAS_WORLD_CLOCKS = true

    /** `ServerboundContainerClickPacket` calls its click type `clickType()` on 1.21.x and `containerInput()` here. */
    fun isThrowClick(packet: ServerboundContainerClickPacket): Boolean =
        packet.containerInput() == ContainerInput.THROW

    /** A plain click outside the window (slot -999) drops the carried stack. */
    fun isOutsideClick(packet: ServerboundContainerClickPacket): Boolean =
        packet.containerInput() == ContainerInput.PICKUP && packet.slotNum().toInt() == -999

    fun loadEntities(nbt: CompoundTag, world: ServerLevel, reason: EntitySpawnReason): Entity? =
        EntityType.loadEntityRecursive(nbt, world, EntitySpawnRequest(reason, false), EntityProcessor.NOP)

    /** Ticks of the dimension's default world clock (0 for dimensions without one). */
    fun getDayTime(level: ServerLevel): Long = level.defaultClockTime

    fun setDayTime(level: ServerLevel, ticks: Long) {
        val clock = level.dimensionType().defaultClock().orElse(null) ?: return
        level.clockManager().setTotalTicks(clock, ticks)
    }

    fun setWeather(level: ServerLevel, clearTime: Int, rainTime: Int, raining: Boolean, thundering: Boolean) {
        val weather = level.weatherData
        weather.clearWeatherTime = clearTime
        weather.rainTime = rainTime
        weather.thunderTime = rainTime
        weather.isRaining = raining
        weather.isThundering = thundering
    }

    fun isRainingFlag(level: ServerLevel): Boolean = level.weatherData.isRaining

    fun isThunderingFlag(level: ServerLevel): Boolean = level.weatherData.isThundering

    fun structureTemplateManager(server: MinecraftServer): StructureTemplateManager = server.structureTemplateManager

    fun readStructure(server: MinecraftServer, nbt: CompoundTag): StructureTemplate {
        val fixed = DataFixTypes.STRUCTURE.updateToCurrentVersion(server.fixerUpper, nbt, NbtUtils.getDataVersion(nbt, 500))
        val template = StructureTemplate()
        template.load(server.registryAccess().lookupOrThrow(Registries.BLOCK), fixed)
        return template
    }

    /** Swing animation sent to trackers and the entity itself (26.x takes the item's swing animation). */
    fun swing(entity: net.minecraft.world.entity.LivingEntity, hand: net.minecraft.world.InteractionHand) {
        entity.swing(hand, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true)
    }

    fun entityTags(entity: Entity): Set<String> = entity.entityTags()

    fun setInvulnerable(entity: Entity, value: Boolean) {
        entity.isPermanentlyInvulnerable = value
    }

    /** Makes the tracker send the entity's motion to clients (`hurtMarked` on 1.21.x). */
    fun syncMotion(entity: Entity) {
        entity.needsSync = true

        // Immediately send sync packet for players, because it doesn't work on them with just .hurtMarked
        if (entity is ServerPlayer) {
            entity.connection.send(ClientboundSetEntityMotionPacket(entity))
        }
    }

    fun giveItem(player: ServerPlayer, stack: ItemStack) {
        player.inventory.placeItemBackInInventory(stack, true, Prediction.SERVER_ONLY)
    }

    fun lightningBoltType(): EntityType<LightningBolt> = EntityTypes.LIGHTNING_BOLT

    fun textDisplayType(): EntityType<Display.TextDisplay> = EntityTypes.TEXT_DISPLAY

    fun itemDisplayType(): EntityType<Display.ItemDisplay> = EntityTypes.ITEM_DISPLAY

    fun blockDisplayType(): EntityType<Display.BlockDisplay> = EntityTypes.BLOCK_DISPLAY

    fun interactionType(): EntityType<Interaction> = EntityTypes.INTERACTION

    fun createBossEvent(
        name: Component,
        color: BossEvent.BossBarColor,
        overlay: BossEvent.BossBarOverlay,
    ): ServerBossEvent = ServerBossEvent(UUID.randomUUID(), name, color, overlay)
}
