package ru.pyxiion.ignis.events

/** A built-in event. Handlers get one argument: the event table `e` with the event's fields. */
class EventDef(val name: String, val cancellable: Boolean)

class EventCatalog(defs: List<EventDef>) {
    private val defs = defs.associateBy { it.name }

    val names: Collection<String> get() = defs.keys

    fun def(name: String): EventDef? = defs[name]

    /** The closest built-in event name to a misspelled [name], or null. */
    fun suggest(name: String): String? {
        val best = defs.keys.minByOrNull { distance(name, it) } ?: return null
        return best.takeIf { distance(name, it) <= maxOf(2, name.length / 3) }
    }

    companion object {
        /** Levenshtein distance; `_` and `.` count as the same character so `player_join` ~ `player.join`. */
        internal fun distance(a: String, b: String): Int {
            fun same(x: Char, y: Char) = x == y || (x == '_' || x == '.') && (y == '_' || y == '.')
            var prev = IntArray(b.length + 1) { it }
            var cur = IntArray(b.length + 1)
            for (i in 1..a.length) {
                cur[0] = i
                for (j in 1..b.length) {
                    val cost = if (same(a[i - 1], b[j - 1])) 0 else 1
                    cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                }
                val t = prev; prev = cur; cur = t
            }
            return prev[b.length]
        }
    }
}

/** Events of `mc.on`. Field lists are documented in `site/.../reference/events.md` and `lua-types/19-events.lua`. */
object GlobalEvents {
    val CATALOG = EventCatalog(
        listOf(
            EventDef("init", false),
            EventDef("uninit", false),
            EventDef("server_start", false),
            EventDef("server_stop", false),
            EventDef("tick", false),

            EventDef("player.login", true),
            EventDef("player.join", false),
            EventDef("player.leave", false),
            EventDef("player.respawn", false),
            EventDef("player.chat", true),
            EventDef("player.move", false),
            EventDef("player.use_item", true),
            EventDef("player.attack", true),
            EventDef("player.interact", true),
            EventDef("player.kill", false),
            EventDef("player.consume", true),
            EventDef("player.pickup", true),
            EventDef("player.drop", true),

            EventDef("block.break", true),
            EventDef("block.place", true),

            EventDef("entity.spawn", false),
            EventDef("entity.despawn", false),
            EventDef("entity.hurt", true),
            EventDef("entity.damaged", false),
            EventDef("entity.death", true),
        ),
    )
}

/** Events of `region:on`. Every event table also has `e.region`. */
object RegionEvents {
    val CATALOG = EventCatalog(
        listOf(
            EventDef("enter", false),
            EventDef("leave", false),
            EventDef("move", false),
            EventDef("death", false),
            EventDef("tick", false),
            EventDef("destroy", false),
        ),
    )
}
