package ru.pyxiion.ignis.api.util

import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import org.luaj.vm2.LuaValue
import ru.pyxiion.ignis.luaTableOf
import ru.pyxiion.ignis.api.wrapper.EntityFactory
import ru.pyxiion.ignis.api.vecTable
import kotlin.math.max
import kotlin.math.min

internal fun performRaycast(
    start: Vec3,
    end: Vec3,
    range: Double,
    includeFluids: Boolean,
    includeEntities: Boolean,
    world: Level,
    source: Entity?,
): LuaValue {
    val shapeCtx = if (source != null) CollisionContext.of(source) else CollisionContext.empty()

    val blockHit = world.clip(ClipContext(
        start, end,
        ClipContext.Block.OUTLINE,
        if (includeFluids) ClipContext.Fluid.ANY else ClipContext.Fluid.NONE,
        shapeCtx
    ))
    var blockDist = range
    if (blockHit.getType() == HitResult.Type.BLOCK) {
        blockDist = start.distanceTo(blockHit.getLocation())
    }

    var closestEntity: Entity? = null
    var closestEntityHit: Vec3? = null
    var closestDist = blockDist

    if (includeEntities) {
        val box = AABB(
            min(start.x, end.x), min(start.y, end.y), min(start.z, end.z),
            max(start.x, end.x), max(start.y, end.y), max(start.z, end.z)
        ).inflate(1.0)
        val nearby = world.getEntities(source, box) { true }

        for (target in nearby) {
            if (target == source || target !is LivingEntity) continue
            val targetBox = target.boundingBox.inflate(0.3)
            val hit = targetBox.clip(start, end).orElse(null) ?: continue
            val dist = start.distanceTo(hit)
            if (dist < closestDist) {
                closestDist = dist
                closestEntity = target
                closestEntityHit = hit
            }
        }
    }

    if (closestEntity != null && closestEntityHit != null) {
        return luaTableOf(
            "type" to LuaValue.valueOf("entity"),
            "entity" to EntityFactory.wrap(closestEntity),
            "hit" to vecTable(closestEntityHit.x, closestEntityHit.y, closestEntityHit.z),
        )
    }

    if (blockHit.getType() == HitResult.Type.BLOCK) {
        val pos = blockHit.getBlockPos()
        val side = blockHit.getDirection()
        val normal = side.getUnitVec3()
        return luaTableOf(
            "type" to LuaValue.valueOf("block"),
            "blockPos" to vecTable(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble()),
            "hit" to vecTable(blockHit.getLocation().x, blockHit.getLocation().y, blockHit.getLocation().z),
            "side" to LuaValue.valueOf(side.getName()),
            "normal" to vecTable(normal.x.toDouble(), normal.y.toDouble(), normal.z.toDouble()),
        )
    }

    return LuaValue.NIL
}
