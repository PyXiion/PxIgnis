package ru.pyxiion.ignis.api

import net.minecraft.util.math.Vec3d
import org.luaj.vm2.LuaDouble
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaFunction
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.VarArgFunction
import ru.pyxiion.ignis.Compat
import ru.pyxiion.ignis.luaTableOf
import kotlin.math.sqrt

data class Vector(
    @JvmField
    val x: Double,
    @JvmField
    val y: Double,
    @JvmField
    val z: Double,
) {
    fun toLuaValue(): LuaValue {
        return vecTable(x, y, z)
    }

    companion object {
        fun fromMc(vec: Vec3d): Vector {
            return Vector(vec.x, vec.y, vec.z)
        }

        fun fromRotation(yaw: Float, pitch: Float): Vector {
            val f = pitch * (Math.PI / 180.0).toDouble()
            val g = -yaw * (Math.PI / 180.0).toDouble()
            val h = Compat.fastCos(g)
            val i = Compat.fastSin(g)
            val j = Compat.fastCos(f)
            val k = Compat.fastSin(f)
            return Vector((i * j).toDouble(), -k.toDouble(), (h * j).toDouble())
        }
    }
}

internal fun vecTable(x: Double, y: Double, z: Double): LuaTable {
    return luaTableOf(
        "x" to LuaValue.valueOf(x),
        "y" to LuaValue.valueOf(y),
        "z" to LuaValue.valueOf(z),
    ).also { it.setmetatable(MetaTableRegistry.VEC) }
}

internal fun resolveOperand(v: LuaValue): Triple<Double, Double, Double> {
    if (v.istable()) {
        val x = v.get("x")
        if (x.isnumber()) {
            return Triple(x.todouble(), v.get("y").checkdouble(), v.get("z").checkdouble())
        }
    }
    if (v.isnumber()) {
        val n = v.todouble()
        return Triple(n, n, n)
    }
    throw LuaError("Операнд должен быть вектором (таблица с x,y,z) или числом")
}

internal fun initVecMeta(meta: LuaTable) {
    meta.apply {
        set("__add", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val (x1, y1, z1) = resolveOperand(args.arg(1))
                val (x2, y2, z2) = resolveOperand(args.arg(2))
                return vecTable(x1 + x2, y1 + y2, z1 + z2)
            }
        })
        set("__sub", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val (x1, y1, z1) = resolveOperand(args.arg(1))
                val (x2, y2, z2) = resolveOperand(args.arg(2))
                return vecTable(x1 - x2, y1 - y2, z1 - z2)
            }
        })
        set("__mul", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val (x1, y1, z1) = resolveOperand(args.arg(1))
                val (x2, y2, z2) = resolveOperand(args.arg(2))
                return vecTable(x1 * x2, y1 * y2, z1 * z2)
            }
        })
        set("__div", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val (x1, y1, z1) = resolveOperand(args.arg(1))
                val v2 = args.arg(2)
                if (!v2.isnumber()) throw LuaError("Деление поддерживается только на число")
                val n = v2.todouble()
                if (n == 0.0) throw LuaError("Деление на ноль")
                return vecTable(x1 / n, y1 / n, z1 / n)
            }
        })
        set("__unm", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val v = args.checktable(1)
                return vecTable(
                    -v.get("x").checkdouble(),
                    -v.get("y").checkdouble(),
                    -v.get("z").checkdouble()
                )
            }
        })
        set("__eq", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val a = args.checktable(1)
                val b = args.checktable(2)
                return valueOf(
                    a.get("x").checkdouble() == b.get("x").checkdouble() &&
                            a.get("y").checkdouble() == b.get("y").checkdouble() &&
                            a.get("z").checkdouble() == b.get("z").checkdouble()
                )
            }
        })
        set("__tostring", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val v = args.checktable(1)
                return valueOf(
                    "(${v.get("x").todouble()}, ${v.get("y").todouble()}, ${
                        v.get("z").todouble()
                    })"
                )
            }
        })

        set("length", object : LuaFunction() {
            override fun call(arg1: LuaValue): LuaValue {
                val (x, y, z) = resolveOperand(arg1)
                return LuaValue.valueOf(sqrt(x*x+y*y+z*z))
            }
        })
    }
}
