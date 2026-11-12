package ru.pyxiion.ignis.events

import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue

/** A built-in event. Handlers get one argument: the event table `e` with the event's fields. */
class EventDef(val name: String, val cancellable: Boolean)

/**
 * A pre-1.0 event name kept for old scripts. Handlers registered under it are attached to [target] and get
 * positional arguments built from the event table by [args]; [accepts] filters out events the old name did
 * not cover (e.g. `player_hurt` only saw players, while `entity.hurt` sees every entity).
 */
class LegacyEvent(
    val name: String,
    val target: String,
    val accepts: (LuaTable) -> Boolean = { true },
    val args: (LuaTable) -> Array<LuaValue>,
)

class EventCatalog(defs: List<EventDef>, legacy: List<LegacyEvent>) {
    private val defs = defs.associateBy { it.name }
    private val legacy = legacy.associateBy { it.name }

    init {
        legacy.forEach { require(it.target in this.defs) { "legacy event ${it.name} targets unknown ${it.target}" } }
    }

    val isEmpty: Boolean get() = defs.isEmpty()
    val names: Collection<String> get() = defs.keys

    fun def(name: String): EventDef? = defs[name]
    fun legacy(name: String): LegacyEvent? = legacy[name]
    fun isBuiltIn(name: String): Boolean = name in defs || name in legacy

    /** The closest built-in event name to a misspelled [name] (legacy names suggest their replacement), or null. */
    fun suggest(name: String): String? {
        var best: String? = null
        var bestDistance = Int.MAX_VALUE
        for (candidate in defs.keys + legacy.keys) {
            val d = distance(name, candidate)
            if (d < bestDistance) {
                bestDistance = d
                best = candidate
            }
        }
        if (best == null || bestDistance > maxOf(2, name.length / 3)) return null
        return legacy[best]?.target ?: best
    }

    companion object {
        val EMPTY = EventCatalog(emptyList(), emptyList())

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

private fun LuaTable.f(key: String): LuaValue = rawget(key)
private fun LuaTable.isPlayer(): Boolean = !rawget("player").isnil()
private fun fields(vararg keys: String): (LuaTable) -> Array<LuaValue> = { e -> Array(keys.size) { e.f(keys[it]) } }

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
        listOf(
            LegacyEvent("player_join_init", "player.login", args = fields("player")),
            LegacyEvent("player_join", "player.join", args = fields("player")),
            LegacyEvent("player_leave", "player.leave", args = fields("player")),
            LegacyEvent("player_respawn", "player.respawn", args = fields("player", "alive")),
            LegacyEvent("player_chat", "player.chat", args = fields("player", "message")),
            LegacyEvent("player_move", "player.move", args = fields("player", "from", "to")),
            LegacyEvent("player_use_item", "player.use_item", args = fields("player", "hand", "item", "itemId")),
            LegacyEvent("player_attack_entity", "player.attack", args = fields("player", "target")),
            LegacyEvent("player_interact_entity", "player.interact", args = fields("player", "target", "hand")),
            LegacyEvent("player_kill", "player.kill", args = fields("player", "target", "source")),
            LegacyEvent("player_consume_item", "player.consume", args = fields("player", "item")),
            LegacyEvent("player_pickup_item", "player.pickup", args = fields("player", "item", "count")),
            LegacyEvent("player_drop_item", "player.drop", args = fields("player", "item", "count")),
            LegacyEvent("player_block_break", "block.break", args = fields("player", "pos", "block")),
            LegacyEvent("player_block_place", "block.place", args = fields("player", "pos", "block")),
            LegacyEvent("entity_spawn", "entity.spawn", args = fields("entity")),
            LegacyEvent("entity_despawn", "entity.despawn", args = fields("entity")),
            LegacyEvent("player_hurt", "entity.hurt", { it.isPlayer() }, fields("player", "source", "amount")),
            LegacyEvent("entity_hurt", "entity.hurt", { !it.isPlayer() }, fields("entity", "source", "amount", "attacker")),
            LegacyEvent("player_damage", "entity.damaged", { it.isPlayer() }, fields("player", "source", "amount", "blocked")),
            LegacyEvent("entity_damage", "entity.damaged", { !it.isPlayer() }, fields("entity", "source", "amount", "attacker", "blocked")),
            LegacyEvent("entity_death", "entity.death", args = fields("entity", "source", "amount")),
            LegacyEvent("player_death", "entity.death", { it.isPlayer() }, fields("entity", "source", "amount")),
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
        listOf(
            LegacyEvent("entity_enter", "enter", args = fields("entity")),
            LegacyEvent("player_enter", "enter", { it.isPlayer() }, fields("entity")),
            LegacyEvent("entity_leave", "leave", args = fields("entity")),
            LegacyEvent("player_leave", "leave", { it.isPlayer() }, fields("entity")),
            LegacyEvent("entity_move", "move", args = fields("entity", "from", "to")),
            LegacyEvent("player_move", "move", { it.isPlayer() }, fields("entity", "from", "to")),
            LegacyEvent("entity_death", "death", args = fields("entity", "source", "amount")),
            LegacyEvent("player_death", "death", { it.isPlayer() }, fields("entity", "source")),
        ),
    )
}
