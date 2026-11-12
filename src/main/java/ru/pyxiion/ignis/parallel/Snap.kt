package ru.pyxiion.ignis.parallel

import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Prototype

/**
 * A thread-neutral description of a Lua value. A [Snapshotter] builds it on the sending thread (a copy, never
 * touching live tables afterwards); a [Materializer] turns it into values of the receiving state.
 */
internal sealed interface Snap

/** nil, boolean, number or string: immutable, shared as is. */
internal class Prim(val v: LuaValue) : Snap

/** The globals table of whichever state materializes it. */
internal object GlobalsRef : Snap

/** A stdlib table or function, found again by [StdlibRegistry.resolve]. */
internal class StdPath(val path: Array<LuaValue>) : Snap

/** A frozen table: crosses by reference. */
internal class Ref(val table: LuaTable) : Snap

/** A plain table, copied. */
internal class Tbl(var keys: Array<Snap>, var values: Array<Snap>) : Snap

/** One upvalue; closures that shared it share the cell. */
internal class Cell(var value: Snap) : Snap

/** A Lua closure: the prototype is immutable and shared, the upvalues are copied. */
internal class Fn(val proto: Prototype, var cells: Array<Cell>) : Snap

internal class Vec(val x: Double, val y: Double, val z: Double) : Snap

internal class Shared(val obj: SharedObject) : Snap
