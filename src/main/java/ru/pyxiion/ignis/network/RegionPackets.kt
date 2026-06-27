package ru.pyxiion.ignis.network

import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.codec.PacketCodecs
import net.minecraft.network.packet.CustomPayload
import net.minecraft.util.Identifier
import net.minecraft.util.math.Box

data class RegionEntry(val id: Int, val box: Box)

private val boxCodec: PacketCodec<PacketByteBuf, Box> = PacketCodec.ofStatic(
    { buf, box ->
        buf.writeDouble(box.minX)
        buf.writeDouble(box.minY)
        buf.writeDouble(box.minZ)
        buf.writeDouble(box.maxX)
        buf.writeDouble(box.maxY)
        buf.writeDouble(box.maxZ)
    },
    { buf ->
        Box(
            buf.readDouble(),
            buf.readDouble(),
            buf.readDouble(),
            buf.readDouble(),
            buf.readDouble(),
            buf.readDouble()
        )
    }
)

private val regionEntryCodec: PacketCodec<PacketByteBuf, RegionEntry> = PacketCodec.tuple(
    PacketCodecs.INTEGER, RegionEntry::id,
    boxCodec, RegionEntry::box,
    ::RegionEntry
)

data class RegionSyncPayload(val entries: List<RegionEntry>) : CustomPayload {
    companion object {
        val ID: CustomPayload.Id<RegionSyncPayload> =
            CustomPayload.Id(Identifier.of("pxignis", "regions_sync"))
        val CODEC: PacketCodec<PacketByteBuf, RegionSyncPayload> =
            regionEntryCodec.collect(PacketCodecs.toList()).xmap(
                ::RegionSyncPayload,
                RegionSyncPayload::entries
            )
    }

    override fun getId(): CustomPayload.Id<out CustomPayload> = ID
}

data class RegionUpsertPayload(val id: Int, val region: Box) : CustomPayload {
    companion object {
        val ID: CustomPayload.Id<RegionUpsertPayload> =
            CustomPayload.Id(Identifier.of("pxignis", "regions_upsert"))
        val CODEC: PacketCodec<PacketByteBuf, RegionUpsertPayload> =
            PacketCodec.tuple(
                PacketCodecs.INTEGER, RegionUpsertPayload::id,
                boxCodec, RegionUpsertPayload::region,
                ::RegionUpsertPayload
            )
    }

    override fun getId(): CustomPayload.Id<out CustomPayload> = ID
}

data class RegionRemovePayload(val id: Int) : CustomPayload {
    companion object {
        val ID: CustomPayload.Id<RegionRemovePayload> =
            CustomPayload.Id(Identifier.of("pxignis", "regions_remove"))
        val CODEC: PacketCodec<PacketByteBuf, RegionRemovePayload> =
            PacketCodec.tuple(
                PacketCodecs.INTEGER, RegionRemovePayload::id,
                ::RegionRemovePayload
            )
    }

    override fun getId(): CustomPayload.Id<out CustomPayload> = ID
}

data class RegionCapWarningPayload(val cap: Int) : CustomPayload {
    companion object {
        val ID: CustomPayload.Id<RegionCapWarningPayload> =
            CustomPayload.Id(Identifier.of("pxignis", "regions_cap_warning"))
        val CODEC: PacketCodec<PacketByteBuf, RegionCapWarningPayload> =
            PacketCodec.tuple(
                PacketCodecs.INTEGER, RegionCapWarningPayload::cap,
                ::RegionCapWarningPayload
            )
    }

    override fun getId(): CustomPayload.Id<out CustomPayload> = ID
}

data class RegionInterestPayload(val enabled: Boolean) : CustomPayload {
    companion object {
        val ID: CustomPayload.Id<RegionInterestPayload> =
            CustomPayload.Id(Identifier.of("pxignis", "regions_interest"))
        val CODEC: PacketCodec<PacketByteBuf, RegionInterestPayload> =
            PacketCodec.tuple(
                PacketCodecs.BOOLEAN, RegionInterestPayload::enabled,
                ::RegionInterestPayload
            )
    }

    override fun getId(): CustomPayload.Id<out CustomPayload> = ID
}
