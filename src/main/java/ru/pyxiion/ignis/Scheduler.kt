package ru.pyxiion.ignis

import org.luaj.vm2.LuaClosure
import org.luaj.vm2.LuaFunction
import org.luaj.vm2.LuaState
import org.luaj.vm2.LuaThread
import org.luaj.vm2.LuaValue
import ru.pyxiion.ignis.runtime.ScriptErrors
import ru.pyxiion.ignis.runtime.ScriptWatchdog
import java.util.PriorityQueue
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class Scheduler(private val stateProvider: () -> LuaState) {
    private companion object {
        private const val MAX_TASKS_PER_TICK = 1024
    }
    private val lock = ReentrantLock()
    private var nextId = 0
    @Volatile
    var currentTick = 0L
    private val tasks = PriorityQueue(compareBy<ScheduledTask> { it.fireAtTick })
    private val cancelledIds = HashSet<Int>()

    fun tick() {
        val due = ArrayList<ScheduledTask>()
        lock.withLock {
            currentTick++
            var processed = 0
            while (tasks.isNotEmpty() && tasks.peek().fireAtTick <= currentTick && processed < MAX_TASKS_PER_TICK) {
                val task = tasks.poll()
                processed++

                if (task.id in cancelledIds) {
                    cancelledIds.remove(task.id)
                    continue
                }

                if (task.repeating && task.interval > 0) {
                    tasks.offer(task.copy(fireAtTick = task.fireAtTick + task.interval))
                }
                due.add(task)
            }
        }

        val state = stateProvider()
        for (task in due) {
            try {
                val cb = task.callback
                if (cb is LuaClosure) {
                    LuaThread(state, cb).resumeOrLog(LuaValue.NONE, "scheduled task #${task.id}")
                } else {
                    ScriptWatchdog.guard { cb.call() }
                }
            } catch (e: Throwable) {
                ScriptErrors.error("scheduled task #${task.id}", e)
            }
        }
    }

    fun schedule(delay: Int, callback: LuaFunction): Int = lock.withLock {
        val id = nextId++
        tasks.offer(ScheduledTask(id, currentTick + delay.coerceAtLeast(0), 0, false, callback))
        id
    }

    fun scheduleRepeating(delay: Int, interval: Int, callback: LuaFunction): Int = lock.withLock {
        val id = nextId++
        val safeInterval = interval.coerceAtLeast(1)
        tasks.offer(
            ScheduledTask(id, currentTick + delay.coerceAtLeast(0), safeInterval, true, callback)
        )
        id
    }

    fun cancel(id: Int): Boolean = lock.withLock {
        if (id >= nextId) return@withLock false
        if (id in cancelledIds) return@withLock false
        cancelledIds.add(id)
        true
    }

    /** Tasks waiting to run (cancelled ones that were not reached yet are not counted). */
    fun pendingCount(): Int = lock.withLock { tasks.count { it.id !in cancelledIds } }

    fun clear() {
        lock.withLock {
            tasks.clear()
            cancelledIds.clear()
            nextId = 0
        }
    }
}

data class ScheduledTask(
    val id: Int,
    val fireAtTick: Long,
    val interval: Int,
    val repeating: Boolean,
    val callback: LuaFunction
)
