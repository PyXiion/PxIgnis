package ru.pyxiion.ignis.packages

import java.util.PriorityQueue

/**
 * Which packages run and in which order. Pure: built from manifests and the installed plugin versions, no files.
 *
 * - [order]: packages that can run, dependencies first (`depends` and `optional` edges), ids break ties.
 * - [failures]: package id (or the folder for an unreadable one) to the reason it is skipped. A package that failed
 *   takes everything that lists it in `depends` with it; `optional` dependents still run.
 */
class PackagePlan private constructor(
    val manifests: Map<String, PackageManifest>,
    val order: List<String>,
    private val failed: MutableMap<String, String>,
) {
    val failures: Map<String, String> get() = failed

    fun failure(id: String): String? = failed[id]

    /**
     * Marks [id] failed with [reason] and, transitively, every not yet failed package that lists it in `depends`.
     * Returns the newly skipped ids with their reason, [id] first.
     */
    fun fail(id: String, reason: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        if (failed.putIfAbsent(id, reason) == null) out += id to reason
        cascade(failed, manifests, out)
        return out
    }

    companion object {
        /**
         * @param plugins installed plugin id to its version.
         * @param duplicates ids that appeared in several folders, with the folder paths.
         */
        fun build(
            all: List<PackageManifest>,
            plugins: Map<String, String>,
            duplicates: Map<String, List<String>> = emptyMap(),
        ): PackagePlan {
            val failed = linkedMapOf<String, String>()
            val manifests = all.sortedBy { it.id }.associateBy { it.id }
            for ((id, paths) in duplicates) {
                failed[id] = "duplicate package id '$id': ${paths.joinToString(" and ")}"
            }

            for (m in manifests.values) {
                if (m.id in failed) continue
                for (d in m.dependencies) {
                    val problem = when (d.kind) {
                        Dependency.Kind.PKG -> {
                            val dep = manifests[d.id]
                            when {
                                dep == null && d.id in failed -> if (d.optional) null else "dependency '${d.ref}' failed: ${failed[d.id]}"
                                dep == null -> if (d.optional) null else "requires ${d.ref}, which is not installed"
                                !d.accepts(dep.version) -> "requires ${d.ref} ${d.range}, but ${dep.version} is installed"
                                else -> null
                            }
                        }
                        Dependency.Kind.EXT -> {
                            val v = plugins[d.id]
                            when {
                                v == null -> if (d.optional) null else "requires plugin '${d.id}', which is not installed"
                                !d.accepts(v) -> "requires ${d.ref} ${d.range}, but $v is installed"
                                else -> null
                            }
                        }
                    }
                    if (problem != null) {
                        failed[m.id] = problem
                        break
                    }
                }
            }
            cascade(failed, manifests, mutableListOf())

            val order = mutableListOf<String>()
            while (true) {
                order.clear()
                val alive = manifests.keys.filter { it !in failed }
                val leftover = topo(alive, manifests, failed, order)
                if (leftover.isEmpty()) break
                val cycle = findCycle(leftover, manifests)
                val chain = (cycle + cycle.first()).joinToString(" -> ")
                for (id in cycle) failed[id] = "dependency cycle: $chain"
                cascade(failed, manifests, mutableListOf())
            }
            return PackagePlan(manifests, order.toList(), failed)
        }

        /** Transitive `depends` dependents of failed packages. */
        private fun cascade(
            failed: MutableMap<String, String>,
            manifests: Map<String, PackageManifest>,
            out: MutableList<Pair<String, String>>,
        ) {
            var changed = true
            while (changed) {
                changed = false
                for (m in manifests.values) {
                    if (m.id in failed) continue
                    val bad = m.depends.firstOrNull { it.kind == Dependency.Kind.PKG && it.id in failed } ?: continue
                    val reason = "dependency '${bad.id}' failed: ${failed[bad.id]}"
                    failed[m.id] = reason
                    out += m.id to reason
                    changed = true
                }
            }
        }

        private fun edges(m: PackageManifest, manifests: Map<String, PackageManifest>, failed: Map<String, String>): List<String> =
            m.dependencies.filter { it.kind == Dependency.Kind.PKG && it.id in manifests && it.id !in failed }.map { it.id }

        /** Kahn's algorithm with an id-ordered queue; appends to [out], returns the ids stuck in cycles or behind them. */
        private fun topo(
            alive: List<String>,
            manifests: Map<String, PackageManifest>,
            failed: Map<String, String>,
            out: MutableList<String>,
        ): Set<String> {
            val aliveSet = alive.toSet()
            val deps = alive.associateWith { id -> edges(manifests.getValue(id), manifests, failed).filter { it in aliveSet }.toSet() }
            val remaining = deps.mapValues { it.value.size }.toMutableMap()
            val dependents = HashMap<String, MutableList<String>>()
            for ((id, ds) in deps) for (d in ds) dependents.getOrPut(d) { mutableListOf() } += id
            val ready = PriorityQueue<String>()
            remaining.filterValues { it == 0 }.keys.forEach(ready::add)
            while (ready.isNotEmpty()) {
                val id = ready.poll()
                out += id
                for (n in dependents[id].orEmpty()) {
                    val left = remaining.getValue(n) - 1
                    remaining[n] = left
                    if (left == 0) ready.add(n)
                }
            }
            return alive.filter { it !in out }.toSet()
        }

        /** Some cycle among [stuck] as a list of ids in dependency order. */
        private fun findCycle(stuck: Set<String>, manifests: Map<String, PackageManifest>): List<String> {
            val next = stuck.sorted().associateWith { id ->
                manifests.getValue(id).dependencies
                    .filter { it.kind == Dependency.Kind.PKG && it.id in stuck }.map { it.id }.sorted()
            }
            val path = mutableListOf<String>()
            val onPath = HashSet<String>()
            val done = HashSet<String>()
            fun dfs(id: String): List<String>? {
                path += id
                onPath += id
                for (n in next.getValue(id)) {
                    if (n in onPath) return path.subList(path.indexOf(n), path.size).toList()
                    if (n !in done) dfs(n)?.let { return it }
                }
                path.removeLast()
                onPath -= id
                done += id
                return null
            }
            for (id in next.keys) if (id !in done) dfs(id)?.let { return it }
            return stuck.sorted()
        }
    }
}
