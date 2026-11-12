package ru.pyxiion.ignis.api.manager

import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaFunction
import org.luaj.vm2.LuaState
import org.luaj.vm2.LuaValue
import ru.pyxiion.ignis.EventBus
import ru.pyxiion.ignis.PxIgnis
import ru.pyxiion.ignis.api.Vector
import ru.pyxiion.ignis.api.wrapper.EntityFactory
import java.util.UUID

/** A region may cover at most this many chunks (one index entry per chunk), e.g. 32 x 32 chunks = 512 x 512 blocks. */
const val MAX_REGION_CHUNKS: Long = 1024

class Region internal constructor(
    val id: Int,
    val world: ServerLevel,
    bounds: AABB,
) {
    internal val bus = EventBus(" region #$id", PxIgnis.logger) { RegionManager.sharedStateProvider() }
    private val contained = mutableSetOf<UUID>()

    var bounds: AABB = bounds
        private set

    var destroyed = false
        private set

    /** Every Lua-facing operation starts with this: a destroyed region is not usable. */
    fun requireAlive(): Region {
        if (destroyed) throw LuaError("регион #$id уничтожен")
        return this
    }

    fun contains(pos: Vec3): Boolean = bounds.contains(pos)

    fun on(event: String, callback: LuaFunction, throttle: Int = 0): Int {
        requireAlive()
        val handlerId = bus.on(event, callback, throttle)
        if (event == "tick") RegionManager.registerTickSubscriber(this)
        return handlerId
    }

    fun off(handlerId: Int): Boolean {
        requireAlive()
        val removed = bus.off(handlerId)
        if (removed && !bus.hasHandlers("tick")) {
            RegionManager.unregisterTickSubscriber(this)
        }
        return removed
    }

    internal fun fire(event: String, args: () -> Array<LuaValue>) = bus.fire(event, args)

    internal fun fire(event: String, vararg args: LuaValue) = bus.fire(event, *args)

    internal fun tick() = bus.tick()

    fun contains(uuid: UUID): Boolean = uuid in contained

    fun destroy() {
        if (destroyed) return
        fire("destroy")
        markDestroyed()
        RegionManager.remove(this)
    }

    /** Drops handlers and membership. The manager's own maps are the caller's business. */
    internal fun markDestroyed() {
        destroyed = true
        bus.clear()
        RegionManager.forgetMembership(this)
        contained.clear()
        RegionManager.unregisterTickSubscriber(this)
    }

    fun setCorners(a: Vec3, b: Vec3) {
        requireAlive()
        val newBounds = RegionManager.boundsOf(a, b)
        val old = bounds
        bounds = newBounds
        RegionManager.reindexChunks(this, old)

        val leaving = contained.filter { uuid ->
            val e = world.getEntity(uuid)
            e == null || !bounds.contains(e.position())
        }
        for (uuid in leaving) {
            RegionManager.unmarkContained(this, uuid)
            val e = world.getEntity(uuid) ?: continue
            fire("entity_leave", regionWrapperFor(e))
            if (e is ServerPlayer) fire("player_leave", regionWrapperFor(e))
        }

        for (e in RegionManager.entitiesInside(this)) {
            if (e.uuid in contained) continue
            RegionManager.markContained(this, e.uuid)
            fire("entity_enter", regionWrapperFor(e))
            if (e is ServerPlayer) fire("player_enter", regionWrapperFor(e))
        }
    }

    fun liveEntities(): List<Entity> {
        requireAlive()
        val world = this.world
        return contained.mapNotNull { world.getEntity(it) }
    }

    fun livePlayers(): List<ServerPlayer> {
        requireAlive()
        val world = this.world
        return contained.mapNotNull { world.getEntity(it) as? ServerPlayer }
    }

    internal fun addContainedRaw(uuid: UUID) { contained.add(uuid) }
    internal fun removeContainedRaw(uuid: UUID) { contained.remove(uuid) }
    internal fun isContained(uuid: UUID): Boolean = uuid in contained
    internal fun containedUuids(): Set<UUID> = contained.toSet()
}

object RegionManager {
    private val regionsByWorld = mutableMapOf<ServerLevel, MutableList<Region>>()
    private val regionsByChunk = mutableMapOf<ServerLevel, MutableMap<ChunkPos, MutableList<Region>>>()
    private val regionsById = mutableMapOf<Int, Region>()
    private val tickSubscribers = mutableSetOf<Region>()

    /** Reverse of [Region.containedUuids]: the regions that currently contain an entity. */
    private val containedIn = mutableMapOf<UUID, MutableSet<Region>>()
    private var nextId = 0

    var sharedStateProvider: () -> LuaState? = { null }

    /** True while at least one region exists; entity movement is not worth tracking otherwise. */
    val hasRegions: Boolean get() = regionsById.isNotEmpty()

    internal fun boundsOf(a: Vec3, b: Vec3): AABB {
        for (v in doubleArrayOf(a.x, a.y, a.z, b.x, b.y, b.z)) {
            if (v.isNaN() || v.isInfinite()) throw LuaError("регион: координаты должны быть конечными числами")
        }
        val bounds = AABB(
            Vec3(minOf(a.x, b.x), minOf(a.y, b.y), minOf(a.z, b.z)),
            Vec3(maxOf(a.x, b.x), maxOf(a.y, b.y), maxOf(a.z, b.z)),
        )
        val chunks = chunkCount(bounds)
        if (chunks > MAX_REGION_CHUNKS) {
            throw LuaError("регион покрывает $chunks чанков, максимум $MAX_REGION_CHUNKS")
        }
        return bounds
    }

    internal fun create(world: ServerLevel, a: Vec3, b: Vec3): Region {
        val bounds = boundsOf(a, b)
        val region = Region(nextId++, world, bounds)
        regionsByWorld.getOrPut(world) { mutableListOf() }.add(region)
        regionsById[region.id] = region
        indexChunks(region)
        // Entities already inside are members without an event: only later transitions are events.
        for (e in entitiesInside(region)) markContained(region, e.uuid)
        return region
    }

    fun all(world: ServerLevel): List<Region> = regionsByWorld[world]?.toList() ?: emptyList()

    fun get(id: Int): Region? = regionsById[id]

    fun getAt(world: ServerLevel, pos: Vec3): List<Region> {
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

    /** Reload: every region gets its `destroy` event and stops being usable. */
    fun closeAll() {
        val regions = regionsById.values.toList()
        regions.forEach { it.fire("destroy") }
        regions.forEach { it.markDestroyed() }
        regionsByWorld.clear()
        regionsByChunk.clear()
        regionsById.clear()
        tickSubscribers.clear()
        containedIn.clear()
    }

    fun tick() {
        for (r in tickSubscribers.toList()) {
            try {
                r.fire("tick")
            } catch (e: Throwable) {
                PxIgnis.logger.warn("Ошибка в tick региона #${r.id}: ${e.message}", e)
            }
            r.tick()
        }
    }

    internal fun registerTickSubscriber(region: Region) {
        tickSubscribers.add(region)
    }

    internal fun unregisterTickSubscriber(region: Region) {
        tickSubscribers.remove(region)
    }

    internal fun markContained(region: Region, uuid: UUID) {
        region.addContainedRaw(uuid)
        containedIn.getOrPut(uuid) { mutableSetOf() }.add(region)
    }

    internal fun unmarkContained(region: Region, uuid: UUID) {
        region.removeContainedRaw(uuid)
        containedIn[uuid]?.let {
            it.remove(region)
            if (it.isEmpty()) containedIn.remove(uuid)
        }
    }

    internal fun forgetMembership(region: Region) {
        for (uuid in region.containedUuids()) {
            containedIn[uuid]?.let {
                it.remove(region)
                if (it.isEmpty()) containedIn.remove(uuid)
            }
        }
    }

    internal fun entitiesInside(region: Region): List<Entity> {
        val result = ArrayList<Entity>()
        for (e in region.world.getAllEntities()) {
            if (region.bounds.contains(e.position())) result.add(e)
        }
        return result
    }

    /**
     * Enter and leave follow the membership set, not the geometry of one step: an entity that jumps far (teleport,
     * another dimension) leaves exactly the regions that held it and enters exactly the ones that now hold it.
     */
    fun onEntityMoved(entity: Entity, from: Vec3, to: Vec3) {
        val world = entity.level() as? ServerLevel ?: return
        val uuid = entity.uuid

        val candidates = LinkedHashSet<Region>()
        val chunkMap = regionsByChunk[world]
        if (chunkMap != null) {
            chunkMap[chunkPosFor(from)]?.let { candidates.addAll(it) }
            chunkMap[chunkPosFor(to)]?.let { candidates.addAll(it) }
        }
        containedIn[uuid]?.let { candidates.addAll(it) }
        if (candidates.isEmpty()) return

        val isPlayer = entity is ServerPlayer
        val wrapper by lazy { regionWrapperFor(entity) }
        val move by lazy {
            arrayOf(wrapper, Vector.of(from.x, from.y, from.z).toLuaValue(), Vector.of(to.x, to.y, to.z).toLuaValue())
        }

        for (region in candidates) {
            val wasIn = region.isContained(uuid)
            val isIn = region.world === world && region.bounds.contains(to)
            if (isIn && !wasIn) {
                markContained(region, uuid)
                region.fire("entity_enter") { arrayOf(wrapper) }
                if (isPlayer) region.fire("player_enter") { arrayOf(wrapper) }
            } else if (!isIn && wasIn) {
                unmarkContained(region, uuid)
                region.fire("entity_leave") { arrayOf(wrapper) }
                if (isPlayer) region.fire("player_leave") { arrayOf(wrapper) }
            } else if (isIn) {
                region.fire("entity_move") { move }
                if (isPlayer) region.fire("player_move") { move }
            }
        }
    }

    fun onEntityChunkLoad(entity: Entity) {
        val world = entity.level() as? ServerLevel ?: return
        val pos = entity.position()
        val chunkMap = regionsByChunk[world] ?: return
        val candidates = chunkMap[chunkPosFor(pos)] ?: return
        val uuid = entity.uuid
        val isPlayer = entity is ServerPlayer
        val wrapper by lazy { regionWrapperFor(entity) }
        for (region in candidates.toList()) {
            if (region.contains(pos) && !region.isContained(uuid)) {
                markContained(region, uuid)
                region.fire("entity_enter") { arrayOf(wrapper) }
                if (isPlayer) region.fire("player_enter") { arrayOf(wrapper) }
            }
        }
    }

    /** The entity leaves every region of this world that holds it, wherever it is now. */
    fun onEntityChunkUnload(entity: Entity) {
        val world = entity.level() as? ServerLevel ?: return
        val uuid = entity.uuid
        val holding = containedIn[uuid]?.filter { it.world === world } ?: return
        val isPlayer = entity is ServerPlayer
        val wrapper by lazy { regionWrapperFor(entity) }
        for (region in holding) {
            unmarkContained(region, uuid)
            region.fire("entity_leave") { arrayOf(wrapper) }
            if (isPlayer) region.fire("player_leave") { arrayOf(wrapper) }
        }
    }

    fun onEntityDeath(entity: Entity, sourceName: String, amount: Double) {
        val world = entity.level() as? ServerLevel ?: return
        val holding = containedIn[entity.uuid]?.filter { it.world === world } ?: return
        val isPlayer = entity is ServerPlayer
        val w by lazy { regionWrapperFor(entity) }
        val src = LuaValue.valueOf(sourceName)
        val amt = LuaValue.valueOf(amount)
        for (region in holding) {
            region.fire("entity_death") { arrayOf(w, src, amt) }
            if (isPlayer) region.fire("player_death") { arrayOf(w, src) }
        }
    }

    private fun indexChunks(region: Region) {
        val world = region.world
        val map = regionsByChunk.getOrPut(world) { mutableMapOf() }
        forChunkRange(region.bounds) { cp ->
            map.getOrPut(cp) { mutableListOf() }.add(region)
        }
    }

    internal fun reindexChunks(region: Region, oldBounds: AABB) {
        deindexChunks(region, oldBounds)
        indexChunks(region)
    }

    private fun deindexChunks(region: Region, bounds: AABB) {
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

    /** Chunk index of a block coordinate. `toInt()` rounds toward zero and puts -0.5 in chunk 0 instead of -1. */
    private fun chunkOf(coordinate: Double): Int = Math.floorDiv(Math.floor(coordinate).toInt(), 16)

    private fun chunkCount(bounds: AABB): Long {
        val dx = chunkOf(bounds.maxX) - chunkOf(bounds.minX) + 1L
        val dz = chunkOf(bounds.maxZ) - chunkOf(bounds.minZ) + 1L
        return dx * dz
    }

    private inline fun forChunkRange(bounds: AABB, action: (ChunkPos) -> Unit) {
        val minCx = chunkOf(bounds.minX)
        val minCz = chunkOf(bounds.minZ)
        val maxCx = chunkOf(bounds.maxX)
        val maxCz = chunkOf(bounds.maxZ)
        for (cx in minCx..maxCx) {
            for (cz in minCz..maxCz) {
                action(ChunkPos(cx, cz))
            }
        }
    }

    private fun chunkPosFor(pos: Vec3): ChunkPos = ChunkPos(chunkOf(pos.x), chunkOf(pos.z))
}

internal fun regionWrapperFor(entity: Entity): LuaValue {
    return EntityFactory.wrap(entity)
}
