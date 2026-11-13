package ru.pyxiion.ignis.api.manager

import com.mojang.math.Transformation
import net.fabricmc.fabric.api.networking.v1.PlayerLookup
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ClientboundBundlePacket
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Display
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.PositionMoveRotation
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.pyxiion.ignis.api.wrapper.DisplayWrap
import ru.pyxiion.ignis.runtime.ScriptErrors
import ru.pyxiion.ignis.runtime.ScriptWatchdog
import ru.pyxiion.luanova.vm2.LuaFunction
import ru.pyxiion.luanova.vm2.LuaValue
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

private typealias ClientPacket = Packet<in ClientGamePacketListener>

/** A display transform: translation, left rotation, scale, right rotation (as given), or an affine matrix. */
internal class TransformSpec(
    private val t: Vector3f?,
    private val l: Quaternionf?,
    private val s: Vector3f?,
    private val r: Quaternionf?,
    private val matrix: Matrix4f?,
) {
    /** [offset] (an attach offset) is added to the translation. */
    fun build(offset: Vector3f?): Transformation {
        if (matrix != null) {
            if (offset == null) return Transformation(matrix)
            return Transformation(Matrix4f(matrix).translateLocal(offset))
        }
        val tr = Vector3f(t!!)
        if (offset != null) tr.add(offset)
        return Transformation(tr, l!!, s!!, r!!)
    }

    companion object {
        val IDENTITY = TransformSpec(Vector3f(), Quaternionf(), Vector3f(1f, 1f, 1f), Quaternionf(), null)
    }
}

/** Packets collected during one flush, sent as one bundle per player. */
internal class PacketBatch {
    private val perPlayer = IdentityHashMap<ServerPlayer, MutableList<ClientPacket>>()

    fun add(player: ServerPlayer, packet: ClientPacket) {
        perPlayer.getOrPut(player) { ArrayList(4) }.add(packet)
    }

    fun addAll(players: Iterable<ServerPlayer>, packet: ClientPacket) {
        for (p in players) add(p, packet)
    }

    fun send() {
        for ((player, packets) in perPlayer) deliver(player, packets)
        perPlayer.clear()
    }

    companion object {
        fun deliver(player: ServerPlayer, packets: List<ClientPacket>) {
            if (packets.isEmpty()) return
            val connection = player.connection ?: return
            if (packets.size == 1) connection.send(packets[0]) else connection.send(ClientboundBundlePacket(packets))
        }
    }
}

/**
 * Packet-only display entities. A handle owns a never-spawned entity object: its setters and `SynchedEntityData`
 * do the property bookkeeping (diff, serializers, defaults) and its entity id is allocated from the game's counter,
 * but the entity is not in any level, so nothing is saved to chunks or ticked. Clients get the entity through packets.
 */
class DisplayHandle internal constructor(
    val kind: String,
    internal var world: ServerLevel,
    internal val entity: Entity,
    var pos: Vec3,
) {
    val id: Int = entity.id
    var yaw = 0f
        private set
    var pitch = 0f
        private set

    var valid = true
        private set

    enum class Mode { AUTO, MANUAL }

    internal var mode = Mode.AUTO
    internal var viewRange = 1.0f
    internal var transform = TransformSpec.IDENTITY
    internal var startInterp = false
    internal var teleportDirty = false
    internal var needsViewerCheck = true
    internal var dieWithHost = false

    var onClick: LuaFunction? = null
    var onAttack: LuaFunction? = null

    internal val lua: LuaValue by lazy { DisplayWrap.wrap(this) }

    /** Viewer to the level it was spawned in; a different level means the client dropped the entity. */
    internal val viewers = IdentityHashMap<ServerPlayer, ServerLevel>()

    internal var host: Entity? = null
        private set
    private var hostOffset: Vector3f? = null
    private val attachSeen: MutableSet<ServerPlayer> = Collections.newSetFromMap(IdentityHashMap())

    private val display: Display? get() = entity as? Display

    /** Reapplies [transform] with the attach offset folded into the translation. */
    internal fun applyTransform() {
        val d = display ?: return
        d.setTransformation(transform.build(hostOffset))
        startInterp = true
    }

    fun moveTo(to: Vec3, newYaw: Float?, newPitch: Float?) {
        check(valid) { "display removed" }
        check(host == null) { "display is attached to an entity: detach() before moveTo()" }
        pos = to
        if (newYaw != null) yaw = newYaw
        if (newPitch != null) pitch = newPitch
        teleportDirty = true
    }

    fun attach(target: Entity, offset: Vec3?) {
        check(valid) { "display removed" }
        detachInternal(notify = false)
        host = target
        hostOffset = offset?.let { Vector3f(it.x.toFloat(), it.y.toFloat(), it.z.toFloat()) }
        pos = target.position()
        DisplayManager.registerPassenger(target.id, id)
        attachSeen.clear()
        applyTransform()
    }

    fun detach() {
        check(valid) { "display removed" }
        detachInternal(notify = true)
    }

    private fun detachInternal(notify: Boolean) {
        val h = host ?: return
        DisplayManager.unregisterPassenger(h.id, id)
        host = null
        hostOffset = null
        if (display != null) applyTransform()
        pos = h.position()
        teleportDirty = true
        if (notify && !h.isRemoved) {
            val packet = ClientboundSetPassengersPacket(h)
            for (p in attachSeen) if (viewers.containsKey(p)) p.connection?.send(packet)
        }
        attachSeen.clear()
    }

    fun show(player: ServerPlayer) {
        if (!valid || viewers.containsKey(player) || player.isRemoved || player.level() !== world) return
        viewers[player] = world
        val out = ArrayList<ClientPacket>(3)
        addSpawnPackets(out)
        PacketBatch.deliver(player, out)
    }

    fun hide(player: ServerPlayer) {
        if (viewers.remove(player) == null) return
        attachSeen.remove(player)
        player.connection?.send(ClientboundRemoveEntitiesPacket(id))
    }

    fun remove() {
        if (!valid) return
        detachInternal(notify = false)
        valid = false
        val packet = ClientboundRemoveEntitiesPacket(id)
        for ((p, lvl) in viewers) if (!p.isRemoved && p.level() === lvl) p.connection?.send(packet)
        viewers.clear()
        onClick = null
        onAttack = null
        DisplayManager.unregister(this)
    }

    private fun addSpawnPackets(out: MutableList<ClientPacket>) {
        out.add(
            ClientboundAddEntityPacket(
                id, entity.uuid, pos.x, pos.y, pos.z, pitch, yaw, entity.type, 0, Vec3.ZERO, 0.0,
            )
        )
        val values = entity.entityData.nonDefaultValues
        if (!values.isNullOrEmpty()) out.add(ClientboundSetEntityDataPacket(id, values))
    }

    // -- per-tick work, called by DisplayManager -----------------------------------------------------------------

    /** Drops viewers that left, died, changed world or are out of range; in auto mode adds players that came close. */
    internal fun updateViewers(batch: PacketBatch, rangeCheck: Boolean) {
        val it = viewers.entries.iterator()
        val exitR = (viewRange * 64.0 + 8.0).let { r -> r * r }
        var worldChanged = false
        while (it.hasNext()) {
            val (p, lvl) = it.next()
            val gone = p.isRemoved || p.connection == null
            val moved = !gone && (p.level() !== lvl || lvl !== world)
            val far = !gone && !moved && mode == Mode.AUTO && rangeCheck && p.distanceToSqr(pos) > exitR
            if (!(gone || moved || far)) continue
            it.remove()
            attachSeen.remove(p)
            if (far) batch.add(p, ClientboundRemoveEntitiesPacket(id))
            if (moved) worldChanged = true
        }
        if (worldChanged) DisplayManager.requestRecheck()
        if (mode != Mode.AUTO || !rangeCheck) return
        val enterR = (viewRange * 64.0).let { r -> r * r }
        for (p in world.players()) {
            if (p.isRemoved || p.connection == null || viewers.containsKey(p)) continue
            if (p.distanceToSqr(pos) > enterR) continue
            viewers[p] = world
            val out = ArrayList<ClientPacket>(3)
            addSpawnPackets(out)
            for (pk in out) batch.add(p, pk)
        }
    }

    /** Follows the host and re-sends the passenger list to viewers that just started tracking it. */
    internal fun tickAttach(batch: PacketBatch) {
        val h = host ?: return
        if (h.isRemoved) {
            if (dieWithHost) remove() else detachInternal(notify = false)
            return
        }
        pos = h.position()
        val lvl = h.level()
        if (lvl is ServerLevel && lvl !== world) {
            world = lvl
            viewers.clear()
            attachSeen.clear()
            DisplayManager.requestRecheck()
            return
        }
        if (viewers.isEmpty()) return
        val tracking: MutableSet<ServerPlayer> = Collections.newSetFromMap(IdentityHashMap())
        if (h is ServerPlayer) tracking.add(h)
        tracking.addAll(PlayerLookup.tracking(h))
        attachSeen.retainAll { it in tracking }
        var packet: ClientboundSetPassengersPacket? = null
        for (p in viewers.keys) {
            if (p !in tracking || !attachSeen.add(p)) continue
            batch.add(p, packet ?: ClientboundSetPassengersPacket(h).also { packet = it })
        }
    }

    /** One SetEntityData with everything that changed since the last flush, then the teleport. */
    internal fun flush(batch: PacketBatch) {
        val dirty = entity.entityData.packDirty()
        val start = startInterp
        startInterp = false
        val tele = teleportDirty
        teleportDirty = false
        if (viewers.isEmpty()) return
        var values: MutableList<SynchedEntityData.DataValue<*>>? = dirty?.toMutableList()
        if (start && entity is Display) {
            val startId = Display.DATA_TRANSFORMATION_INTERPOLATION_START_DELTA_TICKS_ID
            if (values == null) values = ArrayList(1)
            if (values.none { it.id() == startId.id }) values.add(SynchedEntityData.DataValue.create(startId, 0))
        }
        if (!values.isNullOrEmpty()) batch.addAll(viewers.keys, ClientboundSetEntityDataPacket(id, values))
        if (tele && host == null) {
            val packet = ClientboundTeleportEntityPacket(
                id, PositionMoveRotation(pos, Vec3.ZERO, yaw, pitch), emptySet(), false,
            )
            batch.addAll(viewers.keys, packet)
        }
    }

    internal fun fireClick(player: ServerPlayer, hand: InteractionHand) =
        call(onClick, "display onClick", player, LuaValue.valueOf(if (hand == InteractionHand.MAIN_HAND) "main" else "off"))

    internal fun fireAttack(player: ServerPlayer) = call(onAttack, "display onAttack", player, null)

    private fun call(fn: LuaFunction?, context: String, player: ServerPlayer, extra: LuaValue?) {
        if (fn == null) return
        try {
            ScriptWatchdog.guard {
                val p = ru.pyxiion.ignis.api.wrapper.PlayerWrap.wrap(player)
                if (extra == null) fn.call(p) else fn.call(p, extra)
            }
        } catch (e: Throwable) {
            ScriptErrors.error(context, e)
        }
    }
}

object DisplayManager {
    private const val RECHECK_TICKS = 10

    private val handles = LinkedHashMap<Int, DisplayHandle>()
    private val scratch = ArrayList<DisplayHandle>()

    /** Read on the network thread by the interact mixins. */
    private val virtualIds: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    private val passengersByHost = ConcurrentHashMap<Int, IntArray>()

    private var ticks = 0
    private var recheck = false

    internal fun register(handle: DisplayHandle) {
        handles[handle.id] = handle
        virtualIds.add(handle.id)
    }

    internal fun unregister(handle: DisplayHandle) {
        handles.remove(handle.id)
        virtualIds.remove(handle.id)
    }

    fun all(): List<DisplayHandle> = handles.values.toList()

    @JvmStatic
    fun isVirtual(id: Int): Boolean = id in virtualIds

    /** Virtual passengers of an entity: the SetPassengers mixin appends these to every vanilla packet. */
    @JvmStatic
    fun passengerIds(vehicleId: Int): IntArray? = passengersByHost[vehicleId]

    internal fun registerPassenger(hostId: Int, id: Int) {
        passengersByHost.merge(hostId, intArrayOf(id)) { a, b -> a + b }
    }

    internal fun unregisterPassenger(hostId: Int, id: Int) {
        passengersByHost.computeIfPresent(hostId) { _, a ->
            val rest = a.filter { it != id }.toIntArray()
            if (rest.isEmpty()) null else rest
        }
    }

    /** A player joined, respawned or changed world: the next tick re-checks every auto display. */
    fun requestRecheck() {
        recheck = true
    }

    /** Called on the server thread by the interact mixins. [hand] is null for an attack. */
    @JvmStatic
    fun onInteract(player: ServerPlayer, id: Int, hand: InteractionHand?) {
        val handle = handles[id] ?: return
        if (handle.kind != "interaction" || !handle.viewers.containsKey(player)) return
        if (hand == null) handle.fireAttack(player) else handle.fireClick(player, hand)
    }

    /** End of a server tick: viewers, attachments, one batched flush. */
    fun tick() {
        if (handles.isEmpty()) return
        ticks++
        val full = recheck || ticks % RECHECK_TICKS == 0
        recheck = false
        scratch.clear()
        scratch.addAll(handles.values)
        val batch = PacketBatch()
        for (h in scratch) {
            if (!h.valid) continue
            h.tickAttach(batch)
            if (!h.valid) continue
            val check = full || h.needsViewerCheck
            h.needsViewerCheck = false
            h.updateViewers(batch, check)
            h.flush(batch)
        }
        batch.send()
    }

    /** Reload / shutdown: every display disappears from every client. */
    fun closeAll() {
        for (h in handles.values.toList()) h.remove()
        handles.clear()
        virtualIds.clear()
        passengersByHost.clear()
    }
}
