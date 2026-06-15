package ru.pyxiion.ignis.storage

import java.util.concurrent.ConcurrentHashMap

class StorageManager(private val backend: DataBackend) {
    private val playerCache = ConcurrentHashMap<String, DataTable>()
    private var _global: DataTable? = null

    fun getPlayerData(uuid: String): DataTable {
        return playerCache.computeIfAbsent(uuid) { DataTable(backend, uuid) }
    }

    fun removePlayerData(uuid: String) {
        playerCache.remove(uuid)?.save()
    }

    fun getGlobalData(): DataTable {
        if (_global == null) {
            _global = DataTable(backend, "__global__")
        }
        return _global!!
    }

    fun saveAll() {
        playerCache.values.forEach {
            it.ensureLoaded()
            it.save()
        }
        _global?.let {
            it.ensureLoaded()
            it.save()
        }
    }

    fun close() {
        saveAll()
        backend.close()
    }
}
