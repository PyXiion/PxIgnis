package ru.pyxiion.ignis.api.manager

import java.util.UUID

/**
 * Who was sent a display's spawn packets, keyed by player UUID. Minecraft replaces the player object on respawn
 * while the client (connection) stays, so identity cannot tell who still holds the entity. Each entry remembers the
 * object and level that got the spawn; [live] resolves the current object when a packet has to go out.
 * Pure logic, no Minecraft types: unit-tested by `ViewerBookTest`.
 */
internal class ViewerBook<P : Any, L : Any>(private val uuidOf: (P) -> UUID) {
    class Entry<P : Any, L : Any>(val player: P, val level: L)

    enum class Fate {
        /** Still valid. */
        KEEP,

        /** Disconnected: nobody to tell. */
        DROP,

        /** Out of range: drop and send a remove. */
        REMOVE,

        /** Respawned or changed world: drop, send a remove, and let the next check decide visibility. */
        REMOVE_RECHECK,
    }

    private val entries = LinkedHashMap<UUID, Entry<P, L>>()

    val size: Int get() = entries.size
    fun isEmpty() = entries.isEmpty()
    fun contains(uuid: UUID) = entries.containsKey(uuid)
    fun contains(player: P) = entries.containsKey(uuidOf(player))
    fun uuids(): List<UUID> = entries.keys.toList()
    fun entry(uuid: UUID): Entry<P, L>? = entries[uuid]

    fun add(player: P, level: L) {
        entries[uuidOf(player)] = Entry(player, level)
    }

    fun remove(uuid: UUID): Entry<P, L>? = entries.remove(uuid)
    fun clear() = entries.clear()

    /** Current player object of every entry that still resolves; entries are not changed. */
    fun live(resolve: (UUID) -> P?): List<P> {
        val out = ArrayList<P>(entries.size)
        for (uuid in entries.keys) resolve(uuid)?.let(out::add)
        return out
    }

    companion object {
        fun fate(online: Boolean, sameObject: Boolean, sameLevel: Boolean, far: Boolean): Fate = when {
            !online -> Fate.DROP
            !sameObject || !sameLevel -> Fate.REMOVE_RECHECK
            far -> Fate.REMOVE
            else -> Fate.KEEP
        }
    }
}
