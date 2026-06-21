package ru.pyxiion.ignis.api.manager

import net.minecraft.entity.Entity
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.Box
import net.minecraft.util.math.ChunkPos
import net.minecraft.util.math.Vec3d
import org.luaj.vm2.LuaFunction
import org.luaj.vm2.LuaValue
import ru.pyxiion.ignis.EventBus
import ru.pyxiion.ignis.PxIgnis
import ru.pyxiion.ignis.api.wrapper.EntityWrap
import ru.pyxiion.ignis.api.Vector
import ru.pyxiion.ignis.api.wrapper.PlayerWrap
import java.util.UUID

class Region internal constructor(
    val id: Int,
    val world: ServerWorld,
    @Volatile var bounds: Box,
) {
    internal val bus = EventBus(" region #$id", PxIgnis.logger)
    private val contained = mutableSetOf<UUID>()

    fun contains(pos: Vec3d): Boolean = bounds.contains(pos)

    fun on(event: String, callback: LuaFunction, throttle: Int = 0): Int {
        val handlerId = bus.on(event, callback, throttle)
        if (event == "tick") RegionManager.registerTickSubscriber(this)
        return handlerId
    }

    fun off(handlerId: Int): Boolean {
        val removed = bus.off(handlerId)
        if (removed && !bus.hasHandlers("tick")) {
            RegionManager.unregisterTickSubscriber(this)
        }
        return removed
    }

    internal fun fire(event: String, vararg args: LuaValue) = bus.fire(event, *args)

    internal fun tick() = bus.tick()

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
                fire("entity_leave", regionWrapperFor(e))
                if (e is ServerPlayerEntity) fire("player_leave", regionWrapperFor(e))
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
            fire("entity_enter", regionWrapperFor(e))
            if (e is ServerPlayerEntity) fire("player_enter", regionWrapperFor(e))
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

    internal fun create(world: ServerWorld, bounds: Box): Region {
        val region = Region(nextId++, world, bounds)
        regionsByWorld.getOrPut(world) { mutableListOf() }.add(region)
        regionsById[region.id] = region
        indexChunks(region)
        return region
    }

    fun all(world: ServerWorld): List<Region> = regionsByWorld[world]?.toList() ?: emptyList()

    fun get(id: Int): Region? = regionsById[id]

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

    fun tick() {
        tickSubscribers.forEach { r ->
            try {
                r.fire("tick")
            } catch (_: Throwable) { }
            r.tick()
        }
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
        val isPlayer = entity is ServerPlayerEntity

        for (region in candidates) {
            val wasIn = region.contains(from)
            val isIn = region.contains(to)
            if (isIn && !wasIn) {
                region.addContained(uuid)
                region.fire("entity_enter", regionWrapperFor(entity))
                if (isPlayer) region.fire("player_enter", regionWrapperFor(entity))
            } else if (!isIn && wasIn) {
                region.removeContained(uuid)
                region.fire("entity_leave", regionWrapperFor(entity))
                if (isPlayer) region.fire("player_leave", regionWrapperFor(entity))
            } else if (isIn) {
                val fromLua = Vector(from.x, from.y, from.z).toLuaValue()
                val toLua = Vector(to.x, to.y, to.z).toLuaValue()
                val w = regionWrapperFor(entity)
                region.fire("entity_move", w, fromLua, toLua)
                if (isPlayer) region.fire("player_move", w, fromLua, toLua)
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
        val isPlayer = entity is ServerPlayerEntity
        for (region in candidates) {
            if (region.contains(pos) && !region.isContained(uuid)) {
                region.addContained(uuid)
                region.fire("entity_enter", regionWrapperFor(entity))
                if (isPlayer) region.fire("player_enter", regionWrapperFor(entity))
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
        val isPlayer = entity is ServerPlayerEntity
        for (region in candidates) {
            if (region.isContained(uuid)) {
                region.removeContained(uuid)
                region.fire("entity_leave", regionWrapperFor(entity))
                if (isPlayer) region.fire("player_leave", regionWrapperFor(entity))
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
        val isPlayer = entity is ServerPlayerEntity
        val w = regionWrapperFor(entity)
        val src = LuaValue.valueOf(sourceName)
        val amt = LuaValue.valueOf(amount)
        for (region in candidates) {
            if (region.isContained(uuid)) {
                region.fire("entity_death", w, src, amt)
                if (isPlayer) region.fire("player_death", w, src)
            }
        }
    }

    fun onPlayerDeath(player: ServerPlayerEntity, sourceName: String) {
        onEntityDeath(player, sourceName, 0.0)
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

internal fun regionWrapperFor(entity: Entity): LuaValue {
    return when (entity) {
        is ServerPlayerEntity -> PlayerWrap.wrap(entity)
        else -> EntityWrap.wrap(entity)
    }
}
