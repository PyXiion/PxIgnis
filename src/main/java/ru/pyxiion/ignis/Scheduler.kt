package ru.pyxiion.ignis

import org.luaj.vm2.LuaFunction
import ru.pyxiion.ignis.PxIgnis.Companion.logger
import java.util.PriorityQueue

class Scheduler {
    private var nextId = 0
    var currentTick = 0L
    private val tasks = PriorityQueue(compareBy<ScheduledTask> { it.fireAtTick })
    private val cancelledIds = HashSet<Int>()

    fun tick() {
        currentTick++

        // Limiting the cycle to avoid hanging the server
        while (tasks.isNotEmpty() && tasks.peek().fireAtTick <= currentTick) {
            val task = tasks.poll()

            if (task.id in cancelledIds) {
                cancelledIds.remove(task.id)
                continue
            }

            try {
                task.callback.call()
            } catch (e: Throwable) {
                logger.warn("Ошибка в задании планировщика #${task.id}: ${e.message}")
            }

            if (task.repeating && task.interval > 0) {
                tasks.offer(task.copy(fireAtTick = task.fireAtTick + task.interval))
            }
        }
    }

    fun schedule(delay: Int, callback: LuaFunction): Int {
        val id = nextId++
        tasks.offer(ScheduledTask(id, currentTick + delay.coerceAtLeast(0), 0, false, callback))
        return id
    }

    fun scheduleRepeating(delay: Int, interval: Int, callback: LuaFunction): Int {
        val id = nextId++

        val safeInterval = interval.coerceAtLeast(1)
        tasks.offer(
            ScheduledTask(id, currentTick + delay.coerceAtLeast(0), safeInterval, true, callback)
        )
        return id
    }

    fun cancel(id: Int): Boolean {
        if (id >= nextId) return false
        if (id in cancelledIds) return false
        cancelledIds.add(id)
        return true
    }

    fun clear() {
        tasks.clear()
        cancelledIds.clear()
    }
}

data class ScheduledTask(
    val id: Int,
    val fireAtTick: Long,
    val interval: Int,
    val repeating: Boolean,
    val callback: LuaFunction
)