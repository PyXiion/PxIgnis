package ru.pyxiion.ignis.mapcanvas

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.core.component.DataComponents
import net.minecraft.world.level.saveddata.maps.MapId
import java.util.UUID

/**
 * A grid of packet-only invisible item frames. [pos] is the block of the top-left frame as seen by a viewer, [facing]
 * the direction the frames face. Players of the same world within [RANGE] blocks are shown the frames automatically.
 */
class Wall(
    private val env: MapCanvasEnv,
    private val level: ServerLevel,
    pos: BlockPos,
    facing: Direction,
    cols: Int,
    rows: Int,
    val canvases: List<Canvas>,
) {
    private class Seen(val player: ServerPlayer)

    private val frames: List<ItemFrame>
    private val center: net.minecraft.world.phys.Vec3
    private val seen = HashMap<UUID, Seen>()
    private var removed = false

    init {
        val right = facing.counterClockWise
        frames = List(cols * rows) { i ->
            val at = pos.relative(right, i % cols).below(i / cols)
            ItemFrame(level, at, facing).also { f ->
                val map = ItemStack(Items.FILLED_MAP)
                map.set(DataComponents.MAP_ID, MapId(canvases[i].id))
                f.setItem(map, false)
                f.isInvisible = true
            }
        }
        val first = frames.first().blockPosition()
        val last = frames.last().blockPosition()
        center = net.minecraft.world.phys.Vec3(
            (first.x + last.x) / 2.0 + 0.5, (first.y + last.y) / 2.0 + 0.5, (first.z + last.z) / 2.0 + 0.5,
        )
        canvases.forEach { it.walls.add(this) }
    }

    fun viewerIds(): Set<UUID> = seen.keys

    fun tick() {
        if (removed) return
        val maxSq = RANGE * RANGE
        val now = HashSet<UUID>()
        for (p in env.server.playerList.players) {
            if (p.level() !== level || p.position().distanceToSqr(center) > maxSq) continue
            now.add(p.uuid)
            val s = seen[p.uuid]
            if (s == null || s.player !== p) {
                seen[p.uuid] = Seen(p)
                spawn(p)
            }
        }
        val it = seen.entries.iterator()
        while (it.hasNext()) {
            val (uuid, s) = it.next()
            if (uuid !in now) {
                it.remove()
                despawn(s.player)
            }
        }
    }

    private fun spawn(p: ServerPlayer) {
        for (f in frames) {
            p.connection.send(ClientboundAddEntityPacket(f, f.direction.get3DDataValue(), f.blockPosition()))
            p.connection.send(ClientboundSetEntityDataPacket(f.id, f.entityData.nonDefaultValues!!))
        }
    }

    private fun despawn(p: ServerPlayer) {
        p.connection.send(ClientboundRemoveEntitiesPacket(*frames.map { it.id }.toIntArray()))
    }

    fun remove() {
        if (removed) return
        removed = true
        for (s in seen.values) despawn(s.player)
        seen.clear()
        canvases.forEach { it.walls.remove(this) }
        env.walls.remove(this)
    }

    companion object {
        const val RANGE = 48.0
    }
}
