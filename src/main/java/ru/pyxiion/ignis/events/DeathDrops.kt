package ru.pyxiion.ignis.events

import ru.pyxiion.luanova.vm2.LuaValue
import ru.pyxiion.ignis.EventResult

/** Reads the outcome of `entity.drops`. Pure: no Minecraft classes, so it is unit-tested. */
object DeathDrops {
    /** Returned for a cancelled event: no items, no equipment, no XP. */
    const val CANCELLED = -1

    /** What a death finally drops: [xp] orbs worth of XP and the [items] to spawn, in order. */
    class Outcome<T>(val xp: Int, val items: List<T>)

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

    /**
     * The stacks to spawn: the entries of the Lua list `e.drops` after the handlers ran, or [vanilla] when nobody
     * listened. Order is the list order (positive integer keys ascending, so a hole left by `e.drops[2] = nil` is
     * harmless). A script that erased the list (`e.drops = nil`) drops no items. Anything [unwrap] rejects — a
     * string, a number, a key that is not a list index — is skipped and reported through [bad]; the rest still drops.
     * A value that is not a table at all keeps [vanilla], also reported.
     */
    fun <T> items(result: EventResult, vanilla: List<T>, unwrap: (LuaValue) -> T?, bad: (LuaValue) -> Unit): List<T> {
        val event = result.event ?: return vanilla
        val list = event.rawget("drops")
        if (list.isnil()) return emptyList()
        if (!list.istable()) {
            bad(list)
            return vanilla
        }
        val table = list.checktable()
        val indexed = ArrayList<Pair<Int, T>>()
        for (key in table.keys()) {
            val value = table.rawget(key)
            val index = if (key.isint()) key.toint() else 0
            val item = if (index > 0) unwrap(value) else null
            if (item == null) bad(value) else indexed.add(index to item)
        }
        indexed.sortBy { it.first }
        return indexed.map { it.second }
    }

    /** Both parts together: null for a cancelled event, otherwise the XP and stacks to spawn. */
    fun <T> outcome(
        result: EventResult,
        vanillaXp: Int,
        vanillaItems: List<T>,
        unwrap: (LuaValue) -> T?,
        bad: (LuaValue) -> Unit,
    ): Outcome<T>? {
        val xp = xp(result, vanillaXp)
        if (xp == CANCELLED) return null
        return Outcome(xp, items(result, vanillaItems, unwrap, bad))
    }
}

/**
 * The stacks one dying entity would spawn, collected while vanilla runs its loot step. Pure: [owner] is whatever
 * identifies the dying entity, so the collecting logic is unit-tested without Minecraft.
 */
class DropBuffer<S>(val owner: Any, val previous: DropBuffer<S>? = null) {
    val items = ArrayList<S>()

    /** A player's inventory drop was held back during the capture; it runs after the event (see [DropCapture]). */
    var equipmentDeferred = false

    /** Takes [stack] when [spawner] is the dying entity; any other entity spawns normally. */
    fun offer(spawner: Any, stack: S): Boolean {
        if (spawner !== owner) return false
        items.add(stack)
        return true
    }
}
