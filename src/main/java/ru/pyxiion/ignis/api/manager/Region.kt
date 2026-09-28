package ru.pyxiion.ignis.api.manager

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.entity.Entity
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.Box
import net.minecraft.util.math.ChunkPos
import net.minecraft.util.math.Vec3d
import org.luaj.vm2.LuaFunction
import org.luaj.vm2.LuaState
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import ru.pyxiion.ignis.EventBus
import ru.pyxiion.ignis.HandlerOptions
import ru.pyxiion.ignis.PxIgnis
import ru.pyxiion.ignis.api.wrapper.EntityFactory
import ru.pyxiion.ignis.api.wrapper.RegionWrap
import ru.pyxiion.ignis.events.RegionEvents
import ru.pyxiion.ignis.api.Vector
import ru.pyxiion.ignis.network.RegionCapWarningPayload
import ru.pyxiion.ignis.network.RegionEntry
import ru.pyxiion.ignis.network.RegionInterestPayload
import ru.pyxiion.ignis.network.RegionRemovePayload
import ru.pyxiion.ignis.network.RegionSyncPayload
import ru.pyxiion.ignis.network.RegionUpsertPayload
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

const val REGION_INTEREST_RADIUS_CHUNKS: Int = 4
const val MAX_REGIONS_PER_PLAYER: Int = 256

data class RegionDiff(
    val additions: List<RegionEntry>,
    val removals: List<Int>,
    val capped: Boolean
)

class Region internal constructor(
    val id: Int,
    val world: ServerWorld,
    @Volatile var bounds: Box,
) {
    internal val bus = EventBus(
        "region #$id", PxIgnis.logger, { RegionManager.sharedStateProvider() }, RegionEvents.CATALOG, RegionManager::ticks,
    )
    private val contained = mutableSetOf<UUID>()

    fun contains(pos: Vec3d): Boolean = bounds.contains(pos)

    fun on(event: String, callback: LuaFunction, options: HandlerOptions = HandlerOptions.DEFAULT): Int {
        val handlerId = bus.on(event, callback, options)
        if (bus.hasHandlers("tick")) RegionManager.registerTickSubscriber(this)
        return handlerId
    }

    fun off(handlerId: Int): Boolean {
        val removed = bus.off(handlerId)
        if (removed && !bus.hasHandlers("tick")) {
            RegionManager.unregisterTickSubscriber(this)
        }
        return removed
    }

    private fun post(event: String, fill: (LuaTable) -> Unit = {}) {
        if (!bus.hasHandlers(event)) return
        bus.post(event) {
            it.rawset("region", RegionWrap.wrap(this))
            fill(it)
        }
    }

    private fun LuaTable.entity(entity: Entity) {
        val w = EntityFactory.wrap(entity)
        rawset("entity", w)
        if (entity is ServerPlayerEntity) rawset("player", w)
    }

    internal fun fire(event: String) = post(event)

    internal fun fireEnter(entity: Entity) = post("enter") { it.entity(entity) }

    internal fun fireLeave(entity: Entity) = post("leave") { it.entity(entity) }

    internal fun fireMove(entity: Entity, from: Vec3d, to: Vec3d) = post("move") {
        it.entity(entity)
        it.rawset("from", Vector.of(from.x, from.y, from.z).toLuaValue())
        it.rawset("to", Vector.of(to.x, to.y, to.z).toLuaValue())
    }

    internal fun fireDeath(entity: Entity, source: String, amount: Double) = post("death") {
        it.entity(entity)
        it.rawset("source", LuaValue.valueOf(source))
        it.rawset("amount", LuaValue.valueOf(amount))
    }

    fun contains(uuid: UUID): Boolean = uuid in contained

    fun destroy() {
        fire("destroy")
        RegionManager.remove(this)
        bus.clear()
        contained.clear()
        RegionManager.unregisterTickSubscriber(this)
    }

    fun setCorners(a: Vec3d, b: Vec3d) {
        val old = bounds
        val min = Vec3d(minOf(a.x, b.x), minOf(a.y, b.y), minOf(a.z, b.z))
        val max = Vec3d(maxOf(a.x, b.x), maxOf(a.y, b.y), maxOf(a.z, b.z))
        bounds = Box(min, max)
        RegionManager.reindexChunks(this, old)
        val toLeave = mutableListOf<UUID>()
        val toEnter = mutableListOf<UUID>()
        for (uuid in contained) {
            val e = world.getEntity(uuid) ?: run {
                toLeave.add(uuid)
                continue
            }
            if (!bounds.contains(e.entityPos)) toLeave.add(uuid)
        }
        for (uuid in toLeave) {
            contained.remove(uuid)
            val e = world.getEntity(uuid)
            if (e != null) {
                fireLeave(e)
            }
        }
        for (e in world.players) {
            if (e.uuid in contained) continue
            if (bounds.contains(e.entityPos)) {
                contained.add(e.uuid)
                toEnter.add(e.uuid)
            }
        }
        for (e in world.iterateEntities()) {
            if (e is ServerPlayerEntity) continue
            if (e.uuid in contained) continue
            if (bounds.contains(e.entityPos)) {
                contained.add(e.uuid)
                toEnter.add(e.uuid)
            }
        }
        for (uuid in toEnter) {
            val e = world.getEntity(uuid) ?: continue
            fireEnter(e)
        }
    }

    fun liveEntities(): List<Entity> {
        val world = this.world
        return contained.mapNotNull { world.getEntity(it) }
    }

    fun livePlayers(): List<ServerPlayerEntity> {
        val world = this.world
        return contained.mapNotNull { world.getEntity(it) as? ServerPlayerEntity }
    }

    internal fun addContained(uuid: UUID) { contained.add(uuid) }
    internal fun removeContained(uuid: UUID) { contained.remove(uuid) }
    internal fun isContained(uuid: UUID): Boolean = uuid in contained
    internal fun containedUuids(): Set<UUID> = contained.toSet()
}

object RegionManager {
    private val regionsByWorld = mutableMapOf<ServerWorld, MutableList<Region>>()
    private val regionsByChunk = mutableMapOf<ServerWorld, MutableMap<ChunkPos, MutableList<Region>>>()
    private val regionsById = mutableMapOf<Int, Region>()
    private val tickSubscribers = mutableSetOf<Region>()
    private var nextId = 0

    var sharedStateProvider: () -> LuaState? = { null }

    internal fun create(world: ServerWorld, bounds: Box): Region {
        val region = Region(nextId++, world, bounds)
        regionsByWorld.getOrPut(world) { mutableListOf() }.add(region)
        regionsById[region.id] = region
        indexChunks(region)
        return region
    }

    fun all(world: ServerWorld): List<Region> = regionsByWorld[world]?.toList() ?: emptyList()

    fun get(id: Int): Region? = regionsById[id]

    val count: Int get() = regionsById.size

    fun getAt(world: ServerWorld, pos: Vec3d): List<Region> {
        val chunk = chunkPosFor(pos)
        val chunkMap = regionsByChunk[world] ?: return emptyList()
        val candidates = chunkMap[chunk] ?: return emptyList()
        return candidates.filter { it.bounds.contains(pos) }
    }

    internal fun remove(region: Region) {
        val list = regionsByWorld[region.world] ?: return
        list.remove(region)
        if (list.isEmpty()) regionsByWorld.remove(region.world)
        deindexChunks(region, region.bounds)
        regionsById.remove(region.id)
        tickSubscribers.remove(region)
    }

    fun closeAll() {
        regionsByWorld.values.toList().forEach { list ->
            list.toList().forEach { r ->
                r.fire("destroy")
            }
        }
        regionsByWorld.clear()
        regionsByChunk.clear()
        regionsById.clear()
        tickSubscribers.clear()
    }

    /** Server ticks since start; the clock for region handlers' `throttle`. */
    var ticks = 0L
        private set

    fun tick() {
        ticks++
        tickSubscribers.toList().forEach { r ->
            try {
                r.fire("tick")
            } catch (_: Throwable) { }
        }
    }

    private val optInPlayers: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
    private val lastSnapshots: MutableMap<UUID, Map<Int, Box>> = ConcurrentHashMap()
    private val warnedPlayers: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    fun isOptedIn(uuid: UUID): Boolean = uuid in optInPlayers

    fun setOptedIn(uuid: UUID, enabled: Boolean) {
        if (enabled) {
            optInPlayers.add(uuid)
            lastSnapshots[uuid] = emptyMap()
            warnedPlayers.remove(uuid)
        } else {
            optInPlayers.remove(uuid)
            lastSnapshots.remove(uuid)
            warnedPlayers.remove(uuid)
        }
    }

    fun onPlayerLeft(uuid: UUID) {
        optInPlayers.remove(uuid)
        lastSnapshots.remove(uuid)
        warnedPlayers.remove(uuid)
    }

    fun tickClientSync(server: MinecraftServer) {
        if (optInPlayers.isEmpty()) return
        for (player in server.playerManager.playerList) {
            val uuid = player.uuid
            if (uuid !in optInPlayers) continue
            if (!ServerPlayNetworking.canSend(player, RegionInterestPayload.ID)) continue

            val current = computeVisibleRegions(player)
            val prev = lastSnapshots[uuid] ?: emptyMap()
            val diff = diffRegionSnapshots(prev, current, MAX_REGIONS_PER_PLAYER)

            for (entry in diff.additions) {
                ServerPlayNetworking.send(player, RegionUpsertPayload(entry.id, entry.box))
            }
            for (id in diff.removals) {
                ServerPlayNetworking.send(player, RegionRemovePayload(id))
            }
            if (diff.capped && warnedPlayers.add(uuid)) {
                ServerPlayNetworking.send(player, RegionCapWarningPayload(MAX_REGIONS_PER_PLAYER))
            }
            lastSnapshots[uuid] = current
        }
    }

    fun closeAll(server: MinecraftServer) {
        val uuids = optInPlayers.toList()
        optInPlayers.clear()
        lastSnapshots.clear()
        warnedPlayers.clear()
        for (uuid in uuids) {
            val player = server.playerManager.getPlayer(uuid) ?: continue
            ServerPlayNetworking.send(player, RegionSyncPayload(emptyList()))
        }
    }

    private fun computeVisibleRegions(player: ServerPlayerEntity): Map<Int, Box> {
        val world = player.entityWorld as? ServerWorld ?: return emptyMap()
        val chunkMap = regionsByChunk[world] ?: return emptyMap()
        val pc = ChunkPos(player.chunkPos.x, player.chunkPos.z)
        val r = REGION_INTEREST_RADIUS_CHUNKS
        val out = LinkedHashMap<Int, Box>()
        for (cx in (pc.x - r)..(pc.x + r)) {
            for (cz in (pc.z - r)..(pc.z + r)) {
                val list = chunkMap[ChunkPos(cx, cz)] ?: continue
                for (region in list) {
                    if (out.size >= MAX_REGIONS_PER_PLAYER && region.id !in out) continue
                    if (region.bounds.intersects(chunkBox(cx, cz))) {
                        out[region.id] = region.bounds
                    }
                }
            }
        }
        return out
    }

    private fun chunkBox(cx: Int, cz: Int): Box {
        val minX = (cx shl 4).toDouble()
        val minZ = (cz shl 4).toDouble()
        return Box(minX, Double.NEGATIVE_INFINITY, minZ, minX + 16.0, Double.POSITIVE_INFINITY, minZ + 16.0)
    }

    fun diffRegionSnapshots(
        prev: Map<Int, Box>,
        next: Map<Int, Box>,
        cap: Int
    ): RegionDiff {
        val additions = mutableListOf<RegionEntry>()
        val removals = mutableListOf<Int>()

        for ((id, box) in next) {
            val old = prev[id]
            if (old == null || old != box) {
                additions.add(RegionEntry(id, box))
            }
        }
        for (id in prev.keys) {
            if (id !in next.keys) removals.add(id)
        }

        val capped = next.size > cap
        val limited = if (capped) additions.take(cap) else additions
        return RegionDiff(limited, removals, capped)
    }

    internal fun registerTickSubscriber(region: Region) {
        tickSubscribers.add(region)
    }

    internal fun unregisterTickSubscriber(region: Region) {
        tickSubscribers.remove(region)
    }

    fun onEntityMoved(entity: Entity, from: Vec3d, to: Vec3d) {
        val world = entity.entityWorld as? ServerWorld ?: return
        val fromChunk = chunkPosFor(from)
        val toChunk = chunkPosFor(to)
        val chunkMap = regionsByChunk[world] ?: return

        val candidates = mutableSetOf<Region>()
        chunkMap[fromChunk]?.let { candidates.addAll(it) }
        chunkMap[toChunk]?.let { candidates.addAll(it) }

        val uuid = entity.uuid

        for (region in candidates) {
            val wasIn = region.contains(from)
            val isIn = region.contains(to)
            if (isIn && !wasIn) {
                region.addContained(uuid)
                region.fireEnter(entity)
            } else if (!isIn && wasIn) {
                region.removeContained(uuid)
                region.fireLeave(entity)
            } else if (isIn) {
                region.fireMove(entity, from, to)
            }
        }
    }

    fun onEntityChunkLoad(entity: Entity) {
        val world = entity.entityWorld as? ServerWorld ?: return
        val pos = entity.entityPos
        val chunk = chunkPosFor(pos)
        val chunkMap = regionsByChunk[world] ?: return
        val candidates = chunkMap[chunk] ?: return
        val uuid = entity.uuid
        for (region in candidates) {
            if (region.contains(pos) && !region.isContained(uuid)) {
                region.addContained(uuid)
                region.fireEnter(entity)
            }
        }
    }

    fun onEntityChunkUnload(entity: Entity) {
        val world = entity.entityWorld as? ServerWorld ?: return
        val pos = entity.entityPos
        val chunk = chunkPosFor(pos)
        val chunkMap = regionsByChunk[world] ?: return
        val candidates = chunkMap[chunk] ?: return
        val uuid = entity.uuid
        for (region in candidates) {
            if (region.isContained(uuid)) {
                region.removeContained(uuid)
                region.fireLeave(entity)
            }
        }
    }

    fun onEntityDeath(entity: Entity, sourceName: String, amount: Double) {
        val world = entity.entityWorld as? ServerWorld ?: return
        val pos = entity.entityPos
        val chunk = chunkPosFor(pos)
        val chunkMap = regionsByChunk[world] ?: return
        val candidates = chunkMap[chunk] ?: return
        val uuid = entity.uuid
        for (region in candidates.toList()) {
            if (region.isContained(uuid)) region.fireDeath(entity, sourceName, amount)
        }
    }

    private fun indexChunks(region: Region) {
        val world = region.world
        val map = regionsByChunk.getOrPut(world) { mutableMapOf() }
        forChunkRange(region.bounds) { cp ->
            map.getOrPut(cp) { mutableListOf() }.add(region)
        }
    }

    internal fun reindexChunks(region: Region, oldBounds: Box) {
        deindexChunks(region, oldBounds)
        indexChunks(region)
    }

    private fun deindexChunks(region: Region, bounds: Box) {
        val world = region.world
        val map = regionsByChunk[world] ?: return
        val toRemove = mutableListOf<ChunkPos>()
        forChunkRange(bounds) { cp ->
            val list = map[cp] ?: return@forChunkRange
            list.remove(region)
            if (list.isEmpty()) toRemove.add(cp)
        }
        toRemove.forEach { map.remove(it) }
        if (map.isEmpty()) regionsByChunk.remove(world)
    }

    private inline fun forChunkRange(bounds: Box, action: (ChunkPos) -> Unit) {
        val minCx = Math.floorDiv(bounds.minX.toInt(), 16)
        val minCz = Math.floorDiv(bounds.minZ.toInt(), 16)
        val maxCx = Math.floorDiv(bounds.maxX.toInt(), 16)
        val maxCz = Math.floorDiv(bounds.maxZ.toInt(), 16)
        for (cx in minCx..maxCx) {
            for (cz in minCz..maxCz) {
                action(ChunkPos(cx, cz))
            }
        }
    }

    private fun chunkPosFor(pos: Vec3d): ChunkPos {
        return ChunkPos(Math.floorDiv(pos.x.toInt(), 16), Math.floorDiv(pos.z.toInt(), 16))
    }
}

