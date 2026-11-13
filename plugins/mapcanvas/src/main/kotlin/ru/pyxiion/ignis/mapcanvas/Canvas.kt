package ru.pyxiion.ignis.mapcanvas

import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.saveddata.maps.MapDecoration
import net.minecraft.world.level.saveddata.maps.MapId
import net.minecraft.world.level.saveddata.maps.MapItemSavedData
import java.util.Optional
import java.util.UUID

/** One virtual map: a 128x128 buffer, its icons, and the players it is sent to. */
class Canvas(private val env: MapCanvasEnv, val id: Int) {
    val buffer = PixelBuffer()
    var icons: List<MapDecoration> = emptyList()
        private set
    private var iconsDirty = false
    var freed = false
        private set

    /** Players shown this canvas by `show`. Online players are resolved by uuid on every flush. */
    val viewers = LinkedHashSet<UUID>()
    val walls = LinkedHashSet<Wall>()

    private class Sent(val player: ServerPlayer, val level: ServerLevel)
    private val sent = HashMap<UUID, Sent>()

    fun setIcons(list: List<MapDecoration>) {
        icons = list
        iconsDirty = true
    }

    private fun targets(): Map<UUID, ServerPlayer> {
        val out = LinkedHashMap<UUID, ServerPlayer>()
        for (u in viewers) env.server.playerList.getPlayer(u)?.let { out[u] = it }
        for (w in walls) for (u in w.viewerIds()) env.server.playerList.getPlayer(u)?.let { out[u] = it }
        return out
    }

    fun flush() {
        if (freed) return
        val patch = buffer.takeDirty()
        val iconsChanged = iconsDirty
        iconsDirty = false
        val targets = targets()
        sent.keys.retainAll(targets.keys)
        if (targets.isEmpty()) return
        val id = MapId(id)
        for ((uuid, p) in targets) {
            val level = p.level() as ServerLevel
            val s = sent[uuid]
            if (s == null || s.player !== p || s.level !== level) {
                sent[uuid] = Sent(p, level)
                send(p, packet(id, Optional.of(icons), buffer.full()))
            } else if (patch != null || iconsChanged) {
                send(p, packet(id, if (iconsChanged) Optional.of(icons) else Optional.empty(), patch))
            }
        }
    }

    private fun packet(id: MapId, icons: Optional<List<MapDecoration>>, patch: Patch?) = ClientboundMapItemDataPacket(
        id, 0.toByte(), true, icons,
        Optional.ofNullable(patch?.let { MapItemSavedData.MapPatch(it.x, it.y, it.w, it.h, it.bytes) }),
    )

    private fun send(p: ServerPlayer, packet: ClientboundMapItemDataPacket) = p.connection.send(packet)

    fun free() {
        freed = true
        viewers.clear()
        sent.clear()
        walls.clear()
    }
}
