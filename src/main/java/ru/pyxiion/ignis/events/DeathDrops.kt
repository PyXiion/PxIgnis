package ru.pyxiion.ignis.events

import ru.pyxiion.luanova.vm2.LuaValue
import ru.pyxiion.ignis.EventResult

/** Reads the outcome of `entity.drops`. Pure: no Minecraft classes, so it is unit-tested. */
object DeathDrops {
    /** Returned for a cancelled event: no items, no equipment, no XP. */
    const val CANCELLED = -1

    /**
     * The XP to drop, or [CANCELLED] when the handlers cancelled the event. [default] is the vanilla amount, used
     * when no handler wrote a usable `e.xp`; a negative number counts as 0.
     */
    fun xp(result: EventResult, default: Int): Int {
        if (result.cancelled) return CANCELLED
        val v: LuaValue = result.event?.rawget("xp") ?: return default
        if (!v.isnumber()) return default
        val d = v.todouble()
        if (d.isNaN()) return default
        return if (d <= 0.0) 0 else minOf(d, Int.MAX_VALUE.toDouble()).toInt()
    }
}
