package ru.pyxiion.ignis.storage

import com.google.gson.JsonObject
import org.luaj.vm2.LuaTable
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Persistent tables: plain [LuaTable]s read from disk on first access. Values are validated and
 * written by [save]; an invalid value fails there with its path, not at assignment.
 * Lua runs on the server thread only, so there is no locking here.
 */
class StorageManager(private val backend: JsonBackend) {
    private class Entry(val table: LuaTable, var lastSaved: JsonObject)

    private val entries = HashMap<String, Entry>()

    fun getPlayerData(uuid: String): LuaTable = entry(uuid).table

    fun getGlobalData(): LuaTable = entry(JsonBackend.GLOBAL_KEY).table

    /** Saves and forgets the player's table. A failed save is logged and the table is dropped anyway. */
    fun removePlayerData(uuid: String) {
        try {
            save(uuid)
        } catch (e: Exception) {
            logger.error("Не удалось сохранить данные игрока $uuid", e)
        }
        entries.remove(uuid)
    }

    /** One failing table does not stop the others. */
    fun saveAll() {
        for (key in entries.keys.toList()) {
            try {
                save(key)
            } catch (e: Exception) {
                logger.error("Не удалось сохранить данные '$key'", e)
            }
        }
    }

    fun close() = saveAll()

    private fun entry(key: String): Entry = entries.getOrPut(key) {
        val table = LuaJson.fromJson(backend.load(key)).checktable()
        Entry(table, LuaJson.toJsonObject(table, rootName(key)))
    }

    private fun save(key: String) {
        val entry = entries[key] ?: return
        val json = LuaJson.toJsonObject(entry.table, rootName(key))
        if (json == entry.lastSaved) return
        backend.save(key, json)
        entry.lastSaved = json
    }

    private fun rootName(key: String) = if (key == JsonBackend.GLOBAL_KEY) "mc.data" else "player.data"

    companion object {
        private val logger: Logger = LoggerFactory.getLogger("PxRP/Storage")
    }
}
