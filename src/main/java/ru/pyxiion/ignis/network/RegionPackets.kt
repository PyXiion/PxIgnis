package ru.pyxiion.ignis.network

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.world.phys.AABB

data class RegionEntry(val id: Int, val box: AABB)

private val boxCodec: StreamCodec<FriendlyByteBuf, AABB> = StreamCodec.ofStatic(
    { buf, box ->
        buf.writeDouble(box.minX)
        buf.writeDouble(box.minY)
        buf.writeDouble(box.minZ)
        buf.writeDouble(box.maxX)
        buf.writeDouble(box.maxY)
        buf.writeDouble(box.maxZ)
    },
    { buf ->
        AABB(
            buf.readAccounted(),
            buf.readAccounted(),
            buf.readAccounted(),
            buf.readAccounted(),
            buf.readAccounted(),
            buf.readAccounted()
        )
    }
)

private val regionEntryCodec: StreamCodec<FriendlyByteBuf, RegionEntry> = StreamCodec.composite(
    ByteBufCodecs.INTEGER, RegionEntry::id,
    boxCodec, RegionEntry::box,
    ::RegionEntry
)

data class RegionSyncPayload(val entries: List<RegionEntry>) : CustomPacketPayload {
    companion object {
        val ID: CustomPacketPayload.Id<RegionSyncPayload> =
            CustomPacketPayload.Id(Identifier.of("pxignis", "regions_sync"))
        val CODEC: StreamCodec<FriendlyByteBuf, RegionSyncPayload> =
            regionEntryCodec.collect(ByteBufCodecs.toList()).xmap(
                ::RegionSyncPayload,
                RegionSyncPayload::entries
            )
    }

    override fun getId(): CustomPacketPayload.Id<out CustomPacketPayload> = ID
}

data class RegionUpsertPayload(val id: Int, val region: AABB) : CustomPacketPayload {
    companion object {
        val ID: CustomPacketPayload.Id<RegionUpsertPayload> =
            CustomPacketPayload.Id(Identifier.of("pxignis", "regions_upsert"))
        val CODEC: StreamCodec<FriendlyByteBuf, RegionUpsertPayload> =
            StreamCodec.composite(
                ByteBufCodecs.INTEGER, RegionUpsertPayload::id,
                boxCodec, RegionUpsertPayload::region,
                ::RegionUpsertPayload
            )
    }

    override fun getId(): CustomPacketPayload.Id<out CustomPacketPayload> = ID
}

data class RegionRemovePayload(val id: Int) : CustomPacketPayload {
    companion object {
        val ID: CustomPacketPayload.Id<RegionRemovePayload> =
            CustomPacketPayload.Id(Identifier.of("pxignis", "regions_remove"))
        val CODEC: StreamCodec<FriendlyByteBuf, RegionRemovePayload> =
            StreamCodec.composite(
                ByteBufCodecs.INTEGER, RegionRemovePayload::id,
                ::RegionRemovePayload
            )
    }

    override fun getId(): CustomPacketPayload.Id<out CustomPacketPayload> = ID
}

data class RegionCapWarningPayload(val cap: Int) : CustomPacketPayload {
    companion object {
        val ID: CustomPacketPayload.Id<RegionCapWarningPayload> =
            CustomPacketPayload.Id(Identifier.of("pxignis", "regions_cap_warning"))
        val CODEC: StreamCodec<FriendlyByteBuf, RegionCapWarningPayload> =
            StreamCodec.composite(
                ByteBufCodecs.INTEGER, RegionCapWarningPayload::cap,
                ::RegionCapWarningPayload
            )
    }

    override fun getId(): CustomPacketPayload.Id<out CustomPacketPayload> = ID
}

data class RegionInterestPayload(val enabled: Boolean) : CustomPacketPayload {
    companion object {
        val ID: CustomPacketPayload.Id<RegionInterestPayload> =
            CustomPacketPayload.Id(Identifier.of("pxignis", "regions_interest"))
        val CODEC: StreamCodec<FriendlyByteBuf, RegionInterestPayload> =
            StreamCodec.composite(
                ByteBufCodecs.BOOLEAN, RegionInterestPayload::enabled,
                ::RegionInterestPayload
            )
    }

    override fun getId(): CustomPacketPayload.Id<out CustomPacketPayload> = ID
}
