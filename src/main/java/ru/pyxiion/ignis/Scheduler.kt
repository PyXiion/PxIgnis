package ru.pyxiion.ignis

import ru.pyxiion.luanova.vm2.LuaClosure
import ru.pyxiion.luanova.vm2.LuaFunction
import ru.pyxiion.luanova.vm2.LuaState
import ru.pyxiion.luanova.vm2.LuaThread
import ru.pyxiion.luanova.vm2.LuaValue
import ru.pyxiion.ignis.runtime.ScriptErrors
import ru.pyxiion.ignis.runtime.ScriptWatchdog
import java.util.PriorityQueue
import org.slf4j.LoggerFactory

/** Server-thread only: Lua runs on one thread, so there is no locking. */
class Scheduler(private val stateProvider: () -> LuaState) {
    private companion object {
        private const val MAX_TASKS_PER_TICK = 1024
    }

    private val log = LoggerFactory.getLogger("pxignis")
    private var nextId = 0
    var currentTick = 0L
        private set
    private val tasks = PriorityQueue(compareBy<ScheduledTask> { it.fireAtTick })
    private val cancelledIds = HashSet<Int>()

    fun tick() {
        val due = ArrayList<ScheduledTask>()
        currentTick++
        ScriptWatchdog.resetIfLeaked()
        var processed = 0
        while (tasks.isNotEmpty() && tasks.peek().fireAtTick <= currentTick && processed < MAX_TASKS_PER_TICK) {
            val task = tasks.poll()
            processed++

            if (task.id in cancelledIds) {
                cancelledIds.remove(task.id)
                continue
            }

            if (task.repeating && task.interval > 0) {
                // From the later of "was due" and "now": a backlog of overdue repeats must not pile up and starve
                // tasks scheduled after it (the per-tick cap below handles the oldest first).
                tasks.offer(task.copy(fireAtTick = maxOf(task.fireAtTick + task.interval, currentTick + 1)))
            }
            due.add(task)
        }

        if (processed >= MAX_TASKS_PER_TICK && tasks.isNotEmpty() && tasks.peek().fireAtTick <= currentTick) {
            ScriptErrors.error("scheduler", "more than $MAX_TASKS_PER_TICK tasks are due in one tick; the rest wait for the next ones")
        }
        val state = try {
            stateProvider()
        } catch (e: Throwable) {
            ScriptErrors.error("scheduler", e)
            return
        }
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

    fun schedule(delay: Int, callback: LuaFunction): Int {
        val id = nextId++
        tasks.offer(ScheduledTask(id, currentTick + delay.coerceAtLeast(0), 0, false, callback))
        return id
    }

    fun scheduleRepeating(delay: Int, interval: Int, callback: LuaFunction): Int {
        val id = nextId++
        val safeInterval = interval.coerceAtLeast(1)
        if (log.isDebugEnabled) log.debug("scheduler: repeating task #{} every {} ticks (delay {}), {} pending", id, safeInterval, delay, tasks.size + 1)
        tasks.offer(ScheduledTask(id, currentTick + delay.coerceAtLeast(0), safeInterval, true, callback))
        return id
    }

    /** `true` if a pending task was cancelled; `false` for an unknown id, a task that already ran, or a repeat of it. */
    fun cancel(id: Int): Boolean {
        if (tasks.none { it.id == id }) return false
        if (log.isDebugEnabled) log.debug("scheduler: task #{} cancelled", id)
        return cancelledIds.add(id)
    }

    /** Tasks waiting to run (cancelled ones that were not reached yet are not counted). */
    fun pendingCount(): Int = tasks.count { it.id !in cancelledIds }

    fun clear() {
        if (log.isDebugEnabled) log.debug("scheduler: cleared {} pending tasks", tasks.size)
        tasks.clear()
        cancelledIds.clear()
        nextId = 0
    }
}

data class ScheduledTask(
    val id: Int,
    val fireAtTick: Long,
    val interval: Int,
    val repeating: Boolean,
    val callback: LuaFunction
)
