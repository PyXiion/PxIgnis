package ru.pyxiion.ignis.client

import net.minecraft.world.phys.AABB
import ru.pyxiion.ignis.network.RegionEntry
import java.util.concurrent.ConcurrentHashMap

object ClientRegionRegistry {
    private val regions: ConcurrentHashMap<Int, AABB> = ConcurrentHashMap()

    @Volatile
    var enabled: Boolean = false
        private set

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    fun upsert(id: Int, box: AABB) {
        regions[id] = box
    }

    fun remove(id: Int) {
        regions.remove(id)
    }

    fun replaceAll(entries: List<RegionEntry>) {
        regions.clear()
        for (entry in entries) {
            regions[entry.id] = entry.box
        }
    }

    fun snapshot(): Map<Int, AABB> = regions.toMap()

    fun values(): Collection<AABB> = regions.values

    fun clear() {
        regions.clear()
    }
}
