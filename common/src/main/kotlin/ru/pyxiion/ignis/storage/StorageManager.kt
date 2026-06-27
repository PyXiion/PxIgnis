package ru.pyxiion.ignis.storage

import java.util.concurrent.ConcurrentHashMap

class StorageManager(private val backend: DataBackend) {
    private val playerCache = ConcurrentHashMap<String, DataTable>()
    private val globalData = DataTable(backend, "__global__")

    fun getPlayerData(uuid: String): DataTable {
        return playerCache.computeIfAbsent(uuid) { DataTable(backend, uuid) }
    }

    fun removePlayerData(uuid: String) {
        playerCache.remove(uuid)?.save()
    }

    fun getGlobalData(): DataTable = globalData

    fun saveAll() {
        playerCache.values.forEach {
            it.ensureLoaded()
            it.save()
        }
        globalData.ensureLoaded()
        globalData.save()
    }

    fun close() {
        saveAll()
        backend.close()
    }
}
