package ru.pyxiion.ignis.api

import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaUserdata
import org.luaj.vm2.LuaValue
import ru.pyxiion.ignis.Compat
import ru.pyxiion.ignis.toLua
import ru.pyxiion.ignis.luaFunction
import ru.pyxiion.ignis.luaFunctionNil
import kotlin.math.sqrt

class Vector private constructor(
    @JvmField
    var x: Double,
    @JvmField
    var y: Double,
    @JvmField
    var z: Double,
) : LuaUserdata(this) {

    init {
        setmetatable(MetaTableRegistry.VEC)
    }

    fun toLuaValue(): LuaValue {
        return this
    }

    operator fun component1(): Double = x
    operator fun component2(): Double = y
    operator fun component3(): Double = z

    companion object {
        val X = valueOf("x")
        val Y = valueOf("y")
        val Z = valueOf("z")

        val ZERO by lazy { Vector(0.0, 0.0, 0.0) }

        fun of(x: Double, y: Double, z: Double): Vector {
            if (x == 0.0 && y == 0.0 && z == 0.0) {
                return ZERO
            }
            return Vector(x, y, z)
        }

        fun fromMc(vec: Vec3): Vector {
            return of(vec.x, vec.y, vec.z)
        }

        fun fromRotation(yaw: Float, pitch: Float): Vector {
            val f = pitch * (Math.PI / 180.0).toDouble()
            val g = -yaw * (Math.PI / 180.0).toDouble()
            val h = Compat.fastCos(g)
            val i = Compat.fastSin(g)
            val j = Compat.fastCos(f)
            val k = Compat.fastSin(f)
            return of((i * j).toDouble(), -k.toDouble(), (h * j).toDouble())
        }


        fun LuaValue.toVec3d(): Vec3 {
            if (this is Vector) {
                return Vec3(this.x, this.y, this.z)
            }
            if (istable()) {
                val t = checktable()
                val x = t.get("x").let { if (it.isnumber()) it.todouble() else t.get(1).checkdouble() }
                val y = t.get("y").let { if (it.isnumber()) it.todouble() else t.get(2).checkdouble() }
                val z = t.get("z").let { if (it.isnumber()) it.todouble() else t.get(3).checkdouble() }
                return Vec3(x, y, z)
            }

            throw LuaError("$this is not a Vector. Vector must be either Vector type, {x=x, y=y, z=z} table, or {x, y, z} array.")
        }

        fun LuaValue.toBlockPos(): BlockPos {
            val v = toVec3d()
            return BlockPos.containing(v.x, v.y, v.z)
        }
    }
}

internal fun vecTable(x: Double, y: Double, z: Double): LuaValue {
    return Vector.of(x, y, z)
}

internal fun resolveOperand(v: LuaValue): Vector {
    if (v is Vector) {
        return v
    }
    if (v.istable()) {
        val x = v.get("x")
        if (x.isnumber()) {
            return Vector.of(x.checkdouble(), v.get("y").checkdouble(), v.get("z").checkdouble())
        }
    }
    if (v.isnumber()) {
        val n = v.todouble()
        return Vector.of(n, n, n)
    }
    throw LuaError("Операнд должен быть вектором (таблица с x,y,z) или числом")
}

internal fun initVecMeta(meta: LuaTable) {
    meta.apply {
        set("__index", luaFunction { self, key ->
            val s = self.checkuserdata(Vector::class.java) as Vector

            when (key) {
                Vector.X -> s.x.toLua()
                Vector.Y -> s.y.toLua()
                Vector.Z -> s.z.toLua()
                else -> meta.get(key)
            }
        })

        set("__newindex", luaFunctionNil { self, key, value ->
            val s = self.checkuserdata(Vector::class.java) as Vector
            val v = value.checkdouble()

            when (key) {
                Vector.X -> s.x = v
                Vector.Y -> s.y = v
                Vector.Z -> s.z = v
            }
        })

        set("__add", luaFunction { a, b ->
            val (x1, y1, z1) = resolveOperand(a)
            val (x2, y2, z2) = resolveOperand(b)
            vecTable(x1 + x2, y1 + y2, z1 + z2)
        })
        set("__sub", luaFunction { a, b ->
            val (x1, y1, z1) = resolveOperand(a)
            val (x2, y2, z2) = resolveOperand(b)
            vecTable(x1 - x2, y1 - y2, z1 - z2)
        })
        set("__mul", luaFunction { a, b ->
            val (x1, y1, z1) = resolveOperand(a)
            val (x2, y2, z2) = resolveOperand(b)
            vecTable(x1 * x2, y1 * y2, z1 * z2)
        })
        set("__div", luaFunction { a, b ->
            val (x1, y1, z1) = resolveOperand(a)
            if (!b.isnumber()) throw LuaError("Деление поддерживается только на число")
            val n = b.todouble()
            if (n == 0.0) throw LuaError("Деление на ноль")
            vecTable(x1 / n, y1 / n, z1 / n)
        })
        set("__unm", luaFunction { a ->
            val (x, y, z) = resolveOperand(a)
            vecTable(-x, -y, -z)
        })
        set("__eq", luaFunction { a, b ->
            val (x1, y1, z1) = resolveOperand(a)
            val (x2, y2, z2) = resolveOperand(b)
            LuaValue.valueOf(x1 == x2 && y1 == y2 && z1 == z2)
        })
        set("__tostring", luaFunction { a ->
            val (x, y, z) = resolveOperand(a)
            LuaValue.valueOf("($x,$y,$z)")
        })

        set("length", luaFunction { a ->
            val (x, y, z) = resolveOperand(a)
            LuaValue.valueOf(sqrt(x * x + y * y + z * z))
        })

        set("lengthSq", luaFunction { a ->
            val (x, y, z) = resolveOperand(a)
            LuaValue.valueOf(x * x + y * y + z * z)
        })

        set("distance", luaFunction { a, b ->
            val (x1, y1, z1) = resolveOperand(a)
            val (x2, y2, z2) = resolveOperand(b)
            val dx = x1 - x2
            val dy = y1 - y2
            val dz = z1 - z2
            LuaValue.valueOf(sqrt(dx * dx + dy * dy + dz * dz))
        })

        set("distanceSq", luaFunction { a, b ->
            val (x1, y1, z1) = resolveOperand(a)
            val (x2, y2, z2) = resolveOperand(b)
            val dx = x1 - x2
            val dy = y1 - y2
            val dz = z1 - z2
            LuaValue.valueOf(dx * dx + dy * dy + dz * dz)
        })

        set("normalized", luaFunction { a ->
            val (x, y, z) = resolveOperand(a)
            val length = sqrt(x * x + y * y + z * z)
            if (length == 0.0) return@luaFunction vecTable(0.0, 0.0, 0.0)
            vecTable(x / length, y / length, z / length)
        })

        set("dot", luaFunction { a, b ->
            val (x1, y1, z1) = resolveOperand(a)
            val (x2, y2, z2) = resolveOperand(b)
            LuaValue.valueOf(x1 * x2 + y1 * y2 + z1 * z2)
        })

        set("cross", luaFunction { a, b ->
            val (x1, y1, z1) = resolveOperand(a)
            val (x2, y2, z2) = resolveOperand(b)
            vecTable(
                y1 * z2 - z1 * y2,
                z1 * x2 - x1 * z2,
                x1 * y2 - y1 * x2
            )
        })
    }
}
