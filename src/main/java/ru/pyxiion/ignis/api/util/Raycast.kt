package ru.pyxiion.ignis.api.util

import net.minecraft.block.ShapeContext
import net.minecraft.entity.Entity
import net.minecraft.entity.LivingEntity
import net.minecraft.util.hit.HitResult
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import net.minecraft.world.RaycastContext
import net.minecraft.world.World
import org.luaj.vm2.LuaValue
import ru.pyxiion.ignis.luaTableOf
import ru.pyxiion.ignis.api.wrapper.EntityWrap
import ru.pyxiion.ignis.api.vecTable
import kotlin.math.max
import kotlin.math.min

internal fun performRaycast(
    start: Vec3d,
    end: Vec3d,
    range: Double,
    includeFluids: Boolean,
    includeEntities: Boolean,
    world: World,
    source: Entity?,
): LuaValue {
    val shapeCtx = if (source != null) ShapeContext.of(source) else ShapeContext.absent()

    val blockHit = world.raycast(RaycastContext(
        start, end,
        RaycastContext.ShapeType.OUTLINE,
        if (includeFluids) RaycastContext.FluidHandling.ANY else RaycastContext.FluidHandling.NONE,
        shapeCtx
    ))
    var blockDist = range
    if (blockHit.type == HitResult.Type.BLOCK) {
        blockDist = start.distanceTo(blockHit.pos)
    }

    var closestEntity: Entity? = null
    var closestEntityHit: Vec3d? = null
    var closestDist = blockDist

    if (includeEntities) {
        val box = Box(
            min(start.x, end.x), min(start.y, end.y), min(start.z, end.z),
            max(start.x, end.x), max(start.y, end.y), max(start.z, end.z)
        ).expand(1.0)
        val nearby = world.getOtherEntities(source, box)

        for (target in nearby) {
            if (target == source || target !is LivingEntity) continue
            val targetBox = target.boundingBox.expand(0.3)
            val hit = targetBox.raycast(start, end).orElse(null) ?: continue
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
            "entity" to EntityWrap.wrap(closestEntity),
            "hit" to vecTable(closestEntityHit.x, closestEntityHit.y, closestEntityHit.z),
        )
    }

    if (blockHit.type == HitResult.Type.BLOCK) {
        val pos = blockHit.blockPos
        val side = blockHit.side
        val normal = side.unitVector
        return luaTableOf(
            "type" to LuaValue.valueOf("block"),
            "blockPos" to vecTable(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble()),
            "hit" to vecTable(blockHit.pos.x, blockHit.pos.y, blockHit.pos.z),
            "side" to LuaValue.valueOf(side.asString()),
            "normal" to vecTable(normal.x.toDouble(), normal.y.toDouble(), normal.z.toDouble()),
        )
    }

    return LuaValue.NIL
}
