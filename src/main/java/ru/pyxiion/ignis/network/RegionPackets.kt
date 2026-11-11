package ru.pyxiion.ignis.network

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.world.phys.AABB

data class RegionEntry(val id: Int, val box: AABB)

private val boxCodec: StreamCodec<FriendlyByteBuf, AABB> = StreamCodec.of(
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
            buf.readDouble(),
            buf.readDouble(),
            buf.readDouble(),
            buf.readDouble(),
            buf.readDouble(),
            buf.readDouble()
        )
    }
)

private val regionEntryCodec: StreamCodec<FriendlyByteBuf, RegionEntry> = StreamCodec.composite(
    ByteBufCodecs.INT, RegionEntry::id,
    boxCodec, RegionEntry::box,
    ::RegionEntry
)

data class RegionSyncPayload(val entries: List<RegionEntry>) : CustomPacketPayload {
    companion object {
        val ID: CustomPacketPayload.Type<RegionSyncPayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath("pxignis", "regions_sync"))
        val CODEC: StreamCodec<FriendlyByteBuf, RegionSyncPayload> =
            regionEntryCodec.apply(ByteBufCodecs.list()).map(
                ::RegionSyncPayload,
                RegionSyncPayload::entries
            )
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = ID
}

data class RegionUpsertPayload(val id: Int, val region: AABB) : CustomPacketPayload {
    companion object {
        val ID: CustomPacketPayload.Type<RegionUpsertPayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath("pxignis", "regions_upsert"))
        val CODEC: StreamCodec<FriendlyByteBuf, RegionUpsertPayload> =
            StreamCodec.composite(
                ByteBufCodecs.INT, RegionUpsertPayload::id,
                boxCodec, RegionUpsertPayload::region,
                ::RegionUpsertPayload
            )
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = ID
}

data class RegionRemovePayload(val id: Int) : CustomPacketPayload {
    companion object {
        val ID: CustomPacketPayload.Type<RegionRemovePayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath("pxignis", "regions_remove"))
        val CODEC: StreamCodec<FriendlyByteBuf, RegionRemovePayload> =
            StreamCodec.composite(
                ByteBufCodecs.INT, RegionRemovePayload::id,
                ::RegionRemovePayload
            )
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = ID
}

data class RegionCapWarningPayload(val cap: Int) : CustomPacketPayload {
    companion object {
        val ID: CustomPacketPayload.Type<RegionCapWarningPayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath("pxignis", "regions_cap_warning"))
        val CODEC: StreamCodec<FriendlyByteBuf, RegionCapWarningPayload> =
            StreamCodec.composite(
                ByteBufCodecs.INT, RegionCapWarningPayload::cap,
                ::RegionCapWarningPayload
            )
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = ID
}

data class RegionInterestPayload(val enabled: Boolean) : CustomPacketPayload {
    companion object {
        val ID: CustomPacketPayload.Type<RegionInterestPayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath("pxignis", "regions_interest"))
        val CODEC: StreamCodec<FriendlyByteBuf, RegionInterestPayload> =
            StreamCodec.composite(
                ByteBufCodecs.BOOL, RegionInterestPayload::enabled,
                ::RegionInterestPayload
            )
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = ID
}
