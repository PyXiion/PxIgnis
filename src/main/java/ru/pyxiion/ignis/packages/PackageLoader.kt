package ru.pyxiion.ignis.packages

import ru.pyxiion.ignis.runtime.ScriptErrors
import ru.pyxiion.ignis.runtime.ScriptWatchdog
import ru.pyxiion.luanova.vm2.LuaState
import ru.pyxiion.luanova.vm2.LuaValue

/** Runs the packages of a [PackagePlan] eagerly, dependencies first. */
object PackageLoader {
    /** [loaded] ids that ran; [errors] one "pkg:id: reason" line per package that did not. */
    class Run(val loaded: List<String>, val errors: List<String>) {
        val total: Int get() = loaded.size + errors.size
    }

    fun runAll(state: LuaState, plan: PackagePlan): Run {
        val loaded = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val reported = HashSet<String>()
        fun report(id: String, reason: String) {
            if (!reported.add(id)) return
            errors += "pkg:$id: $reason"
            ScriptErrors.error("package '$id'", "not loaded: $reason")
        }

        // Packages that never made it into the order: missing or mismatched dependencies, duplicates, cycles.
        for ((id, reason) in plan.failures.toList()) report(id, reason)

        val require = state.globals.get("require")
        for (id in plan.order) {
            val failure = plan.failure(id)
            if (failure != null) {
                report(id, failure)
                continue
            }
            try {
                ScriptWatchdog.guard { require.call(LuaValue.valueOf("pkg:$id")) }
                loaded += id
            } catch (e: Exception) {
                // Already sent to ScriptErrors by the module loader.
                if (reported.add(id)) errors += "pkg:$id: ${e.message}"
            }
        }
        return Run(loaded, errors)
    }
}
